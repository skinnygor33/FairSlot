# Bundle bookings: two-phase commit between Erlang nodes

> **Status: DRAFT v0.1** — proposed by Roger on 23 Nov 2026 for review by Macarena.
> Roger implements the coordinator (`backend/src/fairslot_coordinator.erl`), Macarena the
> participant side inside the resource owners. Both code against this file.

A bundle (e.g. a group room and a projector) is booked all-or-nothing, even when its
resources are owned by different nodes (proposal §4, "Atomic bundle bookings"). The
coordinator runs a two-phase commit with **presumed abort**.

## 1. Who takes part

- **Coordinator:** the process `fairslot_coordinator` on the elected coordinator node.
- **Participants:** one per node that owns at least one item of the bundle, plus the node
  that owns the user's **quota shard**, which takes part with the item `{quota, Hours}`.
  This keeps quota and slots in one atomic decision.
- Participants are addressed as `{fairslot_gateway, Node}`, the same entry point the web
  tier uses; the gateway hands `fs_2pc` messages to the right resource processes.

## 2. Entry point

The gateway receives the web tier's `{fs_req, ReqId, Epoch, From, User, {book_bundle, Items}}`
(message contract §3) and calls

```erlang
fairslot_coordinator:book_bundle(ReqId, User, Items, From)   %% returns ok immediately
```

on the coordinator node. The coordinator answers the web tier itself with
`From ! {fs_reply, ReqId, Result}`. `ReqId` is also the transaction ID (`TxId`): a resent
request with the same ID joins the running transaction or gets its stored result.

## 3. Messages

All messages carry the coordinator's `Epoch`; participants reject older epochs (fencing).

```erlang
%% Phase 1: coordinator -> each participant
{fs_2pc, prepare, Epoch, TxId, Coord, User, Items}
%%   Coord :: pid()   the transaction process; votes and acks go there
%%   Items :: [{ResId, SlotStart}] owned by this participant, and/or {quota, Hours}

%% participant -> Coord;  Me = the address the prepare was sent to, i.e. {fairslot_gateway, node()}
{fs_2pc, vote, TxId, Me, yes}
{fs_2pc, vote, TxId, Me, {no, Reason}}        %% Reason as in the contract: {slot_taken, ResId}, quota_exceeded, stale_epoch...

%% Phase 2: coordinator -> each participant
{fs_2pc, commit, Epoch, TxId, Coord}
{fs_2pc, abort,  Epoch, TxId, Coord}

%% participant -> Coord (after the decision is applied AND replicated to its backup)
{fs_2pc, ack, TxId, Me, BookingIds}           %% BookingIds :: #{ResId => binary()}, #{} on abort

%% Recovery: participant -> {fairslot_coordinator, CoordinatorNode}, when it holds a prepared tx too long
{fs_2pc, query, Epoch, TxId, From}            %% From :: pid()
%% coordinator -> From
{fs_2pc, decision, TxId, commit | abort}
```

## 4. Rules

| Side | Rule |
|---|---|
| Participant, on `prepare` | Place a **prepared hold** on every item (and reserve the hours for `{quota, H}`), replicate it to the backup, then vote `yes`. If any item cannot be held, hold nothing and vote `{no, Reason}`. |
| Participant, prepared | A prepared hold **does not expire** on its own. It stays until `commit` or `abort` arrives (proposal §4). |
| Participant, on `commit` / `abort` | Turn the prepared holds into bookings (or release them), replicate, then `ack`. Both are idempotent: a repeated message gets the same `ack`. |
| Participant, waiting long | After 10 s without a decision, send `query` to the current coordinator, and repeat. |
| Coordinator | Writes `{begin, TxId, Participants}` to the decision log before sending `prepare`, and `{commit, TxId}` **before** sending `commit`. |
| Coordinator | Any `no`, or a missing vote after 5 s, means abort. Aborts are not logged (presumed abort). |
| Coordinator | Resends the decision every second until every participant has acked. |
| New coordinator (after election) | For each logged transaction: `commit` logged → resend `commit`; only `begin` → send `abort`. A `query` for a transaction with no `commit` record is answered `abort`. |

## 5. Result to the web tier

| Outcome | `Result` in `{fs_reply, ReqId, Result}` |
|---|---|
| Committed | `{ok, #{bundle_id => TxId, booking_ids => [binary()]}}` (one per item, same order as the request) |
| A participant voted no | `{error, Reason}` from that vote, e.g. `{error, {slot_taken, ResId}}` or `{error, quota_exceeded}` |
| A participant did not vote in time | `{error, {participant_unavailable, Node}}` |

## 6. Open questions

- [ ] Does the gateway dispatch `fs_2pc` messages, or should participants register their own name (e.g. `fairslot_2pc`)? The draft assumes the gateway.
- [ ] Bundles inside one node still go through the coordinator (simpler, one code path). OK?
- [ ] Vote timeout 5 s, decision resend 1 s, participant query after 10 s: confirm in the spec.
