# Automatic daily deployment (Linux with systemd)

Use a dedicated, always-on Linux machine/VM and checkout. Windows can use a Linux
VM; these units do not run natively on Windows or macOS. Daily deployment runs at
07:00 America/New_York, including weekends, following daylight saving time. A
powered-off machine misses that run; it does not deploy unexpectedly on wake.

Commit and push changes from any development machine to the configured branch.
The deployment machine fetches that branch each morning, fast-forwards, performs
`clean build` including tests, then stops and starts the packaged server. It
rebuilds even when the revision has not changed. Updates are daily, not immediate.
A failed fetch/build leaves the running JAR intact. Failed startup attempts restore
the previous JAR; this does not undo external effects or configuration changes.

## One-time setup on the deployment machine

Install Git, Bash, JDK 17, curl, sudo, and util-linux (flock). Create a `trading`
user and clone your repository into `/opt/tradingautomation`, owned by that user.
Set up noninteractive Git access as `trading` (for example, a read-only SSH deploy
key and a verified known_hosts entry). Allow outbound access for Git and Gradle.
Check out the deployment branch, default `main`.

Create `/opt/tradingautomation/.env` on that machine using `.env.example` as the
reference. Use systemd EnvironmentFile syntax: plain KEY=value lines, no `export`,
no shell substitutions. Set WEBULL_APP_KEY and WEBULL_APP_SECRET and required
notification settings. Keep this file private (`chmod 600`, owner `trading`).
Review current `app.engine.mode` and `app.engine.trading-enabled` before starting;
the committed configuration uses LIVE mode. Local secrets are never pulled.
For a nondefault port, set SERVER_PORT here and DEPLOY_HEALTH_URL in the update unit.

Edit the units if your username, checkout path, Java path, branch, or time differs.
The Java home shown is the Ubuntu/Debian amd64 JDK 17 path; adjust for your host.
Then install:

```bash
sudo cp deploy/systemd/tradingautomation* /etc/systemd/system/
sudo systemctl daemon-reload
```

Using `sudo visudo -f /etc/sudoers.d/tradingautomation`, grant only these service
operations to the deployment user (adjust `/usr/bin/systemctl` to `command -v systemctl`):

```text
trading ALL=(root) NOPASSWD: /usr/bin/systemctl stop tradingautomation.service, /usr/bin/systemctl start tradingautomation.service
```

The updater uses `sudo -n` so it fails instead of waiting for a password. Do not
run the checkout/build as root. This account owns executable deployment code, so
restrict access to it and the deployment branch.

Stop any old bootRun/start.sh instance on this machine before the first deployment.
From a clean checkout containing these committed scripts, deploy once and enable:

```bash
sudo systemctl start tradingautomation-update.service
sudo systemctl enable tradingautomation.service
sudo systemctl enable --now tradingautomation-update.timer
systemctl list-timers tradingautomation-update.timer
curl --fail http://127.0.0.1:8080/api/health
```

The first update creates `.run/server.jar`. Start the service manually only after
that succeeds. Never use scripts/start.sh alongside this systemd service.
The liveness check does not prove broker authentication or trading readiness.

## Operations

```bash
sudo systemctl start tradingautomation-update.service  # update now
systemctl status tradingautomation.service
journalctl -u tradingautomation.service -n 100
journalctl -u tradingautomation-update.service -n 100
tail -n 100 /opt/tradingautomation/logs/daily-update.log
sudo systemctl disable --now tradingautomation-update.timer
```

A dirty checkout, diverged branch, build/test failure, or startup failure requires
inspection of the logs. The updater never discards local changes. Use `.env` for
machine-specific overrides rather than editing tracked YAML on this machine.
The deployed revision is recorded in `.run/deployed-revision`; Git HEAD may be
newer if a build or deployment fails. Previous JAR rollback reuses the current
external environment. Logs need your normal retention/logrotate policy.

No remote host is configured or scheduled by merely adding these repository files.
