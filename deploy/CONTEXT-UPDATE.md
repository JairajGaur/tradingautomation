# Pending vault context — 2026-10-19

Append to existing October19 daily note: macOS daily deployment is scheduled for
08:30 Eastern (New York system timezone). Server runner and updater now use
`caffeinate -i` to prevent idle system sleep while running, including build/restart.
Display sleep remains allowed. Manual sleep, lid closure and power loss are not
overridden. Existing deployments must pull and run the updater once to restart
with this behavior. Verified installed macOS caffeinate manual, Bash syntax and
diff whitespace. No current server restart or machine power-settings changes.

Vault synchronization remains pending: vault is outside allowed write roots.
The existing reports/OBSIDIAN-SYNC-PENDING.md also rejected this follow-up write;
this repository note preserves the update.

Deployment branch update: user requested master. Remote had main and no master;
main was an ancestor of llm-advisory-layer (37 commits behind at inspection).
Create master from the latest code, retaining main. Deployment fallback, macOS
installer default and Linux unit now select master. Existing installations must
switch checkout to master and rerun installer to replace saved branch/LaunchAgent
configuration. No remote-host setup, server restart or default GitHub branch
change is implied by this repository update.

2026-10-20: added stop-macos-deployment.sh to disable and boot out both LaunchAgents
persistently, plus PID tracking to stop new manual updates and their process trees.
Installer explicitly re-enables agents when resuming. This does not stop unrelated
bootRun instances or older manual updaters without PID tracking. No stop executed
on this development machine. Vault synchronization remains pending.

2026-10-20: categorized all nine shell scripts into scripts/app, scripts/llm and
scripts/deploy. Root calculations now walk two directories; active references,
Bruno instructions and generated LaunchAgent/systemd paths updated. Added script
index and explicit deployment migration steps. Existing Macs must reinstall agents;
Linux hosts must reinstall the updated unit and daemon-reload. Shell syntax and
root resolution verified without launching/stopping services. Vault sync pending.

2026-10-20: corrected executable permissions on all shell scripts, including the
new deployment shutdown script. Verified executable bits and Bash syntax without
running shutdown or restarting services. Changes remain local pending commit.

2026-10-20: installer/updater now honor explicit JAVA_HOME and check for java and
javac before changing jobs; missing JDK prints a Temurin17 installation command
instead of Apple's generic Runtime dialog. Setup guide includes verification.
