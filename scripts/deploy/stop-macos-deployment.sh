#!/bin/bash
# Disable scheduled deployment and stop this user's managed server/update jobs.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'This script requires macOS.'; exit 1; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOMAIN="gui/$(id -u)"
# Disable persists across login. Do this first so an in-flight updater cannot restart it.
for label in com.tradingautomation.update com.tradingautomation.server; do
  launchctl disable "$DOMAIN/$label"
done
# Stop updates before the server so no update can start the server afterward.
for label in com.tradingautomation.update com.tradingautomation.server; do
  if launchctl print "$DOMAIN/$label" >/dev/null 2>&1; then
    launchctl bootout "$DOMAIN/$label"
  fi
done
# New manual updater runs record their PID inside their exclusive lock directory.
PID_FILE="$ROOT/.run/daily-update.lockdir/pid"
stop_tree() {
  local pid="$1" child
  kill -STOP "$pid" 2>/dev/null || return 0
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    stop_tree "$child"
  done
  kill -TERM "$pid" 2>/dev/null || true
  kill -CONT "$pid" 2>/dev/null || true
}
if [[ -f "$PID_FILE" ]]; then
  pid="$(cat "$PID_FILE")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && [[ "$pid" -gt 1 ]]; then
    owner="$(ps -p "$pid" -o uid= | tr -d ' ' || true)"
    command="$(ps -p "$pid" -o command= || true)"
    if [[ "$owner" == "$(id -u)" && "$command" == *daily-update.sh* ]]; then
      stop_tree "$pid"
    fi
  fi
fi
echo 'Daily updates and managed server stopped and disabled, including at next login.'
echo 'To resume: bash scripts/deploy/install-macos-schedule.sh && bash scripts/deploy/daily-update.sh'
