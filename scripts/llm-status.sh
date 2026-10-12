#!/usr/bin/env bash
#
# Report the status of the local LLM runtime (Ollama) used by the app's advisory
# analyst layer (app.llm.* / GET /api/llm/analyze).
#
#   ./scripts/llm-status.sh
#
# Shows: whether the server is reachable, its version, the recorded PID, the
# installed models, which models are currently loaded in memory (size, quant,
# context, unload time), and the model/enabled flag the app is configured to use.
# Read-only — starts/stops nothing. Exit code: 0 if the server is up, 1 if down.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PID_FILE="$ROOT/.run/ollama.pid"
LOG_FILE="$ROOT/logs/ollama.out"
YML="$ROOT/src/main/resources/application.yml"

HOST="${OLLAMA_HOST:-127.0.0.1:11434}"
BASE_URL="http://${HOST}"

have_jq=0
command -v jq >/dev/null 2>&1 && have_jq=1

# Reports one model's readiness: installed (on disk, from TAGS_JSON) and loaded
# (in memory now, from PS_JSON). Both globals are populated before this is called.
report_model() {
  local m="$1"
  [[ -z "$m" ]] && return 0
  local installed="no" loaded="no"
  if [[ "$have_jq" -eq 1 ]]; then
    echo "${TAGS_JSON:-}" | jq -e --arg m "$m" '.models[]?|select(.name==$m)' >/dev/null 2>&1 && installed="yes"
    echo "${PS_JSON:-}"   | jq -e --arg m "$m" '.models[]?|select(.name==$m)' >/dev/null 2>&1 && loaded="yes"
  else
    ollama list 2>/dev/null | awk '{print $1}' | grep -qx "$m" && installed="yes"
    ollama ps   2>/dev/null | awk 'NR>1{print $1}' | grep -qx "$m" && loaded="yes"
  fi

  if [[ "$installed" == "yes" && "$loaded" == "yes" ]]; then
    echo "    • $m — installed ✅, loaded in memory ✅ (warm)"
  elif [[ "$installed" == "yes" ]]; then
    echo "    • $m — installed ✅, not loaded (cold; loads on first request)"
  else
    echo "    • $m — ⚠️  NOT installed — pull it: ollama pull $m"
  fi
}

echo "── Local LLM status ─────────────────────────────────────────────"
echo "Endpoint : $BASE_URL"

# ── Server reachable? ────────────────────────────────────────────────────────
if ! curl -fsS "$BASE_URL/api/version" >/dev/null 2>&1; then
  echo "Server   : ❌ DOWN (not answering at $BASE_URL)"
  if [[ -f "$PID_FILE" ]]; then
    echo "PID file : $PID_FILE (PID $(cat "$PID_FILE") — stale, process not answering)"
  fi
  echo ""
  echo "Start it with: ./scripts/llm-start.sh"
  exit 1
fi

VERSION_JSON="$(curl -fsS "$BASE_URL/api/version" 2>/dev/null || echo '{}')"
if [[ "$have_jq" -eq 1 ]]; then
  VERSION="$(echo "$VERSION_JSON" | jq -r '.version // "?"')"
else
  VERSION="$VERSION_JSON"
fi
echo "Server   : ✅ UP (version $VERSION)"

# ── PID (only meaningful if we started it via llm-start.sh) ──────────────────
if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    echo "PID      : $PID (from llm-start.sh)"
  else
    echo "PID      : $PID in $PID_FILE is stale (server running from elsewhere)"
  fi
else
  echo "PID      : (no PID file — server not started by llm-start.sh)"
fi
echo "Log      : $LOG_FILE"

# ── Installed models ─────────────────────────────────────────────────────────
TAGS_JSON="$(curl -fsS "$BASE_URL/api/tags" 2>/dev/null || echo '{}')"
echo ""
echo "── Installed models ─────────────────────────────────────────────"
if [[ "$have_jq" -eq 1 ]]; then
  COUNT="$(echo "$TAGS_JSON" | jq '.models | length')"
  if [[ "$COUNT" == "0" ]]; then
    echo "(none pulled — run: ollama pull <model>)"
  else
    echo "$TAGS_JSON" | jq -r '.models[] |
      "  \(.name)  —  \(.details.parameter_size // "?"), \(.details.quantization_level // "?"), \(((.size // 0) / 1073741824) | (.*10 | round / 10))GB"'
  fi
else
  echo "$TAGS_JSON"
fi

# ── Loaded in memory (what will answer instantly) ────────────────────────────
PS_JSON="$(curl -fsS "$BASE_URL/api/ps" 2>/dev/null || echo '{}')"
echo ""
echo "── Loaded in memory ─────────────────────────────────────────────"
if [[ "$have_jq" -eq 1 ]]; then
  LOADED="$(echo "$PS_JSON" | jq '.models | length')"
  if [[ "$LOADED" == "0" ]]; then
    echo "(none loaded — first request will cold-load the model)"
  else
    echo "$PS_JSON" | jq -r '.models[] |
      "  \(.name)  —  \(((.size_vram // .size // 0) / 1073741824) | (.*10 | round / 10))GB VRAM, ctx \(.context_length // "?"), unloads \(.expires_at // "?")"'
  fi
else
  echo "$PS_JSON"
fi

# ── What the APP is configured to use ────────────────────────────────────────
echo ""
echo "── App config (application.yml app.llm) ─────────────────────────"
if [[ -f "$YML" ]]; then
  # Pull the values from the `llm:` and `alert-review:` blocks under `app:`. Simple
  # grep/awk — the keys are unique enough within the file for a read-only status view.
  LLM_ENABLED="$(awk '/^  llm:/{f=1} f&&/^    enabled:/{print $2; exit}' "$YML" 2>/dev/null || true)"
  LLM_MODEL="$(awk '/^  llm:/{f=1} f&&/^    model:/{gsub(/"/,"",$2); print $2; exit}' "$YML" 2>/dev/null || true)"
  LLM_BASE="$(awk '/^  llm:/{f=1} f&&/^    base-url:/{gsub(/"/,"",$2); print $2; exit}' "$YML" 2>/dev/null || true)"
  REVIEW_ENABLED="$(awk '/^  alert-review:/{f=1} f&&/^    enabled:/{print $2; exit}' "$YML" 2>/dev/null || true)"
  REVIEW_MODEL="$(awk '/^  alert-review:/{f=1} f&&/^    model:/{gsub(/"/,"",$2); print $2; exit}' "$YML" 2>/dev/null || true)"
  # alert-review.model may be blank → the app falls back to app.llm.model.
  REVIEW_EFFECTIVE="${REVIEW_MODEL:-$LLM_MODEL}"

  echo "  base-url            : ${LLM_BASE:-?}"
  echo "  live model (app.llm): ${LLM_MODEL:-?}   (enabled=${LLM_ENABLED:-?})   — /api/llm/analyze, /evaluate"
  echo "  review model (app.alert-review): ${REVIEW_EFFECTIVE:-?}   (enabled=${REVIEW_ENABLED:-?})   — alert review, /api/llm/review"
  if [[ -z "${REVIEW_MODEL:-}" ]]; then
    echo "       (alert-review.model is blank → falls back to the live model)"
  fi

  # Per-model readiness: installed (on disk) and loaded (in memory right now).
  echo "  model readiness:"
  report_model "$LLM_MODEL"
  if [[ "$REVIEW_EFFECTIVE" != "$LLM_MODEL" ]]; then
    report_model "$REVIEW_EFFECTIVE"
  fi
else
  echo "(application.yml not found at $YML)"
fi

echo ""
echo "✅ LLM runtime is up."
