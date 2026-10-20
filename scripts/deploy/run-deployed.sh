#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a
source "$ROOT/.env"
set +a
: "${WEBULL_APP_KEY:?Set WEBULL_APP_KEY in .env}"
: "${WEBULL_APP_SECRET:?Set WEBULL_APP_SECRET in .env}"
# Hold an idle-sleep assertion for the lifetime of the server; display may sleep.
exec /usr/bin/caffeinate -i "$JAVA_HOME/bin/java" -jar "$ROOT/.run/server.jar"
