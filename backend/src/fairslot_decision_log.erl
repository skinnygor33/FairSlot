%% Decision log of the 2PC coordinator (docs/2pc-protocol.md §4).
%%
%% Owner: Roger. Keeps, per unfinished bundle transaction, whether it has only begun
%% (participants known, no decision: presumed abort) or has a commit decision.
%% append/1 returns only after the entry has also gone through the `replicate` hook, so a
%% decision is replicated before the coordinator sends it. Week 2 keeps the log in memory;
%% week 3 plugs replication to the other nodes into the hook and uses install/1 to load the
%% replicated log on a newly elected coordinator.
-module(fairslot_decision_log).
-behaviour(gen_server).

-export([start_link/0, start_link/1, stop/0,
         append/1, lookup/1, pending/0, install/1]).
-export([init/1, handle_call/3, handle_cast/2]).

-type tx_id() :: binary().
-type participant() :: {atom(), node()}.
-type entry() :: {'begin', tx_id(), [participant()]}
               | {commit, tx_id()}
               | {done, tx_id()}.
-type tx_state() :: {'begin' | commit, [participant()]}.
-export_type([entry/0, tx_state/0]).

%% ------------------------------------------------------------------ API

start_link() ->
    start_link(#{}).

%% Opts: #{replicate => fun((entry()) -> ok | {error, term()})}
start_link(Opts) ->
    gen_server:start_link({local, ?MODULE}, ?MODULE, Opts, []).

stop() ->
    gen_server:stop(?MODULE).

-spec append(entry()) -> ok | {error, term()}.
append(Entry) ->
    gen_server:call(?MODULE, {append, Entry}).

-spec lookup(tx_id()) -> {ok, tx_state()} | none.
lookup(TxId) ->
    gen_server:call(?MODULE, {lookup, TxId}).

%% Unfinished transactions, for recovery.
-spec pending() -> [{tx_id(), tx_state()}].
pending() ->
    gen_server:call(?MODULE, pending).

%% Replaces the log with a replicated copy (state transfer to a new coordinator).
-spec install(#{tx_id() => tx_state()}) -> ok.
install(Txs) ->
    gen_server:call(?MODULE, {install, Txs}).

%% ------------------------------------------------------------------ gen_server

init(Opts) ->
    {ok, #{txs => #{}, replicate => maps:get(replicate, Opts, fun(_) -> ok end)}}.

handle_call({append, Entry}, _From, S = #{txs := Txs, replicate := Replicate}) ->
    case Replicate(Entry) of
        ok -> {reply, ok, S#{txs := apply_entry(Entry, Txs)}};
        {error, _} = Error -> {reply, Error, S}
    end;
handle_call({lookup, TxId}, _From, S = #{txs := Txs}) ->
    Reply = case maps:find(TxId, Txs) of
                {ok, State} -> {ok, State};
                error -> none
            end,
    {reply, Reply, S};
handle_call(pending, _From, S = #{txs := Txs}) ->
    {reply, maps:to_list(Txs), S};
handle_call({install, Txs}, _From, S) ->
    {reply, ok, S#{txs := Txs}}.

handle_cast(_Msg, S) ->
    {noreply, S}.

apply_entry({'begin', TxId, Participants}, Txs) ->
    Txs#{TxId => {'begin', Participants}};
apply_entry({commit, TxId}, Txs) ->
    {_, Participants} = maps:get(TxId, Txs, {'begin', []}),
    Txs#{TxId => {commit, Participants}};
apply_entry({done, TxId}, Txs) ->
    maps:remove(TxId, Txs).
