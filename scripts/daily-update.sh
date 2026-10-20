#!/usr/bin/env bash
# Dedicated deployment checkout only. Run as its owner, not root.
set -euo pipefail
# Keep the Mac awake through build and restart, including when the server is stopped.
if [[ "$(uname -s)" == Darwin && "${DEPLOY_AWAKE_GUARD:-}" != 1 ]]; then
  exec /usr/bin/caffeinate -i /usr/bin/env DEPLOY_AWAKE_GUARD=1 /bin/bash "$0" "$@"
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REMOTE="${DEPLOY_REMOTE:-origin}"
BRANCH="${DEPLOY_BRANCH:-$(cat .run/deploy-branch 2>/dev/null || echo master)}"
SERVICE="${DEPLOY_SERVICE:-tradingautomation.service}"
HEALTH_URL="${DEPLOY_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
export GIT_TERMINAL_PROMPT=0
if [[ "$(uname -s)" == Darwin ]]; then
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
  export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
  DOMAIN="gui/$(id -u)"
  PLIST="$HOME/Library/LaunchAgents/com.tradingautomation.server.plist"
  stop_service() {
    if launchctl print "$DOMAIN/com.tradingautomation.server" >/dev/null 2>&1; then
      launchctl bootout "$DOMAIN" "$PLIST"
      sleep 5
    fi
  }
  start_service() { launchctl bootstrap "$DOMAIN" "$PLIST"; }
else
  stop_service() { sudo -n systemctl stop "$SERVICE"; }
  start_service() { sudo -n systemctl start "$SERVICE"; }
fi
mkdir -p .run logs
LOCK_DIR="$ROOT/.run/daily-update.lockdir"
mkdir "$LOCK_DIR" 2>/dev/null || { echo 'Another update is running; skipping.'; exit 0; }
trap 'rmdir "$LOCK_DIR"' EXIT
exec >>logs/daily-update.log 2>&1
trap 'echo "Update failed at $(date +%Y-%m-%dT%H:%M:%S%z), line $LINENO"' ERR
echo "Update started: $(date +%Y-%m-%dT%H:%M:%S%z)"
[[ "$(git branch --show-current)" == "$BRANCH" ]] || { echo 'Wrong deployment branch'; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo 'Checkout has local changes; refusing to overwrite them'; exit 1; }
# No force reset, stash, or deletion of local configuration.
git fetch "$REMOTE" "$BRANCH"
git merge --ff-only FETCH_HEAD
[[ "$(git rev-parse HEAD)" == "$(git rev-parse FETCH_HEAD)" ]] || { echo 'Local branch has unpushed commits'; exit 1; }
# The running service uses .run/server.jar, independent of clean's build directory.
bash ./gradlew --no-daemon clean build
jars=(build/libs/*.jar)
boot_jars=()
for jar in "${jars[@]}"; do
  [[ "$jar" == *-plain.jar ]] || boot_jars+=("$jar")
done
[[ ${#boot_jars[@]} -eq 1 && -f "${boot_jars[0]}" ]] || { echo 'Expected one executable JAR'; exit 1; }
cp "${boot_jars[0]}" .run/server.jar.next
had_previous=false
if [[ -f .run/server.jar ]]; then
  cp .run/server.jar .run/server.jar.previous
  had_previous=true
fi
stop_service
mv .run/server.jar.next .run/server.jar
healthy() {
  local attempt
  for attempt in {1..90}; do
    if curl --fail --silent --max-time 2 "$HEALTH_URL" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}
if start_service && healthy; then
  git rev-parse HEAD >.run/deployed-revision
  echo "Deployment healthy: $(date +%Y-%m-%dT%H:%M:%S%z)"
else
  echo 'Startup failed; restoring previous JAR if available'
  stop_service
  if "$had_previous"; then
    cp .run/server.jar.previous .run/server.jar
    start_service
    healthy || echo 'Rollback unhealthy; inspect journalctl'
  fi
  exit 1
fi
