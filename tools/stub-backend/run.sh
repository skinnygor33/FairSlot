#!/usr/bin/env bash
# Starts the throwaway Erlang stub as node fairslot_a@<host> for web-tier development.
# Then run the web tier against it:
#   cd web && FAIRSLOT_BACKEND=jinterface ./mvnw jetty:run
# Cookie: $FAIRSLOT_COOKIE (default fairslot-dev, same default as the web tier).
set -euo pipefail
cd "$(dirname "$0")"
OUT=$(mktemp -d)
erlc -o "$OUT" fairslot_stub.erl
exec erl -sname "${NODE:-fairslot_a}" -setcookie "${FAIRSLOT_COOKIE:-fairslot-dev}" -noshell \
    -pa "$OUT" -eval "fairslot_stub:start()"
