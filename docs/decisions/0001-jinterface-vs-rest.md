# ADR 0001: JInterface, not REST, between the web tier and Erlang

- **Status:** proposed by Roger, 20 Nov 2026. Confirm with Macarena by Sun 22 Nov (week 1 gate).
- **Context:** the proposal (§5) plans JInterface and keeps a REST interface on the Erlang
  nodes as the fallback "if JInterface proves impractical". The roadmap says to switch by
  22 Nov at the latest if needed.

## Decision

Keep **JInterface**. The web tier runs as a hidden Erlang node (`fairslot_web`) and
exchanges the terms of [`docs/message-contract.md`](../message-contract.md) with the
process `fairslot_gateway` on each back-end node.

## Evidence (proof of concept, branch `web/jinterface-poc`)

`web/src/test/java/.../JInterfaceStubIntegrationTest.java` starts a real Erlang/OTP 29.1.1
node running `tools/stub-backend/fairslot_stub.erl` and drives it from Java 17 through
JInterface 1.16. Run on 20 Nov 2026 on Roger's laptop (Windows 11), all on one machine:

| Check | Result |
|---|---|
| Java appears in `nodes(hidden)` and not in `nodes()` | pass: it cannot disturb failure detection or leader election |
| Hold and confirm a slot through Erlang | pass |
| 40 concurrent holds on one slot from 20 Java threads | 1 succeeds, 39 get `slot_taken` |
| Resend with the same request ID | same result, not applied twice |
| Event pushed from Erlang (`hold_expired`) to the Java `events` mailbox | received |
| Round-trip latency, `availability` (13 slots), 200 sequential calls | **0.26 ms** average |
| Browser → servlet → JInterface → Erlang books one slot (week 1 gate) | pass, by hand in the browser |

## Why not REST on the Erlang nodes

- JInterface gives us push for free: the back-end sends events with `!` to a registered
  Java mailbox. With REST we would need a second channel (long polling or webhooks
  back to Tomcat) for offers and expiries.
- Erlang terms map directly to the contract; REST would add an HTTP server (cowboy or
  `inets`) and JSON encoding on every node, i.e. more code on Macarena's side.
- Request IDs, epochs and resends work the same way over either transport, so REST
  would not simplify failover.

## Consequences and things to remember

- **EPMD must be running before the web tier starts.** `OtpNode` registers with EPMD in
  its constructor and fails with "Nameserver not responding" otherwise. On the web VM:
  run `epmd -daemon` (from an Erlang install) before starting Tomcat.
- The JInterface jar must match the OTP release of the back-end. Maven Central only has
  OTP 17 builds; `tools/install-jinterface.sh` builds 1.16 from the OTP 29.1.1 sources.
- All nodes share one cookie (`FAIRSLOT_COOKIE`), never committed. Node names must use
  the same style everywhere: `-sname` locally, `-name` with IPs on the cloud VMs, and
  `FAIRSLOT_WEB_NODE` / `FAIRSLOT_ERLANG_NODES` set to match.
- On the VMs the firewall must allow EPMD (TCP 4369) and the distribution port range;
  pin it with `-kernel inet_dist_listen_min/max` on the Erlang nodes. The roadmap
  already plans a test VM in week 2 to find these problems early.
