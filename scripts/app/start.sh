#!/usr/bin/env bash
#
# Start the trading-automation app in the background with env vars from .env.
#
#   ./scripts/app/start.sh
#
# Reads ./.env (copy from .env.example first). Writes a PID to ./.run/app.pid and
# logs to ./logs/app.out. Use ./scripts/app/stop.sh to stop it.

set -euo pipefail

# Resolve project root (this script lives two directories below the project root: scripts/).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="$ROOT/.env"
RUN_DIR="$ROOT/.run"
PID_FILE="$RUN_DIR/app.pid"
LOG_FILE="$ROOT/logs/app.out"
PORT="${SERVER_PORT:-8080}"

# ── Load .env ────────────────────────────────────────────────────────────────
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Copy the template first:"
  echo "         cp .env.example .env   # then fill in your values"
  exit 1
fi

# Export every non-comment, non-blank KEY=VALUE line from .env.
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# ── Validate required vars ───────────────────────────────────────────────────
missing=()
[[ -z "${WEBULL_APP_KEY:-}" ]]    && missing+=("WEBULL_APP_KEY")
[[ -z "${WEBULL_APP_SECRET:-}" ]] && missing+=("WEBULL_APP_SECRET")
if (( ${#missing[@]} > 0 )); then
  echo "ERROR: required env var(s) not set in .env: ${missing[*]}"
  exit 1
fi

# ── Already running? ─────────────────────────────────────────────────────────
mkdir -p "$RUN_DIR" "$ROOT/logs"
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Already running (PID $(cat "$PID_FILE")). Use ./scripts/app/stop.sh first."
  exit 1
fi
if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
  echo "ERROR: port $PORT is already in use by PID(s): $(lsof -ti tcp:$PORT | tr '\n' ' ')"
  echo "       Stop that process (or ./scripts/app/stop.sh) before starting."
  exit 1
fi

# ── Start in the background ──────────────────────────────────────────────────
echo "Starting trading-automation (mode is set in application.yml app.engine.mode)..."
echo "  logs -> $LOG_FILE"
nohup ./gradlew bootRun > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "Started with PID $(cat "$PID_FILE"). Tailing startup (Ctrl-C to stop tailing; app keeps running)..."

# Tail until we see the started banner or a failure, then detach.
( tail -n +1 -f "$LOG_FILE" & TAIL_PID=$!
  while kill -0 "$TAIL_PID" 2>/dev/null; do
    if grep -q "Started TradingAutomationApplication" "$LOG_FILE" 2>/dev/null; then
      echo ""; echo "✅ App started on port $PORT."; kill "$TAIL_PID" 2>/dev/null; break
    fi
    if grep -qE "APPLICATION FAILED TO START|BUILD FAILED" "$LOG_FILE" 2>/dev/null; then
      echo ""; echo "❌ Startup failed — see $LOG_FILE"; kill "$TAIL_PID" 2>/dev/null; break
    fi
    sleep 1
  done ) || true
