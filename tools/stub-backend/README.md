# Erlang stub back-end (throwaway)

A single Erlang process that speaks [`docs/message-contract.md`](../../docs/message-contract.md)
well enough to develop and test the web tier against a real Erlang node before the real
back-end (`backend/`) is ready. It is **not** the real back-end: no partitions, replication,
waitlists or weekly reset. It supports `hold`, `confirm`, `release`, `cancel`,
`availability`, `quota` and `partition_map`, detects duplicate request IDs and pushes
`hold_expired` / `slot_freed` events.

```sh
tools/stub-backend/run.sh                         # node fairslot_a@<host>, cookie fairslot-dev
cd web && FAIRSLOT_BACKEND=jinterface ./mvnw jetty:run
```

Delete it once the real back-end can run a single node.
