%% Test double of a 2PC participant (docs/2pc-protocol.md §3-4), used to test the
%% coordinator before the real participant side exists. Registered under a name; its
%% behaviour can be changed at run time.
%%
%% Behaviour map:
%%   vote       => yes | {no, Reason} | silent
%%   ack_after  => N   ack only from the N-th time it receives the same decision (default 1)
%%   trace      => ets table where it inserts {unique_integer(), {participant, Name, Decision}}
-module(fake_participant).
-export([start/2, stop/1, set/2, state/1, prepares/1]).

start(Name, Behaviour) ->
    Pid = spawn(fun() -> register(Name, self()), loop(Name, Behaviour, #{}, 0) end),
    wait_registered(Name, 50),
    Pid.

stop(Name) ->
    call(Name, stop).

set(Name, Behaviour) ->
    call(Name, {set, Behaviour}).

%% #{TxId => {prepared | committed | aborted, Items, TimesDecisionSeen}}
state(Name) ->
    call(Name, state).

%% How many prepare messages it received.
prepares(Name) ->
    call(Name, prepares).

call(Name, Msg) ->
    Ref = make_ref(),
    Name ! {call, self(), Ref, Msg},
    receive {Ref, Reply} -> Reply after 2000 -> error({no_reply, Name, Msg}) end.

loop(Name, B, Txs, Prepares) ->
    Me = {Name, node()},
    receive
        {fs_2pc, prepare, _Epoch, TxId, Coord, _User, Items} ->
            case maps:get(vote, B, yes) of
                yes ->
                    Coord ! {fs_2pc, vote, TxId, Me, yes},
                    loop(Name, B, Txs#{TxId => {prepared, Items, 0}}, Prepares + 1);
                {no, Reason} ->
                    Coord ! {fs_2pc, vote, TxId, Me, {no, Reason}},
                    loop(Name, B, Txs, Prepares + 1);
                silent ->
                    loop(Name, B, Txs, Prepares + 1)
            end;
        {fs_2pc, Decision, _Epoch, TxId, Coord} when Decision =:= commit; Decision =:= abort ->
            {_, Items, Seen} = maps:get(TxId, Txs, {none, [], 0}),
            trace(B, {participant, Name, Decision}),
            Final = case Decision of commit -> committed; abort -> aborted end,
            Txs2 = Txs#{TxId => {Final, Items, Seen + 1}},
            case Seen + 1 >= maps:get(ack_after, B, 1) of
                true ->
                    Ids = case Decision of
                              commit -> maps:from_list([{R, <<"b-", R/binary>>} || {R, _} <- Items, is_binary(R)]);
                              abort -> #{}
                          end,
                    Coord ! {fs_2pc, ack, TxId, Me, Ids};
                false ->
                    ok
            end,
            loop(Name, B, Txs2, Prepares);
        {call, From, Ref, stop} ->
            From ! {Ref, ok};
        {call, From, Ref, {set, B2}} ->
            From ! {Ref, ok},
            loop(Name, B2, Txs, Prepares);
        {call, From, Ref, state} ->
            From ! {Ref, Txs},
            loop(Name, B, Txs, Prepares);
        {call, From, Ref, prepares} ->
            From ! {Ref, Prepares},
            loop(Name, B, Txs, Prepares)
    end.

trace(#{trace := Tab}, Event) -> ets:insert(Tab, {erlang:unique_integer([monotonic]), Event});
trace(_, _) -> ok.

wait_registered(_Name, 0) -> error(not_registered);
wait_registered(Name, N) ->
    case whereis(Name) of
        undefined -> timer:sleep(5), wait_registered(Name, N - 1);
        _ -> ok
    end.
