# Daily deployment on macOS — 08:30 Eastern

1. Install Git and JDK 17 on the other Mac. Clone this repository into a dedicated
   folder; check out the branch you want deployed. Configure noninteractive Git
   authentication (SSH deploy key and verified known_hosts, or Keychain credentials).
2. Create `.env` from `.env.example`, supply credentials, and `chmod 600 .env`.
   This file is sourced as Bash; use only trusted configuration. Review current
   `app.engine.mode` (currently LIVE) and `app.engine.trading-enabled` before running.
3. Set macOS Settings → General → Date & Time → timezone to New York. LaunchAgents
   use the Mac's system timezone. This means 08:30 EST in winter, 08:30 EDT in summer.
4. Commit and push these deployment scripts first. On this dedicated clone, run:

   ```bash
   bash scripts/install-macos-schedule.sh
   # Stop any existing start.sh/bootRun instance on THIS Mac first.
   bash scripts/daily-update.sh
   ```

The installer registers the morning job for the logged-in user. Initial deployment
builds/tests and starts the server; the server then restarts on failure and on login.
The Mac must be powered on and the user logged in. A sleeping Mac may run a missed
calendar job on wake; keep it awake for reliable morning execution. FileVault/login
can prevent automatic startup after reboot until the user logs in.

Commit and push changes from anywhere to the selected branch. Each morning the Mac
fetches and fast-forwards, runs a clean build including tests, stops the server,
installs the new JAR, and starts it. Unpushed edits do not sync. Dirty or diverged
checkouts fail without overwriting changes. Keep machine overrides in `.env`.

A failed build leaves the running JAR intact. Startup is checked for up to roughly
six minutes using `/api/health`; failed startup restores the previous JAR when present.
This is liveness, not broker authentication validation. Rollback uses current external
configuration and cannot undo external effects. Set DEPLOY_HEALTH_URL when running
the updater if SERVER_PORT differs; for scheduled runs add that variable to the
update plist EnvironmentVariables, then unload/reload that agent.

Logs: `logs/daily-update.log`, `logs/server.log`, `logs/update-launcher.log`.
Successful revision: `.run/deployed-revision`. Git HEAD can be newer after failure.
Arrange log rotation. After a forced termination, if no updater remains running,
remove the stale `.run/daily-update.lockdir` before retrying.

Run an update now: `bash scripts/daily-update.sh`.
Disable daily updates:

```bash
launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/com.tradingautomation.update.plist"
```

Do not use scripts/start.sh alongside this LaunchAgent. Installation is required on
the other Mac; adding these files does not schedule or restart this development Mac.
