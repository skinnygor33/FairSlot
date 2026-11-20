%% Throwaway single-node stand-in for the FairSlot back-end.
%%
%% Purpose: let the web tier (Java, JInterface) be developed and tested against a real
%% Erlang node before the real back-end exists, and run the week 1 proof of concept.
%% It speaks docs/message-contract.md but implements only hold / confirm / release /
%% cancel / availability / quota. No partitions, quotas per week, waitlists or replication.
%% NOT the real back-end: that is backend/ (Macarena).
%%
%% Run:  erlc fairslot_stub.erl
%%       erl -sname fairslot_a -setcookie <cookie> -noshell -eval "fairslot_stub:start()"
%% Env:  FAIRSLOT_STUB_HOLD_MS  hold length in ms (default 300000 = 5 min)
-module(fairslot_stub).
-export([start/0, init/0]).

-define(HOUR, 3600000).
-define(QUOTA_HOURS, 10).
-define(FIRST_HOUR, 8).
-define(LAST_HOUR, 21).

start() ->
    Pid = spawn(fun init/0),
    {ok, Pid}.

init() ->
    register(fairslot_gateway, self()),
    io:format("fairslot_stub: gateway ready on ~p~n", [node()]),
    loop(#{slots => #{},       % {ResId, Start} => {held, User, HoldId} | {booked, User, BookingId}
           holds => #{},       % HoldId => {ResId, Start}
           bookings => #{},    % BookingId => {ResId, Start}
           quota => #{},       % User => hours used
           done => #{},        % ReqId => Result (duplicate detection)
           web => undefined,   % node of the last web-tier caller, for events
           next => 1}).

loop(S0) ->
    receive
        {fs_req, ReqId, _Epoch, From, User, Op} ->
            S1 = S0#{web := node(From)},
            {Result, S2} =
                case maps:find(ReqId, maps:get(done, S1)) of
                    {ok, Stored} -> {Stored, S1};
                    error -> remember(ReqId, Op, handle(User, Op, S1))
                end,
            From ! {fs_reply, ReqId, Result},
            loop(S2);
        {expire_hold, HoldId} ->
            loop(expire_hold(HoldId, S0));
        stop_node ->
            init:stop();
        Other ->
            io:format("fairslot_stub: ignoring ~p~n", [Other]),
            loop(S0)
    end.

%% Reads do not need duplicate detection; everything else is stored by request ID.
remember(_ReqId, Op, {Result, S}) when element(1, Op) =:= availability;
                                        element(1, Op) =:= quota;
                                        element(1, Op) =:= partition_map;
                                        element(1, Op) =:= debug_nodes ->
    {Result, S};
remember(ReqId, _Op, {Result, S = #{done := Done}}) ->
    {Result, S#{done := Done#{ReqId => Result}}}.

%% ------------------------------------------------------------------ operations

handle(_User, {partition_map}, S) ->
    {{ok, #{epoch => 0, coordinator => node(),
            partitions => #{<<"all">> => node()}, resources => #{}}}, S};

handle(_User, {debug_nodes}, S) ->
    {{ok, #{self => node(), visible => nodes(visible), hidden => nodes(hidden)}}, S};

handle(User, {availability, ResId, DayStart}, S = #{slots := Slots}) ->
    List = [#{start => T, state => state_for(User, maps:get({ResId, T}, Slots, free))}
            || H <- lists:seq(?FIRST_HOUR, ?LAST_HOUR - 1), T <- [DayStart + H * ?HOUR]],
    {{ok, #{slots => List}}, S};

handle(_User, {hold, _ResId, Start}, S) when Start rem ?HOUR =/= 0 ->
    {{error, {bad_request, <<"slot start must be on the hour">>}}, S};
handle(User, {hold, ResId, Start}, S = #{slots := Slots, holds := Holds, quota := Quota}) ->
    Used = maps:get(User, Quota, 0),
    case maps:is_key({ResId, Start}, Slots) of
        true -> {{error, slot_taken}, S};
        false when Used >= ?QUOTA_HOURS -> {{error, quota_exceeded}, S};
        false ->
            {HoldId, S1} = new_id(<<"h-">>, S),
            HoldMs = hold_ms(),
            erlang:send_after(HoldMs, self(), {expire_hold, HoldId}),
            {{ok, #{hold_id => HoldId, expires_at => now_ms() + HoldMs}},
             S1#{slots := Slots#{{ResId, Start} => {held, User, HoldId}},
                 holds := Holds#{HoldId => {ResId, Start}},
                 quota := Quota#{User => Used + 1}}}
    end;

handle(User, {confirm, HoldId}, S = #{holds := Holds, slots := Slots, bookings := Bookings}) ->
    case maps:find(HoldId, Holds) of
        error -> {{error, not_found}, S};
        {ok, Key} ->
            case maps:get(Key, Slots) of
                {held, User, HoldId} ->
                    {BookingId, S1} = new_id(<<"b-">>, S),
                    {{ok, #{booking_id => BookingId}},
                     S1#{holds := maps:remove(HoldId, Holds),
                         slots := Slots#{Key => {booked, User, BookingId}},
                         bookings := Bookings#{BookingId => Key}}};
                _ -> {{error, not_holder}, S}
            end
    end;

handle(User, {release, HoldId}, S = #{holds := Holds, slots := Slots}) ->
    case maps:find(HoldId, Holds) of
        error -> {{error, not_found}, S};
        {ok, Key} ->
            case maps:get(Key, Slots) of
                {held, User, HoldId} -> {{ok, #{}}, free_slot(Key, User, S#{holds := maps:remove(HoldId, Holds)})};
                _ -> {{error, not_holder}, S}
            end
    end;

handle(User, {cancel, BookingId}, S = #{bookings := Bookings, slots := Slots}) ->
    case maps:find(BookingId, Bookings) of
        error -> {{error, not_found}, S};
        {ok, Key} ->
            case maps:get(Key, Slots) of
                {booked, User, BookingId} ->
                    {{ok, #{}}, free_slot(Key, User, S#{bookings := maps:remove(BookingId, Bookings)})};
                _ -> {{error, not_holder}, S}
            end
    end;

handle(User, {quota}, S = #{quota := Quota}) ->
    {{ok, #{used_hours => maps:get(User, Quota, 0), limit_hours => ?QUOTA_HOURS, week_start => 0}}, S};

handle(_User, Op, S) ->
    {{error, {bad_request, iolist_to_binary(io_lib:format("not supported by the stub: ~p", [element(1, Op)]))}}, S}.

%% ------------------------------------------------------------------ helpers

expire_hold(HoldId, S = #{holds := Holds, slots := Slots}) ->
    case maps:find(HoldId, Holds) of
        error -> S;   % already confirmed or released
        {ok, Key} ->
            {held, User, HoldId} = maps:get(Key, Slots),
            push(hold_expired, #{user => User, hold_id => HoldId}, S),
            free_slot(Key, User, S#{holds := maps:remove(HoldId, Holds)})
    end.

free_slot(Key = {ResId, Start}, User, S = #{slots := Slots, quota := Quota}) ->
    push(slot_freed, #{resource_id => ResId, slot_start => Start}, S),
    S#{slots := maps:remove(Key, Slots), quota := Quota#{User => max(0, maps:get(User, Quota, 1) - 1)}}.

%% Events go to the mailbox registered as 'events' on the web node (contract §5).
push(_Type, _Data, #{web := undefined}) -> ok;
push(Type, Data, #{web := WebNode}) -> {events, WebNode} ! {fs_event, Type, Data}, ok.

state_for(_User, free) -> free;
state_for(User, {_, User, _}) -> mine;
state_for(_User, {held, _, _}) -> held;
state_for(_User, {booked, _, _}) -> booked.

new_id(Prefix, S = #{next := N}) ->
    {<<Prefix/binary, (integer_to_binary(N))/binary>>, S#{next := N + 1}}.

hold_ms() ->
    case os:getenv("FAIRSLOT_STUB_HOLD_MS") of
        false -> 300000;
        V -> list_to_integer(V)
    end.

now_ms() -> erlang:system_time(millisecond).
