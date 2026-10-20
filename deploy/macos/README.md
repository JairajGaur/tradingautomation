# Daily deployment on macOS — 08:30 Eastern

1. Install Git and JDK 17 on the other Mac. Clone this repository into a dedicated
   folder; check out `master`, the deployment branch. Configure noninteractive Git
   authentication (SSH deploy key and verified known_hosts, or Keychain credentials).
2. Create `.env` from `.env.example`, supply credentials, and `chmod 600 .env`.
   This file is sourced as Bash; use only trusted configuration. Review current
   `app.engine.mode` (currently LIVE) and `app.engine.trading-enabled` before running.
3. Set macOS Settings → General → Date & Time → timezone to New York. LaunchAgents
   use the Mac's system timezone. This means 08:30 EST in winter, 08:30 EDT in summer.
4. Commit and push these deployment scripts first. On this dedicated clone, run:

   ```bash
   bash scripts/deploy/install-macos-schedule.sh
   # Stop any existing start.sh/bootRun instance on THIS Mac first.
   bash scripts/deploy/daily-update.sh
   ```

The installer registers the morning job for the logged-in user. Initial deployment
builds/tests and starts the server; the server then restarts on failure and on login.
The Mac must be powered on and the user logged in. The server and updater use
macOS `caffeinate -i` to prevent automatic sleep due to inactivity while either runs.
The display can still turn off. Keep the Mac plugged in and a laptop lid open;
this does not override manual Sleep, lid closure, shutdown, or a drained battery.
If the Mac is already sleeping, the job cannot wake it; a missed calendar job may
run on wake. FileVault/login can prevent startup after reboot until login.
After pulling this change on an existing deployment, run the updater once so the
server restarts with the sleep assertion. Check it with `pmset -g assertions`.

Commit and push changes from anywhere to `master`. Each morning the Mac
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

Run an update now: `bash scripts/deploy/daily-update.sh`.
Disable daily updates:

```bash
launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/com.tradingautomation.update.plist"
```

Do not use scripts/app/start.sh alongside this LaunchAgent. Installation is required on
the other Mac; adding these files does not schedule or restart this development Mac.

## Switch an existing deployment to master

In the deployment checkout, with no tracked local edits:

```bash
git fetch origin
git switch master  # creates a tracking branch if only origin/master exists
git pull --ff-only origin master
bash scripts/deploy/install-macos-schedule.sh
bash scripts/deploy/daily-update.sh
```

Reinstalling replaces the saved branch and scheduled job configuration with `master`.
For a new machine, clone with
`git clone --branch master https://github.com/JairajGaur/tradingautomation.git`.

## Stop the entire managed deployment

Run as the same login user that installed it:

```bash
bash scripts/deploy/stop-macos-deployment.sh
```

This disables both LaunchAgents across future logins, stops the scheduled updater
and server, and stops a manual updater started with the new PID-recording script.
It preserves credentials, JARs and logs. Older manual updater runs without the PID
record must be stopped with Ctrl-C in their original terminal. Separate bootRun or
scripts/app/start.sh instances are outside this managed deployment.

To resume, rerun the installer, then daily-update.sh. If a terminated update left
`.run/daily-update.lockdir`, check no updater remains before removing that directory.

## Migration from the old flat scripts folder

On an already configured deployment Mac, stop the deployment with its old script
**before pulling** the folder reorganization:

```bash
bash scripts/stop-macos-deployment.sh
git pull --ff-only origin master
bash scripts/deploy/install-macos-schedule.sh
bash scripts/deploy/daily-update.sh
```

The first command above is the old path and is only for this migration. If you
already pulled, use `bash scripts/deploy/stop-macos-deployment.sh` instead.
Reinstalling updates both LaunchAgent plist paths. The schedule stays 08:30.

## Missing Java Runtime / JDK 17

With Homebrew installed, run:

```bash
brew install --cask temurin@17
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
bash scripts/deploy/install-macos-schedule.sh
bash scripts/deploy/daily-update.sh
```

Without Homebrew, install the macOS JDK 17 package from https://adoptium.net/.
Use the package matching your Mac (Apple Silicon or Intel). An explicitly set
JAVA_HOME must point to a full JDK, including bin/java and bin/javac.
