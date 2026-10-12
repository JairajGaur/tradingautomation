#!/usr/bin/env bash
#
# Stop the local LLM runtime (Ollama) started by scripts/llm-start.sh — or just
# unload a single model from memory while leaving the server (and other models) up.
#
#   ./scripts/llm-stop.sh                 # unload all loaded models, then stop the server
#   ./scripts/llm-stop.sh qwen3:14b       # unload ONLY that model; server stays running
#
# With no argument: stops the process recorded in ./.run/ollama.pid, falling back
# to any 'ollama serve' process if the PID file is missing/stale.
# With a model argument: runs `ollama stop <model>` to evict just that model from
# RAM/VRAM (frees its memory) and leaves the server + any other loaded models alone.
# Independent of the trading app — this never stops start.sh/bootRun.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PID_FILE="$ROOT/.run/ollama.pid"
HOST="${OLLAMA_HOST:-127.0.0.1:11434}"
BASE_URL="http://${HOST}"
export OLLAMA_HOST="$HOST"

# Optional first arg = a single model to unload (e.g. qwen3:14b).
MODEL="${1:-}"

# ── Mode A: unload ONE model, leave the server + other models running ────────
if [[ -n "$MODEL" ]]; then
  if ! curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then
    echo "Ollama is not running at $BASE_URL — nothing to unload."
    exit 0
  fi
  echo "Unloading model '$MODEL' from memory (server stays up)..."
  if ollama stop "$MODEL" 2>/dev/null; then
    echo "✅ Unloaded '$MODEL'. Loaded models now:"
    ollama ps 2>/dev/null || true
  else
    echo "⚠️  'ollama stop $MODEL' failed (it may not have been loaded). Loaded models now:"
    ollama ps 2>/dev/null || true
  fi
  exit 0
fi

# ── Mode B: full stop (no model arg) — unload everything, then stop the server ─
# Unload any models currently in memory first, so they release RAM/VRAM cleanly
# before the server goes down (includes the 14b review model if it was loaded).
if curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then
  loaded="$(ollama ps 2>/dev/null | awk 'NR>1 {print $1}')"
  if [[ -n "$loaded" ]]; then
    echo "Unloading loaded model(s) before shutdown: $(echo "$loaded" | tr '\n' ' ')"
    while IFS= read -r m; do
      [[ -z "$m" ]] && continue
      ollama stop "$m" 2>/dev/null || true
    done <<< "$loaded"
  fi
fi

stopped=0

# ── 1. Stop via the recorded PID ─────────────────────────────────────────────
if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping Ollama PID $PID (and children)..."
    pkill -TERM -P "$PID" 2>/dev/null || true
    kill -TERM "$PID" 2>/dev/null || true
    stopped=1
  else
    echo "PID file present but process $PID not running (stale)."
  fi
  rm -f "$PID_FILE"
fi

# ── 2. Fallback: any lingering 'ollama serve' we started ─────────────────────
if pgrep -f "ollama serve" >/dev/null 2>&1; then
  echo "Stopping lingering 'ollama serve' process(es): $(pgrep -f 'ollama serve' | tr '\n' ' ')"
  pkill -TERM -f "ollama serve" 2>/dev/null || true
  stopped=1
fi

# ── 3. Give it a moment, then force-kill if still answering ──────────────────
if [[ "$stopped" -eq 1 ]]; then
  sleep 2
  if curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then
    echo "Still answering at $BASE_URL — force-killing 'ollama serve'."
    pkill -9 -f "ollama serve" 2>/dev/null || true
  fi
  echo "✅ Stopped."
else
  echo "Nothing to stop (no PID file, and no 'ollama serve' process found)."
fi
