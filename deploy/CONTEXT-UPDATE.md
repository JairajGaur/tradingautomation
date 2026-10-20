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
