#!/bin/bash
# Run once from a dedicated clean clone on the deployment Mac, as the login user.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$ROOT" in *'&'*|*'<'*|*'>'*|*'"'*) echo 'Use a checkout path without XML special characters'; exit 1;; esac
[[ "$(uname -s)" == Darwin ]] || { echo 'macOS required'; exit 1; }
[[ -f "$ROOT/.env" ]] || { echo 'Create .env first; see deploy/macos/README.md'; exit 1; }
JAVA_HOME="$(/usr/libexec/java_home -v 17)"
BRANCH="$(git -C "$ROOT" branch --show-current)"
[[ "$BRANCH" =~ ^[a-zA-Z0-9_./-]+$ ]] || { echo 'Check out a named deployment branch'; exit 1; }
AGENTS="$HOME/Library/LaunchAgents"
mkdir -p "$AGENTS" "$ROOT/.run" "$ROOT/logs"
umask 077
printf '%s\n' "$BRANCH" > "$ROOT/.run/deploy-branch"
cat > "$AGENTS/com.tradingautomation.server.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>com.tradingautomation.server</string>
<key>ProgramArguments</key><array><string>/bin/bash</string><string>$ROOT/scripts/run-deployed.sh</string></array>
<key>WorkingDirectory</key><string>$ROOT</string>
<key>EnvironmentVariables</key><dict><key>JAVA_HOME</key><string>$JAVA_HOME</string></dict>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>15</integer>
<key>StandardOutPath</key><string>$ROOT/logs/server.log</string>
<key>StandardErrorPath</key><string>$ROOT/logs/server.log</string>
</dict></plist>
PLIST
cat > "$AGENTS/com.tradingautomation.update.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>com.tradingautomation.update</string>
<key>ProgramArguments</key><array><string>/bin/bash</string><string>$ROOT/scripts/daily-update.sh</string></array>
<key>WorkingDirectory</key><string>$ROOT</string>
<key>EnvironmentVariables</key><dict><key>JAVA_HOME</key><string>$JAVA_HOME</string><key>DEPLOY_BRANCH</key><string>$BRANCH</string></dict>
<key>StartCalendarInterval</key><dict><key>Hour</key><integer>8</integer><key>Minute</key><integer>30</integer></dict>
<key>StandardOutPath</key><string>$ROOT/logs/update-launcher.log</string>
<key>StandardErrorPath</key><string>$ROOT/logs/update-launcher.log</string>
</dict></plist>
PLIST
plutil -lint "$AGENTS/com.tradingautomation.server.plist" "$AGENTS/com.tradingautomation.update.plist"
DOMAIN="gui/$(id -u)"
if launchctl print "$DOMAIN/com.tradingautomation.update" >/dev/null 2>&1; then
  launchctl bootout "$DOMAIN" "$AGENTS/com.tradingautomation.update.plist"
fi
launchctl bootstrap "$DOMAIN" "$AGENTS/com.tradingautomation.update.plist"
echo 'Installed daily 08:30 schedule in the Mac system timezone. Run daily-update.sh once for initial deployment.'
