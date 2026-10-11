# Trading Automation — Project Context

Steering for the `tradingautomation` workspace. Auto-loaded each session so the project is
understood without re-explaining. (The root `STEERING.md` is the ORIGINAL blueprint and is now
partly stale — prefer this file.)

## What it is
A Spring Boot service that connects to the **Webull OpenAPI v3** to run automated trading
strategies and emit market **alerts** (Telegram). Equities/ETFs, intraday.

## Stack & build
- **Java 17**, **Spring Boot 3.3.4**, **Gradle** (Groovy DSL). Core package `com.trading`.
- Broker access is a **direct v3 HTTP client** (OkHttp + Jackson) in `com.trading.webull` —
  the old Webull Java SDK was removed (it targeted the dead v1 API). Do NOT reintroduce the SDK.
- **Config is split into broker-neutral vs broker-specific** (see "Configuration" below) so adding
  a new broker never touches `app.*` domain config.
- Build: `./gradlew compileJava` · Test: `./gradlew test` (JUnit 5) · Run: `./gradlew bootRun`.
- **Always run the build + tests after changes** (`./gradlew compileJava test`) and prefer a
  real `bootRun` to confirm Spring wiring for DI-affecting changes.
- The whole app runs in **America/New_York (ET)**; the JVM default TZ is set to ET at startup.

## Running locally
Credentials come from env vars — nothing is hardcoded:
- `WEBULL_APP_KEY`, `WEBULL_APP_SECRET` (required; app fails fast without them).
- Optional Telegram: `TELEGRAM_BOT_TOKEN`, plus per-channel chat ids (see Alerts below).
- With dummy creds the context still boots; market-data calls return 401 (expected offline).

## Configuration (IMPORTANT — split by concern)
Two `@ConfigurationProperties` records, so a new broker only adds broker config:
- **`AppProperties`** (prefix **`app`**, `com.trading.config`) — broker-NEUTRAL domain config.
  Groups: `app.engine.*` (the trading engine knobs: mode, warmup, sizing, universe, sessions,
  concurrency — this was the old `webull.trading`), `app.strategies`, `app.exit`, `app.risk`,
  `app.market-hours`, `app.supertrend`, `app.adx`, `app.entry-guard`, `app.notifications`,
  `app.alerts`, `app.diagnostics`. **All domain code injects `AppProperties`** and reads
  `.engine()`, `.alerts()`, `.supertrend()`, etc.
- **`BrokerProperties`** (prefix **`broker.webull`**, `com.trading.config`) — broker-SPECIFIC:
  `broker.webull.api.*` (keys/host/token/account) and `broker.webull.endpoints.*` (v3 paths).
  `broker.provider` selects the adapter (default `webull` via `matchIfMissing=true`). **Only the
  `com.trading.webull` adapter reads broker config**, via `WebullApiProperties`.
- A new broker = add `broker.<name>.*` + its adapter; `app.*` and all domain code stay unchanged.

## Paper vs Live — IMPORTANT
Paper/live is the single property **`app.engine.mode`** (`PAPER` → sandbox host,
`LIVE` → production host). It is NOT a Spring profile (the root STEERING.md is wrong on this).
Broker host resolution lives in `WebullApiProperties.resolvedApiHost()`, which reads the
`broker.webull.api.endpoint` override else `AppProperties.isLiveMode()` — i.e. mode stays in the
app/domain config, the broker side just reads it. `app.engine.trading-enabled` is a separate
master switch for the automated loop. The committed `application.yml` currently has
`mode: LIVE` — be careful with order-placing code.

## Package map (`src/main/java/com/trading`)
- `alert/` — pluggable market-alert framework (see below).
- `indicator/` — Supertrend, EMA, ADX calculators + their services; the Swift Trend detector/scanner.
- `service/` — market-data layer (`MarketDataService`, `BarDataManager`), `BarResampler`,
  `TimeframeSupport`, `MarketHoursGuard`, Telegram senders, orchestrator.
- `webull/` — the v3 HTTP client / broker adapter (`WebullV3Client`), request signing.
- `broker/` — `BrokerClient` abstraction (so the app is broker-neutral).
- `config/` — `WebullProperties` (`@ConfigurationProperties(prefix="webull")`), `UniverseLoader`.
- `api/` — REST controllers (inspection + backtests): EMA, ADX, Supertrend, History,
  Swift-scan backtest, etc.
- `strategy/`, `state/`, `model/` — strategy logic, in-memory position state, the `Candle` record.
- `experimental/` — SANDBOX for strategy experiments (see "Experimental sandbox").

## Market-data layer (how bars flow)
- `MarketDataService.fetchHistoricalBars/Batch(...)` talks to the broker and returns oldest-first
  `Candle`s. Broker caps a request at **1650 bars** (`TimeframeSupport.BROKER_MAX_BARS`); exceeding
  it is an HTTP 417. The API only returns "most recent N" — there is **no date-range/pagination**,
  so deep history on fine timeframes isn't possible in one request.
- `BarDataManager` is the single cached, rate-limited, batch-prefetching owner of bars
  (key `ticker|timeframe`, invalidated per bar boundary). All consumers read through it.
- **Synthetic timeframes M2/M3** (any non-native `M<n>`): the broker only serves
  M1/M5/M15/M30/M60/M120/M240. M2/M3 are built by **resampling native M1** (`BarResampler`,
  hour-aligned OHLCV buckets). `MarketDataService` and `BarDataManager` do this transparently;
  `BarDataManager` fetches the underlying M1 once (shared) and caches the resampled series.
  Usable look-back for Mn ≈ 1650/n M1 bars (~2 days for M2/M3).
- `TimeframeSupport.minutesOf/isNativeMinute/isSynthetic` is the single source of truth for
  minute-timeframe classification — reuse it, don't re-add per-switch timeframe tables.

## Indicators
- **Supertrend** (`SupertrendCalculator`/`SupertrendService`): Wilder ATR, sticky bands;
  shared params `app.supertrend.length`/`factor` (default 7/3) used app-wide.
- **Swift Trend** (`SwiftTrendDetector` + `SwiftTrendScanner`, single source of truth): a
  Supertrend flip backed by ATR-normalised momentum. Gates (all ATR units): travel-since-flip,
  separation-from-line (a FLOOR, not required to widen), ST-line slope over a lookback,
  strong-candle count, whipsaw guard, climax-bar cap. One-alert-per-leg latch + cooldown + RTH gate.
  Note: `flip-within-bars` was REMOVED (the flip may be any age).
- When debugging "why did/didn't it fire", use the swift-scan backtest with `?debug=true` for a
  per-bar trace with the exact reject reason — don't guess from the thresholds.

## Alerts (`com.trading.alert`)
- `AlertEngine` (`@Scheduled` every minute, market-hours gated) discovers **every** `@Component`
  implementing `Alert` via `List<Alert>` injection — no central registry. Each alert runs on its
  own `scanIntervalMinutes()` cadence. The engine dedupes by `alertId|ticker` state string.
- **Delivery: per-alert channels, NO common channel.** The engine sends each hit to
  `alert.channelId()` via `TelegramChannelSender`. Each alert has its own configurable Telegram
  chat id (same or different per alert). A blank channel → that alert is skipped.
- Alerts: `OrbUpAlert`/`OrbDownAlert` (Opening-Range Breakout; optional EMA-slope and
  Supertrend-slope confluence gates), `StAdxAlert` (Supertrend + ADX), and `SwiftTrendAlert`
  (self-delivers to its own channel, returns empty hits so the engine dispatches nothing for it).
- All thresholds/knobs live under `app.alerts.*` in `application.yml`.

## Experimental sandbox (`com.trading.experimental`)
Rule the user set: **experiment WITHOUT touching working code.** Reuse production classes; when a
change is needed, SUBCLASS and override only the new logic; promote to the real package once a
config is backtested and proven.
- Config is a separate `experimental.*` `@ConfigurationProperties` loaded from
  `application-experimental.yml` via a local `@PropertySource` (NOT a Spring profile — a profile
  would override the shared `app.*` config globally and prevent running prod + experimental
  side-by-side). It reuses shared properties and only overrides the knobs under test; each knob
  DEFAULTS to the current production value.
- `ExperimentalSwiftTrendScanner` subclasses `SwiftTrendScanner`, overriding `length()`,
  `factor()`, `config()`.
- `GET /api/experimental/swift/backtest/{ticker}?date=&timeframe=&sessions=&count=` runs the SAME
  candle series through BOTH the production and experimental scanners and returns a side-by-side
  comparison. The production side here equals `/api/swift-scan/backtest/{ticker}` output.
- Gotcha when "experimental vs production don't match": the configs usually differ on a knob the
  experimental yaml didn't override (so it falls back to the experimental DEFAULT). Compare the
  returned `config` blocks field by field before assuming a bug.

## Conventions / expectations
- Prices use `BigDecimal` (never double) in indicator math.
- Match existing style; reuse existing calculators/services rather than duplicating logic.
- Keep changes config-driven and default to current behavior unless a change is explicitly a bug fix.
- Confirm before destructive git ops; prefer feature branches over committing to `main`.
- When documentation (javadoc/yaml comments) contradicts code, treat the CODE as truth and fix the docs.
