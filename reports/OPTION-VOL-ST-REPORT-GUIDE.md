# Option ST Alert — Replay Report Guide

How to produce the **Option Supertrend alert intraday-replay report** (`option-vol-st-replay-<date>.html`).
This mirrors the other `*-REPORT-GUIDE.md` docs: it is the reusable spec/template for regenerating
the report whenever the alert config changes or a new session needs reviewing.

## What the report shows
A bar-by-bar replay of the `option-vol-st` alert over **today's RTH session (09:30–16:00 ET)**:
for each configured underlying it picks ATM/OTM contracts off the stock's OPEN price, walks each
contract's M1 bars, and records every bar where ALL enabled legs pass (no lookahead), with a
per-contract cooldown. It is **detection only** — no orders, no fills/slippage.

## Source
Single endpoint, no params needed (defaults to all configured underlyings, today):

```
GET /api/options/vol-st/replay            # all underlyings
GET /api/options/vol-st/replay?underlying=AAPL   # one
```

The server must be running (`./scripts/start.sh`) during or after an RTH session. Options have no
deep dated history (most-recent-N bars only), so **only today's session is replayable** — older
dates cannot be reconstructed.

## Alert rules reflected (config: `app.alerts.option-vol-st`)
All legs are evaluated on the option's own `timeframe` bars; a contract fires only when every
ENABLED leg passes:
1. **Supertrend** direction UP and the line rising ≥ `st-rising-candles` completed bars.
2. **Close ≥ EMA** — option close at/above its `ema-period`-EMA on each of the last
   `ema-above-candles` bars (candle colour irrelevant).
3. **EMA stack** (if `ema-stack-enabled`) — fast EMA > slow EMA (`ema-stack-fast` > `ema-stack-slow`).
4. **Volume** (if `volume-enabled`) — session volume ≥ `min-session-volume` AND rising
   (recent-half avg > older-half avg over `volume-lookback`).

Contract universe: **ATM + OTM only** (ITM excluded), strikes generated off the stock OPEN.
After a fire, re-fires on the same contract are suppressed for `cooldown-minutes`.

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
1. Start the app during/after an RTH session.
2. `curl -s http://localhost:8080/api/options/vol-st/replay -o /tmp/replay.json`
3. Run the report generator (same transform documented here) → `reports/option-vol-st-replay-<date>.html`.
4. Stop the app.

The JSON shape the generator consumes:
```
{ date, session, timeframe, stRisingCandles, emaPeriod, emaAboveCandles,
  emaStackEnabled, emaStackFast, emaStackSlow, volumeEnabled, minSessionVolume,
  volumeLookback, cooldownMinutes, totalSignals,
  results: [ { underlying, stockOpen, contractsWithSignals, signalCount,
               contracts: [ { occSymbol, type, strike, moneyness, rthBars,
                              signals: [ { firedAtEt, optionClose, stRisingStreak,
                                           aboveEma, emaStackUp, volumeOk } ] } ] } ] }
```

## Caveats (always state in the report)
- Not an intraday *live* log — a replay over today's most-recent bars (walk + cooldown reproduce
  what the live alert would have fired, but latches/engine delivery are not involved here).
- Options API returns most-recent-N bars only → **today's RTH session only**.
- Strikes are picked off the stock OPEN, not reconstructed from an option chain at each minute.
- Detection/analysis only — no fills, slippage, or P&L.
