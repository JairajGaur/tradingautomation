#!/usr/bin/env bash
#
# Stop the trading-automation app started by scripts/start.sh.
#
#   ./scripts/stop.sh
#
# Stops the process recorded in ./.run/app.pid; falls back to whatever is bound
# to the server port if the PID file is missing/stale.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PID_FILE="$ROOT/.run/app.pid"
PORT="${SERVER_PORT:-8080}"

stopped=0

# ── 1. Stop via the recorded PID (and its child gradle/java processes) ───────
if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping PID $PID (and children)..."
    # Kill the whole process group so the gradle wrapper + java child both die.
    pkill -TERM -P "$PID" 2>/dev/null || true
    kill -TERM "$PID" 2>/dev/null || true
    stopped=1
  else
    echo "PID file present but process $PID not running (stale)."
  fi
  rm -f "$PID_FILE"
fi

# ── 2. Fallback: anything still bound to the port (the java bootRun child) ───
if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
  PORT_PIDS="$(lsof -ti tcp:$PORT | tr '\n' ' ')"
  echo "Stopping process(es) on port $PORT: $PORT_PIDS"
  # shellcheck disable=SC2086
  kill -TERM $PORT_PIDS 2>/dev/null || true
  stopped=1
fi

# ── 3. Give it a moment, then force-kill the port if still up ────────────────
if [[ "$stopped" -eq 1 ]]; then
  sleep 3
  if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
    echo "Still up — force-killing port $PORT."
    # shellcheck disable=SC2046
    kill -9 $(lsof -ti tcp:$PORT) 2>/dev/null || true
  fi
  echo "✅ Stopped."
else
  echo "Nothing running (no PID file, nothing on port $PORT)."
fi
