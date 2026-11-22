%% EUnit tests of the 2PC coordinator against fake participants (test/fake_participant.erl).
%% Three participants on this node: rooms, equipment and the quota shard.
-module(fairslot_coordinator_tests).
-include_lib("eunit/include/eunit.hrl").

-define(ROOM, <<"room:B2-201">>).
-define(PROJ, <<"equip:proj-01">>).
-define(SLOT, 1795680000000).

router({resource, <<"room:", _/binary>>}) -> {fake_rooms, node()};
router({resource, _}) -> {fake_equip, node()};
router({quota, _User}) -> {fake_quota, node()}.

start(Trace) ->
    {ok, _} = fairslot_decision_log:start_link(#{replicate => fun(E) -> trace(Trace, {log, element(1, E)}), ok end}),
    {ok, _} = fairslot_coordinator:start_link(#{router => fun router/1, vote_timeout => 300,
                                                resend_interval => 50, ack_wait => 500, abort_wait => 200}),
    [fake_participant:start(N, #{trace => Trace}) || N <- [fake_rooms, fake_equip, fake_quota]],
    ok.

stop() ->
    [fake_participant:stop(N) || N <- [fake_rooms, fake_equip, fake_quota]],
    fairslot_coordinator:stop(),
    fairslot_decision_log:stop().

trace(Tab, Event) ->
    ets:insert(Tab, {erlang:unique_integer([monotonic]), Event}).

events(Tab) ->
    [E || {_, E} <- lists:sort(ets:tab2list(Tab))].

book(ReqId, Items) ->
    ok = fairslot_coordinator:book_bundle(ReqId, <<"alice">>, Items, self()),
    receive {fs_reply, ReqId, Result} -> Result after 3000 -> error(no_reply) end.

bundle() ->
    [{?ROOM, ?SLOT}, {?PROJ, ?SLOT}].

%% Waits until the coordinator has written {done, TxId} (it does so after the reply).
wait_done(TxId) ->
    wait_done(TxId, 100).
wait_done(TxId, 0) -> error({still_in_log, TxId});
wait_done(TxId, N) ->
    case fairslot_decision_log:lookup(TxId) of
        none -> ok;
        _ -> timer:sleep(20), wait_done(TxId, N - 1)
    end.

coordinator_test_() ->
    {foreach,
     fun() -> Tab = ets:new(trace, [public, ordered_set]), start(Tab), Tab end,
     fun(_) -> stop() end,
     [fun all_yes_commits_everywhere/1,
      fun one_no_aborts_everywhere/1,
      fun quota_no_aborts/1,
      fun silent_participant_aborts_after_timeout/1,
      fun commit_is_logged_before_it_is_sent/1,
      fun lost_ack_is_resent_until_acked/1,
      fun resent_request_joins_same_transaction/1,
      fun recovery_finishes_logged_transactions/1,
      fun query_answers_from_the_log/1,
      fun malformed_bundle_is_rejected/1]}.

all_yes_commits_everywhere(_Tab) ->
    fun() ->
        {ok, #{bundle_id := <<"tx-1">>, booking_ids := Ids}} = book(<<"tx-1">>, bundle()),
        ?assertEqual([<<"b-room:B2-201">>, <<"b-equip:proj-01">>], Ids),
        ?assertMatch(#{<<"tx-1">> := {committed, [{?ROOM, ?SLOT}], 1}}, fake_participant:state(fake_rooms)),
        ?assertMatch(#{<<"tx-1">> := {committed, [{?PROJ, ?SLOT}], 1}}, fake_participant:state(fake_equip)),
        %% the quota shard is a participant too, with the hours of the whole bundle
        ?assertMatch(#{<<"tx-1">> := {committed, [{quota, 2}], 1}}, fake_participant:state(fake_quota)),
        wait_done(<<"tx-1">>)
    end.

one_no_aborts_everywhere(_Tab) ->
    fun() ->
        fake_participant:set(fake_equip, #{vote => {no, {slot_taken, ?PROJ}}}),
        ?assertEqual({error, {slot_taken, ?PROJ}}, book(<<"tx-2">>, bundle())),
        wait_done(<<"tx-2">>),
        ?assertMatch(#{<<"tx-2">> := {aborted, _, _}}, fake_participant:state(fake_rooms)),
        ?assertMatch(#{<<"tx-2">> := {aborted, _, _}}, fake_participant:state(fake_quota))
    end.

quota_no_aborts(_Tab) ->
    fun() ->
        fake_participant:set(fake_quota, #{vote => {no, quota_exceeded}}),
        ?assertEqual({error, quota_exceeded}, book(<<"tx-3">>, bundle())),
        wait_done(<<"tx-3">>),
        ?assertMatch(#{<<"tx-3">> := {aborted, _, _}}, fake_participant:state(fake_rooms))
    end.

silent_participant_aborts_after_timeout(_Tab) ->
    fun() ->
        fake_participant:set(fake_equip, #{vote => silent}),
        T0 = erlang:monotonic_time(millisecond),
        ?assertEqual({error, {participant_unavailable, node()}}, book(<<"tx-4">>, bundle())),
        ?assert(erlang:monotonic_time(millisecond) - T0 >= 300),
        wait_done(<<"tx-4">>),
        ?assertMatch(#{<<"tx-4">> := {aborted, _, _}}, fake_participant:state(fake_rooms))
    end.

commit_is_logged_before_it_is_sent(Tab) ->
    fun() ->
        {ok, _} = book(<<"tx-5">>, bundle()),
        wait_done(<<"tx-5">>),
        Events = events(Tab),
        [FirstCommit | _] = [E || E = {participant, _, commit} <- Events],
        ?assert(index({log, commit}, Events) < index(FirstCommit, Events)),
        ?assertEqual([{log, 'begin'}, {log, commit}, {log, done}], [E || E = {log, _} <- Events])
    end.

lost_ack_is_resent_until_acked(_Tab) ->
    fun() ->
        fake_participant:set(fake_equip, #{ack_after => 3}),   % ignores the first two commits
        {ok, #{booking_ids := [_, _]}} = book(<<"tx-6">>, bundle()),
        wait_done(<<"tx-6">>),
        ?assertMatch(#{<<"tx-6">> := {committed, _, 3}}, fake_participant:state(fake_equip))
    end.

resent_request_joins_same_transaction(_Tab) ->
    fun() ->
        fake_participant:set(fake_equip, #{vote => silent}),
        ok = fairslot_coordinator:book_bundle(<<"tx-7">>, <<"alice">>, bundle(), self()),
        ok = fairslot_coordinator:book_bundle(<<"tx-7">>, <<"alice">>, bundle(), self()),
        R1 = receive {fs_reply, <<"tx-7">>, A} -> A after 2000 -> error(no_reply) end,
        R2 = receive {fs_reply, <<"tx-7">>, B} -> B after 2000 -> error(no_reply) end,
        ?assertEqual(R1, R2),
        ?assertEqual(1, fake_participant:prepares(fake_rooms)),
        %% and after it finished, a third copy gets the stored result without a new prepare
        ?assertEqual(R1, book(<<"tx-7">>, bundle())),
        ?assertEqual(1, fake_participant:prepares(fake_rooms))
    end.

recovery_finishes_logged_transactions(_Tab) ->
    fun() ->
        Parts = [{fake_rooms, node()}, {fake_equip, node()}],
        %% what a crashed coordinator left behind: one undecided, one committed
        ok = fairslot_decision_log:install(#{<<"old-1">> => {'begin', Parts},
                                             <<"old-2">> => {commit, Parts}}),
        ?assertEqual({ok, 2}, fairslot_coordinator:recover()),
        wait_done(<<"old-1">>),
        wait_done(<<"old-2">>),
        ?assertMatch(#{<<"old-1">> := {aborted, _, _}, <<"old-2">> := {committed, _, _}},
                     fake_participant:state(fake_rooms)),
        ?assertMatch(#{<<"old-1">> := {aborted, _, _}, <<"old-2">> := {committed, _, _}},
                     fake_participant:state(fake_equip))
    end.

query_answers_from_the_log(_Tab) ->
    fun() ->
        ok = fairslot_decision_log:install(#{<<"q-1">> => {commit, []}}),
        fairslot_coordinator ! {fs_2pc, query, 0, <<"q-1">>, self()},
        fairslot_coordinator ! {fs_2pc, query, 0, <<"unknown">>, self()},
        ?assertEqual(commit, receive {fs_2pc, decision, <<"q-1">>, D1} -> D1 after 1000 -> timeout end),
        ?assertEqual(abort, receive {fs_2pc, decision, <<"unknown">>, D2} -> D2 after 1000 -> timeout end)
    end.

malformed_bundle_is_rejected(_Tab) ->
    fun() ->
        ?assertMatch({error, {bad_request, _}}, book(<<"bad">>, [])),
        ?assertMatch({error, {bad_request, _}}, book(<<"bad2">>, [{"not-binary", 1}])),
        ?assertEqual(0, fake_participant:prepares(fake_rooms))
    end.

index(E, List) ->
    index(E, List, 1).
index(E, [E | _], N) -> N;
index(E, [_ | T], N) -> index(E, T, N + 1).
