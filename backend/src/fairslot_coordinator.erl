%% 2PC coordinator for bundle bookings (docs/2pc-protocol.md).
%%
%% Owner: Roger. Each bundle runs in its own transaction process, so a slow participant
%% never blocks other bundles. Presumed abort: only `begin` and `commit` are logged, and
%% the commit decision is logged (and replicated, see fairslot_decision_log) before any
%% participant is told to commit.
%%
%% Routing is a fun given at start-up, so the partition map (owned by the coordinator,
%% week 3) can be plugged in without touching the protocol code:
%%   Router({resource, ResId}) -> {fairslot_gateway, OwnerNode}
%%   Router({quota, User})     -> {fairslot_gateway, QuotaShardNode}
-module(fairslot_coordinator).
-behaviour(gen_server).

-export([start_link/1, stop/0, book_bundle/4, recover/0, set_epoch/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(DEFAULTS, #{epoch => 0,
                    vote_timeout => 5000,      % no vote by then = abort
                    resend_interval => 1000,   % resend the decision to participants that did not ack
                    ack_wait => 3000,          % how long the web tier waits for commit acks
                    abort_wait => 2000,        % best-effort wait for abort acks (presumed abort)
                    log => fairslot_decision_log}).

%% ------------------------------------------------------------------ API

%% Opts: #{router := fun(), epoch, vote_timeout, resend_interval, ack_wait, abort_wait, log}
start_link(Opts) ->
    gen_server:start_link({local, ?MODULE}, ?MODULE, Opts, []).

stop() ->
    gen_server:stop(?MODULE).

%% Called by the gateway for {fs_req, ReqId, Epoch, From, User, {book_bundle, Items}}.
%% Returns at once; the result goes to From as {fs_reply, ReqId, Result}.
-spec book_bundle(binary(), binary(), [{binary(), integer()}], pid()) -> ok.
book_bundle(ReqId, User, Items, From) ->
    gen_server:cast(?MODULE, {book_bundle, ReqId, User, Items, From}).

%% Finishes the transactions left in the decision log by a previous coordinator.
recover() ->
    gen_server:call(?MODULE, recover).

set_epoch(Epoch) ->
    gen_server:call(?MODULE, {set_epoch, Epoch}).

%% ------------------------------------------------------------------ gen_server

init(Opts) ->
    Cfg = maps:merge(?DEFAULTS, Opts),
    true = is_function(maps:get(router, Cfg), 1),
    {ok, #{cfg => Cfg,
           running => #{},     % TxId => {Pid, [From]}
           results => #{}}}.   % TxId => Result, to answer resent requests

handle_call(recover, _From, S = #{cfg := Cfg}) ->
    Log = maps:get(log, Cfg),
    Pending = Log:pending(),
    lists:foreach(fun({TxId, {State, Parts}}) ->
                          spawn(fun() -> complete(TxId, State, Parts, Cfg) end)
                  end, Pending),
    {reply, {ok, length(Pending)}, S};
handle_call({set_epoch, Epoch}, _From, S = #{cfg := Cfg}) ->
    {reply, ok, S#{cfg := Cfg#{epoch := Epoch}}}.

handle_cast({book_bundle, TxId, User, Items, From}, S = #{running := Running, results := Results}) ->
    case {maps:find(TxId, Results), maps:find(TxId, Running)} of
        {{ok, Result}, _} ->
            From ! {fs_reply, TxId, Result},
            {noreply, S};
        {error, {ok, {Pid, Waiters}}} ->
            {noreply, S#{running := Running#{TxId := {Pid, [From | Waiters]}}}};
        {error, error} ->
            case valid_items(Items) of
                true ->
                    Self = self(),
                    Cfg = maps:get(cfg, S),
                    {Pid, _Ref} = spawn_monitor(fun() -> run_tx(Self, TxId, User, Items, Cfg) end),
                    {noreply, S#{running := Running#{TxId => {Pid, [From]}}}};
                false ->
                    From ! {fs_reply, TxId, {error, {bad_request, <<"a bundle is a list of {ResId, SlotStart}">>}}},
                    {noreply, S}
            end
    end.

handle_info({tx_result, TxId, Result}, S) ->
    {noreply, finish_request(TxId, Result, S)};
handle_info({'DOWN', _Ref, process, Pid, Reason}, S = #{running := Running, cfg := Cfg}) when Reason =/= normal ->
    %% The transaction process crashed before answering: let the log decide, as after a failover.
    case [TxId || {TxId, {P, _}} <- maps:to_list(Running), P =:= Pid] of
        [TxId] ->
            Log = maps:get(log, Cfg),
            case Log:lookup(TxId) of
                {ok, {State, Parts}} -> spawn(fun() -> complete(TxId, State, Parts, Cfg) end);
                none -> ok
            end,
            {noreply, finish_request(TxId, {error, coordinator_failure}, S)};
        [] ->
            {noreply, S}
    end;
handle_info({fs_2pc, query, _Epoch, TxId, From}, S = #{cfg := Cfg, running := Running}) ->
    Log = maps:get(log, Cfg),
    case Log:lookup(TxId) of
        {ok, {commit, _}} -> From ! {fs_2pc, decision, TxId, commit};
        {ok, {'begin', _}} when is_map_key(TxId, Running) -> ok;   % still collecting votes: ask again later
        _ -> From ! {fs_2pc, decision, TxId, abort}                   % presumed abort
    end,
    {noreply, S};
handle_info(_Other, S) ->
    {noreply, S}.

finish_request(TxId, Result, S = #{running := Running, results := Results}) ->
    case maps:take(TxId, Running) of
        {{_Pid, Waiters}, Rest} ->
            [W ! {fs_reply, TxId, Result} || W <- Waiters],
            S#{running := Rest, results := Results#{TxId => Result}};
        error ->
            S
    end.

%% ------------------------------------------------------------------ one transaction

run_tx(Parent, TxId, User, Items, Cfg = #{log := Log, epoch := Epoch}) ->
    Groups = group(Items, User, maps:get(router, Cfg)),
    Parts = maps:keys(Groups),
    ok = Log:append({'begin', TxId, Parts}),
    %% The deadline starts before sending: `!` to a node that is down blocks while Erlang
    %% tries to connect, and that time must count against the vote timeout.
    Deadline = now_ms() + maps:get(vote_timeout, Cfg),
    maps:foreach(fun(Dest, Its) -> Dest ! {fs_2pc, prepare, Epoch, TxId, self(), User, Its} end, Groups),
    case collect_votes(TxId, Parts, Deadline) of
        yes ->
            ok = Log:append({commit, TxId}),
            {Ids, Missing} = decide(commit, TxId, Parts, Cfg, maps:get(ack_wait, Cfg)),
            BookingIds = [maps:get(ResId, Ids, <<"pending">>) || {ResId, _} <- Items],
            Parent ! {tx_result, TxId, {ok, #{bundle_id => TxId, booking_ids => BookingIds}}},
            %% Committed is final: keep telling the slow participants until they ack.
            _ = decide(commit, TxId, Missing, Cfg, infinity),
            ok = Log:append({done, TxId});
        {no, Reason} ->
            Parent ! {tx_result, TxId, {error, Reason}},
            _ = decide(abort, TxId, Parts, Cfg, maps:get(abort_wait, Cfg)),
            ok = Log:append({done, TxId})
    end.

%% Finishes a transaction found in the log (recovery or a crashed transaction process).
complete(TxId, commit, Parts, Cfg = #{log := Log}) ->
    _ = decide(commit, TxId, Parts, Cfg, infinity),
    ok = Log:append({done, TxId});
complete(TxId, 'begin', Parts, Cfg = #{log := Log}) ->
    _ = decide(abort, TxId, Parts, Cfg, maps:get(abort_wait, Cfg)),
    ok = Log:append({done, TxId}).

collect_votes(_TxId, [], _Deadline) ->
    yes;
collect_votes(TxId, Pending, Deadline) ->
    receive
        {fs_2pc, vote, TxId, From, yes} ->
            collect_votes(TxId, lists:delete(From, Pending), Deadline);
        {fs_2pc, vote, TxId, _From, {no, Reason}} ->
            {no, Reason}
    after max(0, Deadline - now_ms()) ->
        {_, Node} = hd(Pending),
        {no, {participant_unavailable, Node}}
    end.

%% Sends the decision to Parts and collects acks, resending to the silent ones every
%% resend_interval, until all acked or Wait ms passed. Returns {BookingIds, NotAcked}.
decide(_Decision, _TxId, [], _Cfg, _Wait) ->
    {#{}, []};
decide(Decision, TxId, Parts, Cfg = #{epoch := Epoch}, Wait) ->
    Send = fun(Dests) -> [D ! {fs_2pc, Decision, Epoch, TxId, self()} || D <- Dests] end,
    Send(Parts),
    Until = case Wait of infinity -> infinity; _ -> now_ms() + Wait end,
    wait_acks(TxId, Parts, #{}, Send, maps:get(resend_interval, Cfg), Until).

wait_acks(_TxId, [], Ids, _Send, _Interval, _Until) ->
    {Ids, []};
wait_acks(TxId, Missing, Ids, Send, Interval, Until) ->
    Left = case Until of infinity -> Interval; _ -> min(Interval, Until - now_ms()) end,
    if
        Left =< 0 ->
            {Ids, Missing};
        true ->
            receive
                {fs_2pc, ack, TxId, From, BookingIds} ->
                    wait_acks(TxId, lists:delete(From, Missing), maps:merge(Ids, BookingIds), Send, Interval, Until)
            after Left ->
                Send(Missing),
                wait_acks(TxId, Missing, Ids, Send, Interval, Until)
            end
    end.

%% #{Participant => Items}: each resource to its owner, plus {quota, Hours} to the user's shard.
group(Items, User, Router) ->
    ByOwner = lists:foldl(fun(Item = {ResId, _Start}, Acc) ->
                                  maps:update_with(Router({resource, ResId}), fun(L) -> L ++ [Item] end, [Item], Acc)
                          end, #{}, Items),
    Quota = {quota, length(Items)},
    maps:update_with(Router({quota, User}), fun(L) -> L ++ [Quota] end, [Quota], ByOwner).

valid_items(Items) when is_list(Items), Items =/= [] ->
    lists:all(fun({R, S}) -> is_binary(R) andalso is_integer(S); (_) -> false end, Items);
valid_items(_) ->
    false.

now_ms() ->
    erlang:monotonic_time(millisecond).
