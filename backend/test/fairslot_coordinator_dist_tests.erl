%% The coordinator against participants on real Erlang nodes (started with OTP's `peer`):
%% rooms on node A, equipment and the quota shard on node B.
-module(fairslot_coordinator_dist_tests).
-include_lib("eunit/include/eunit.hrl").

-define(ROOM, <<"room:B2-201">>).
-define(PROJ, <<"equip:proj-01">>).
-define(SLOT, 1795680000000).

dist_test_() ->
    {setup, fun setup/0, fun cleanup/1,
     fun(Ctx) ->
         [{"bundle commits on two nodes", fun() -> commits_on_two_nodes(Ctx) end},
          {"participant acks late: commit survives, then completes", fun() -> late_ack(Ctx) end},
          {"participant node down: abort", fun() -> node_down_aborts(Ctx) end}]
     end}.

setup() ->
    ensure_epmd(),
    WasAlive = is_alive(),
    WasAlive orelse
        begin
            {ok, _} = net_kernel:start(list_to_atom("coord_test_" ++ os:getpid()), #{name_domain => shortnames}),
            true
        end,
    TestDir = filename:dirname(code:which(fake_participant)),
    Args = ["-pa", TestDir, "-setcookie", atom_to_list(erlang:get_cookie())],
    {ok, PA, NodeA} = peer:start_link(#{name => peer:random_name(rooms), args => Args}),
    {ok, PB, NodeB} = peer:start_link(#{name => peer:random_name(equip), args => Args}),
    [erpc:call(N, fake_participant, start, [fairslot_gateway, #{}]) || N <- [NodeA, NodeB]],
    Router = fun({resource, <<"room:", _/binary>>}) -> {fairslot_gateway, NodeA};
                ({resource, _}) -> {fairslot_gateway, NodeB};
                ({quota, _}) -> {fairslot_gateway, NodeB}
             end,
    {ok, _} = fairslot_decision_log:start_link(),
    {ok, _} = fairslot_coordinator:start_link(#{router => Router, vote_timeout => 1000,
                                                resend_interval => 100, ack_wait => 500}),
    #{pa => PA, pb => PB, a => NodeA, b => NodeB, was_alive => WasAlive}.

cleanup(#{pa := PA, pb := PB, was_alive := WasAlive}) ->
    fairslot_coordinator:stop(),
    fairslot_decision_log:stop(),
    [try peer:stop(P) catch _:_ -> ok end || P <- [PA, PB]],   % B is already stopped by a test
    WasAlive orelse net_kernel:stop().

book(TxId) ->
    ok = fairslot_coordinator:book_bundle(TxId, <<"bob">>, [{?ROOM, ?SLOT}, {?PROJ, ?SLOT}], self()),
    receive {fs_reply, TxId, R} -> R after 5000 -> error(no_reply) end.

remote_state(Node) ->
    erpc:call(Node, fake_participant, state, [fairslot_gateway]).

commits_on_two_nodes(#{a := NodeA, b := NodeB}) ->
    ?assertEqual({ok, #{bundle_id => <<"d-1">>, booking_ids => [<<"b-room:B2-201">>, <<"b-equip:proj-01">>]}},
                 book(<<"d-1">>)),
    ?assertMatch(#{<<"d-1">> := {committed, [{?ROOM, ?SLOT}], _}}, remote_state(NodeA)),
    ?assertMatch(#{<<"d-1">> := {committed, [{?PROJ, ?SLOT}, {quota, 2}], _}}, remote_state(NodeB)).

late_ack(#{b := NodeB}) ->
    %% B votes yes but does not ack in time (e.g. busy replicating to its backup).
    erpc:call(NodeB, fake_participant, set, [fairslot_gateway, #{ack_after => 8}]),
    {ok, #{booking_ids := [<<"b-room:B2-201">>, <<"pending">>]}} = book(<<"d-2">>),
    %% the commit decision stays in the log until B acks ...
    ?assertMatch({ok, {commit, _}}, fairslot_decision_log:lookup(<<"d-2">>)),
    %% ... and the coordinator keeps resending until it does
    wait(fun() -> fairslot_decision_log:lookup(<<"d-2">>) =:= none end),
    ?assertMatch(#{<<"d-2">> := {committed, _, 8}}, remote_state(NodeB)),
    erpc:call(NodeB, fake_participant, set, [fairslot_gateway, #{}]).

node_down_aborts(#{pb := PB, a := NodeA, b := NodeB}) ->
    peer:stop(PB),
    T0 = erlang:monotonic_time(millisecond),
    ?assertEqual({error, {participant_unavailable, NodeB}}, book(<<"d-3">>)),
    %% the user hears back within the vote timeout (1 s), not after connection retries
    Elapsed = erlang:monotonic_time(millisecond) - T0,
    ?assert(Elapsed < 1500),
    wait(fun() -> fairslot_decision_log:lookup(<<"d-3">>) =:= none end),
    ?assertMatch(#{<<"d-3">> := {aborted, _, _}}, remote_state(NodeA)).

%% Distribution needs EPMD. `os:cmd("epmd -daemon")` never returns on Windows (the daemon
%% keeps the output pipe open), so start it from a throwaway process and poll.
ensure_epmd() ->
    case erl_epmd:names() of
        {ok, _} -> ok;
        _ ->
            spawn(fun() -> os:cmd("epmd -daemon") end),
            wait(fun() -> element(1, erl_epmd:names()) =:= ok end)
    end.

wait(Cond) ->
    wait(Cond, 100).
wait(_Cond, 0) -> error(timeout);
wait(Cond, N) ->
    case Cond() of
        true -> ok;
        false -> timer:sleep(50), wait(Cond, N - 1)
    end.
