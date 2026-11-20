# FairSlot back-end (Erlang)

Owner: **Macarena**. Standard rebar3 layout: modules in `src/`, EUnit tests in `test/`.

```sh
cd backend
rebar3 compile
rebar3 eunit
```

The web tier talks to this back-end only through the messages in
[`docs/message-contract.md`](../docs/message-contract.md). Each node must register a
process under the name given there (`fairslot_gateway`) to receive requests from the web tier.
