# FairSlot back-end (Erlang)

Standard rebar3 layout: modules in `src/`, EUnit tests in `test/`.

```sh
cd backend
rebar3 compile
rebar3 eunit        # includes tests that start extra Erlang nodes with OTP's `peer`
```

Get rebar3 (3.28+ for OTP 29) from <https://github.com/erlang/rebar3/releases>; on Windows,
run it as `escript rebar3 ...` or through a one-line `rebar3.cmd` wrapper.

## Who owns what

| Module | Owner | What |
|---|---|---|
| `fairslot_coordinator` | Roger | 2PC coordinator for bundle bookings ([protocol](../docs/2pc-protocol.md)) |
| `fairslot_decision_log` | Roger | Coordinator decision log (in memory now; replicated in week 3) |
| everything else | Macarena | Resource processes, holds, quotas, waitlist, timers, replication, 2PC participant |

The web tier talks to this back-end only through the messages in
[`docs/message-contract.md`](../docs/message-contract.md). Each node must register a
process under the name given there (`fairslot_gateway`) to receive requests from the web
tier; the same gateway receives the `fs_2pc` messages of [`docs/2pc-protocol.md`](../docs/2pc-protocol.md).

`test/fake_participant.erl` is a test double of the participant side, so the coordinator
can be tested before the real participant exists.
