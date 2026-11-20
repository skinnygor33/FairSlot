# FairSlot

Distributed booking system for university study rooms and lab equipment.
DSMT project (Prof. Bechini) — Roger Hostalot, Macarena Arriaga.

A Java web tier (servlets on Tomcat) serves the browser UI and a REST API, and talks over
JInterface to an Erlang back-end of three nodes that owns all booking state: holds, weekly
quotas, waitlists with timed offers, check-in / no-show release and all-or-nothing bundle
bookings, with primary-backup replication and failover.

## Repository layout

| Path | What | Owner |
|---|---|---|
| `backend/` | Erlang back-end (rebar3 app) | Macarena |
| `web/` | Java web tier: servlets, REST API, WebSocket, UI, SQL (Maven WAR) | Roger |
| `docs/` | Spec, [message contract](docs/message-contract.md), design decisions | shared |
| `tools/` | Developer utilities (JInterface installer, ...) | shared |

The only coupling between `web/` and `backend/` is
[`docs/message-contract.md`](docs/message-contract.md). Change it together.

## Prerequisites

- JDK 17 or newer, Erlang/OTP 29, Git (Git Bash on Windows)
- No Maven install needed: `web/mvnw` downloads Maven 3.9.16 on first use
- JInterface is not on Maven Central for OTP 29. Build and install it once:

  ```sh
  tools/install-jinterface.sh      # needs mvn on PATH, or MVN=/path/to/mvn
  ```

## Build and run

```sh
cd web
./mvnw verify          # compile + unit tests, builds target/fairslot.war
./mvnw jetty:run       # dev server on http://localhost:8080/
```

```sh
cd backend
rebar3 compile && rebar3 eunit
```

## Working together

- **Branches.** `main` always builds and passes tests. Work on short-lived branches named
  `web/<topic>` or `backend/<topic>` and merge into `main` at the Thursday integration
  session (or earlier, once the other person has had a look).
- **Commits.** Prefix every message with your name: `ROGER: ...`, `MACARENA: ...`.
- **Weekly rhythm.** Mon sync (agree interface changes), Thu integration (run the demo
  scripts end to end), Sun gate check.
