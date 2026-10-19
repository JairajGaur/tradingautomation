# Option ST Alert — Replay Report Guide

[Report knowledge graph](KNOWLEDGE-GRAPH.md) — report families, source code, configuration and validation links.

How to produce the **Option Supertrend alert intraday-replay report** (`option-vol-st-replay-<date>.html`).
This mirrors the other `*-REPORT-GUIDE.md` docs: it is the reusable spec/template for regenerating
the report whenever the alert config changes or a new session needs reviewing.

## What the report shows
A bar-by-bar replay of the `option-vol-st` alert over **the last completed RTH session (09:30–16:00 ET)**:
for each configured underlying it picks ATM/OTM contracts off the stock's OPEN price, walks each
contract's M1 bars, and records every bar where ALL enabled legs pass (no lookahead), with a
stock-and-side cooldown. It is **detection only** — no orders, no fills/slippage.

## Source
Single endpoint, no params needed (defaults to all configured underlyings, latest available session):

```
GET /api/options/vol-st/replay            # all underlyings
GET /api/options/vol-st/replay?underlying=AAPL   # one
```

The running server chooses the latest date with an actual 09:30 ET stock opening bar in returned
history. Weekends, holidays, and premarket requests fall back to the previous available session.
`date` is the replay date; `requestDate` is today in ET; `previousSession` identifies fallback.
Expiry is resolved from the replay date. Options use most-recent-N history, so missing or expired
contract data is reported explicitly via row notes and `contractsWithSessionData`.

## Alert rules reflected (config: `app.alerts.option-vol-st`)
Option legs use the option's own `timeframe` bars; a contract fires only when every
ENABLED leg passes:
1. **Supertrend** direction UP and the line rising ≥ `st-rising-candles` completed bars.
2. **Close ≥ EMA** — option close at/above its `ema-period`-EMA on each of the last
   `ema-above-candles` bars (candle colour irrelevant).
3. **EMA stack** (if `ema-stack-enabled`) — fast EMA > slow EMA (`ema-stack-fast` > `ema-stack-slow`).
4. **Volume** (if `volume-enabled`) — session volume ≥ `min-session-volume` AND rising
   (recent-half avg > older-half avg over `volume-lookback`).
5. **Underlying Heikin Ashi** (if `ha-direction-enabled`) — the latest completed stock HA
   candle on `ha-timeframe` (M15 by default) allows CALLs when green and PUTs when red.
   Doji, missing, prior-session or stale HA data blocks both sides. Historical replay uses
   only HA candles closed at each evaluation instant; future/forming colors are excluded.
   Stock bars use the existing configured trading sessions and shared cache. HA values are
   calculated from stock OHLC, not from option candles. Existing option legs remain required.

```yaml
app:
  alerts:
    option-vol-st:
      ha-direction-enabled: true
      ha-timeframe: "M15"
```

The property defaults to disabled for configurations that omit it; the project YAML enables it.
Both dry-run and replay responses expose `haDirectionEnabled` and `haTimeframe`. Dry-run
contract rows also expose `haColor` and `haDirectionOk` to explain the side filter.

### Optional large option candle filter

The option strategy reuses the exact Swift Trend candle-size cap through the shared
`CandleRangeAtrFilter`. It rejects the latest completed OPTION candle when
`(high - low) / ATR > max-candle-atr`. ATR is the current completed point's Wilder ATR,
using the configured shared Supertrend period; it includes that candle, matching Swift.
Comparison keeps Swift's four-decimal rounding. This checks real option OHLC, not stock HA.

```yaml
app:
  alerts:
    option-vol-st:
      large-candle-filter-enabled: true # false disables the gate
      max-candle-atr: 3.0              # <=0 also disables it; equality passes
```

The toggle defaults to false when omitted; the project YAML enables it. This cap affects the
latest completed signal candle only, with no added cooldown or multi-candle lookback.
It adds no broker requests. Dry-run contract rows expose `candleSizeOk`, `candleRangeAtr`
and a rejection `note`. Replay exposes `largeCandleRejections` counts per underlying and
fired contract, separate from data-quality failures, including aggregate counts when no
contracts fire. Counts represent cap rejections after quality/HA/cooldown checks and before
the remaining option signal conditions. Both endpoints expose the toggle and threshold.

## Candle validation and recovery

Signals use timestamp-confirmed completed candles (`start + timeframe <= evaluation time`).
The signal price is the latest completed close. Responses that include a forming candle and
responses containing completed candles only are handled consistently.

Before calculating option indicators or underlying HA, the quality gate sorts timestamps,
collapses identical duplicates, rejects conflicting duplicates or invalid OHLCV, and checks
freshness, actual warmup size and recent active-session gaps. Overnight closures and session
transitions are excluded from gap counts. Native candles are validated at the broker's returned
timeframe; synthetic M3/HA buckets require all expected source candles. The gate cannot infer
missing trades inside a native candle from OHLCV alone.

```yaml
app:
  alerts:
    option-vol-st:
      bar-quality:
        max-age-bars: 1
        freshness-grace-seconds: 2
        gap-window: 20
        max-missing-bars: 1
        min-warmup-bars: 0
        ha-warmup-bars: 20
```

`max-age-bars` measures time since candle close in units of its timeframe (not time since the
HTTP response). `min-warmup-bars: 0` derives a mathematical minimum from enabled option
indicators; a positive value can require more history. HA requires 20 completed candles by default.

If live/inspection history fails validation, one targeted, rate-limited refresh is allowed per
symbol/timeframe per wall-clock minute, requesting up to 1200 option bars or 1650 stock bars.
Fresh broker history is revalidated; a failed recovery blocks the signal. Old cache data remains
available for diagnostics, and the next scheduled scan can retry. Recovery never manufactures
candles or fills missing intervals with repeated prices.

Replay uses the historical evaluation timestamp for these same checks and does not run live
recovery during its per-bar walk. Per-underlying `dataQualityRejections` counts explain suppressed
evaluations, including contracts with no signals. Dry-run rows report the failure reason in `note`.

Contract universe: **ATM + OTM only** (ITM excluded), strikes generated off the stock OPEN.
All qualifying candidates are collected before cooldown. One alert groups simultaneous strikes
for each underlying and CALL/PUT side; `cooldown-minutes` suppresses that entire stock/side,
even if its qualifying strike changes or the condition temporarily clears. CALL and PUT have
independent cooldowns. Every qualifying strike remains visible, ordered numerically, without ranking.
Replay uses the historical evaluation time (`evaluatedAtEt`); `firedAtEt` is the completed candle start.
`totalSignals`/`signalCount` now count groups; `totalContractSignals`/`contractSignalCount` count their
retained strike rows. `groupedSignals` contains each group and all candidates. Raw candidate and
suppressed-group counts are also returned. Live cooldown starts after a configured Telegram
send attempt; unavailable destinations do not consume it. Sender success means an attempt,
not guaranteed delivery. Live state is in memory and resets on app restart.

## Report sections (presentation)
1. **Header** — date, session, generated-at, mode; a caveat box restating the rules + data limits.
2. **At a glance** — stat cards: total signals, contracts fired (+ avg/contract), CALL/PUT split,
   first→last signal time, avg ST streak at fire, busiest contract.
3. **Signals by hour (ET)** — horizontal bar chart of signal counts per clock hour.
4. **Per-underlying** — table: open, contracts fired, CALL / PUT / total signal counts.
5. **Fired signals (per contract)** — a card per contract (CALL/PUT pill, OCC, strike, moneyness,
   RTH bars, count) with a table of each fire: time (ET), option close, ST streak, ≥EMA, EMA stack,
   volume.

## Macros / derived metrics
- `total` = Σ signals; `contractsFired` = distinct OCCs that fired; `avg/contract` = total/contractsFired.
- CALL/PUT split from each contract's `type`.
- Hourly histogram from each signal's `firedAtEt` hour.
- `avg ST streak` = mean `stRisingStreak` across signals.
- `busiest` = OCC with the most signals.

## How to regenerate
1. Verify the running app includes the intended source/configuration. When uncertain, use
   `scripts/stop.sh`, `./gradlew clean build`, and `scripts/start.sh` before capturing data.
   A health response alone does not establish which code version is running.
2. Save the read-only replay response. Closed-market requests select the latest available session:

   ```bash
   curl --fail-with-body --max-time 240 -sS http://localhost:8080/api/options/vol-st/replay -o /tmp/replay.json
   ```

3. Render with the reusable generator, retaining the existing report's styling:

   ```bash
   python3 scripts/option_replay_report.py \
     --input /tmp/replay.json \
     --out reports/option-vol-st-replay-SESSION-analysis-GENERATED.html
   ```

   Replace `SESSION` with the response's `date` and `GENERATED` with the ET generation date.
   The renderer produces HTML, the exact source JSON, a signal CSV and a metadata JSON sidecar.
   It performs no broker calls, app startup, trading or notification delivery.
   `--metadata /path/to/provenance.json` adds captured restart/build/source details; missing
   metadata is labeled unknown. Existing files are protected unless `--replace` is explicitly
   supplied for a draft. Preserve older dated reports as historical snapshots.
4. Verify signal totals against JSON and CSV, check runtime HA/ATR/quality fields, and visually
   review the HTML and contract filter. Record data limitations rather than inventing metrics.
5. Stop a temporary report instance or restore the original app startup settings after capture.

Latest generated analysis: [2026-10-16 replay, generated 2026-10-18](option-vol-st-replay-2026-10-16-analysis-2026-10-18.html).

The JSON shape the generator consumes:
```
{ date, requestDate, previousSession, session, timeframe, stRisingCandles, emaPeriod, emaAboveCandles,
  emaStackEnabled, emaStackFast, emaStackSlow, volumeEnabled, minSessionVolume,
  volumeLookback, threeMinStEnabled, threeMinStTimeframe, haDirectionEnabled, haTimeframe,
  largeCandleFilterEnabled, maxCandleAtr, barQuality, cooldownMinutes, totalSignals,
  results: [ { underlying, stockOpen, contractsWithSessionData, contractsWithSignals, signalCount,
               dataQualityRejections, largeCandleRejections,
               contracts: [ { occSymbol, type, strike, moneyness, rthBars,
                              dataQualityRejections, largeCandleRejections,
                              signals: [ { firedAtEt, optionClose, stRisingStreak,
                                           aboveEma, emaStackUp, volumeOk } ] } ] } ] }
```

The schema above is illustrative: some older configuration fields are not returned by replay.
Signal rows inherit OCC symbol, type, strike and moneyness from their parent contract; the
renderer uses that relationship for accurate CALL/PUT counts and CSV identifiers.

## Caveats (always state in the report)
- Replay and live alerts share stock/side grouping and cooldown semantics; live state resets on restart.
- Options API returns most-recent-N bars only. Selected-session history can be incomplete or
  unavailable, especially for expired contracts. Zero signals with no session data is not a strategy result.
- Strikes are picked off the stock OPEN, not reconstructed from an option chain at each minute.
- Detection/analysis only — no fills, slippage, or P&L.

Latest grouped analysis: [2026-10-16 session, generated 2026-10-18](option-vol-st-replay-2026-10-16-grouped-2026-10-18.html).
For grouped responses the renderer uses group evaluation times for hourly charts, group-side counts
for CALL/PUT totals, and shows a stock/direction table with every candidate. Contract CSV includes
`evaluatedAtEt` and completed candle `firedAtEt`. Preserve both counting scopes; older snapshots
remain per-contract. This report reuses the verified 21:13:06 EDT capture, with 10-minute cooldown.

## Dynamic expiry and last-session replay (2026-10-18)

`app.options.dte` is removed. Live and replay call the same `OptionContractService.resolveExpiry`
using the live ET date or selected replay session date. It chooses the nearest actual standard-contract
expiry on/after that date; there is no guessed Friday, calendar-DTE, or missing-bar fallback.
Replay is limited to the last completed RTH session: intraday requests use the preceding session;
at/after 16:00 ET today's actual opening session becomes eligible. Weekends/holidays use the latest
available completed session with a stock opening bar. Arbitrary historical replay is not exposed.

Webull metadata comes from `/trading/instruments/options/contracts/list`, with pagination and
local validation of underlying/root/standard type/date. Actual signed broker probes established
`end_date=DATE` returns that exact expiry, including expired contracts omitted from the general
listing. The resolver checks exact reference/intervening dates first, then upcoming contracts via
`start_date`; do not assume the documentation's inconsistent parameter descriptions are accurate.
The general `DELISTING` response alone did not recover the known last-session contracts.

Successful lookups cache per stock/reference session/current ET day. Failures are throttled for
one minute and surfaced explicitly; old or partial metadata is never cached as a success after a
page failure. Live skips the affected underlying; replay reports discovery unavailable and
continues other stocks. Missing candles use existing recovery/quality rules and do not roll expiry.
Strike generation, HA, ATR, volume, ST/EMA gates and configured stock/side cooldown are retained.
No ranking is added. Expiry is per underlying and returned as `expiry`/`expirySelection`; top-level
`replayScope=LAST_COMPLETED_SESSION`. `GET /api/options/expiry?symbol=AAPL` inspects live selection;
`?symbol=SPY&replay=true` inspects the latest replay session, using the same resolver.

Verification: 116 tests pass. Live AAPL selected 2026-10-19 with 11 contracts loaded; replay SPY
selected session and expiry 2026-10-16. Prior dated reports remain historical snapshots and were
not regenerated after this expiry change. Metadata discovery confirms an expiry, not every
synthetic strike; unavailable generated strikes remain per-contract diagnostics.
