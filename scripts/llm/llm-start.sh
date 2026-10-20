#!/usr/bin/env bash
#
# Start the local LLM runtime (Ollama) used by the app's advisory analyst layer
# (app.llm.* / GET /api/llm/analyze). Safe to run repeatedly — it reuses a server
# that is already up and only pulls the model if it is missing.
#
#   ./scripts/llm/llm-start.sh                 # defaults to the model in application.yml
#   LLM_MODEL=qwen3:14b ./scripts/llm/llm-start.sh
#
# Writes a PID to ./.run/ollama.pid and logs to ./logs/ollama.out. Stop it with
# ./scripts/llm/llm-stop.sh. This is INDEPENDENT of the trading app — bring the LLM
# up/down without touching start.sh/stop.sh.

set -euo pipefail

# Resolve project root (this script lives two directories below the project root: scripts/).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN_DIR="$ROOT/.run"
PID_FILE="$RUN_DIR/ollama.pid"
LOG_FILE="$ROOT/logs/ollama.out"

# Ollama host/port the app talks to (matches app.llm.base-url default).
HOST="${OLLAMA_HOST:-127.0.0.1:11434}"
BASE_URL="http://${HOST}"

# Model to ensure is available. Defaults to the app.llm.model in application.yml;
# override with LLM_MODEL=... for the deeper-analyst model.
DEFAULT_MODEL="gemma3:27b"
MODEL="${LLM_MODEL:-$DEFAULT_MODEL}"

# Performance env (same flags Homebrew suggests): flash attention + quantised KV
# cache for lower memory / faster decode on Apple Silicon.
export OLLAMA_FLASH_ATTENTION="${OLLAMA_FLASH_ATTENTION:-1}"
export OLLAMA_KV_CACHE_TYPE="${OLLAMA_KV_CACHE_TYPE:-q8_0}"
export OLLAMA_HOST="$HOST"

# ── Preconditions ────────────────────────────────────────────────────────────
if ! command -v ollama >/dev/null 2>&1; then
  echo "ERROR: 'ollama' not found. Install it first:"
  echo "         brew install ollama"
  exit 1
fi

mkdir -p "$RUN_DIR" "$ROOT/logs"

# ── Start the server if it isn't already answering ───────────────────────────
if curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then
  echo "Ollama already running at $BASE_URL — reusing it."
else
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Ollama PID $(cat "$PID_FILE") is up but not answering yet — waiting..."
  else
    echo "Starting Ollama server (host=$HOST)..."
    echo "  logs -> $LOG_FILE"
    nohup ollama serve > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    echo "Started with PID $(cat "$PID_FILE")."
  fi

  # Wait up to ~30s for the HTTP API to come up.
  for _ in $(seq 1 30); do
    if curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  if ! curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then
    echo "❌ Ollama did not become ready at $BASE_URL — see $LOG_FILE"
    exit 1
  fi
fi

VERSION="$(curl -fsS "$BASE_URL/api/version" 2>/dev/null || echo '{}')"
echo "✅ Ollama up at $BASE_URL ($VERSION)"

# ── Ensure the model is present (pull once if missing) ───────────────────────
if ollama list 2>/dev/null | awk '{print $1}' | grep -qx "$MODEL"; then
  echo "✅ Model '$MODEL' already pulled."
else
  echo "Pulling model '$MODEL' (first time only; this may take a few minutes)..."
  ollama pull "$MODEL"
  echo "✅ Model '$MODEL' ready."
fi

echo ""
echo "LLM ready. The app's analyst (app.llm.enabled=true) will use '$MODEL'."
echo "Try it:  curl \"$BASE_URL/api/version\""
echo "Stop it: ./scripts/llm/llm-stop.sh"
