# Alert Day-Report & Accuracy Playbook

How any session/LLM regenerates the daily alert report **with move-verification statistics**
and **per-alert-type comparison**. The goal: for every alert that fired, measure whether the
move it predicted actually happened, and roll that up per alert type so they can be compared.

> Prerequisite context: read `.kiro/steering/project-overview.md` first (project layout, alert
> types, timeframes, backtest endpoints). This file is the step-by-step report procedure.

---

## 1. Inputs — where the data comes from

Two sources, both already available:

1. **Fired alerts (what/when/direction):**
   - **ALL alert types now emit ONE uniform, machine-parseable line** via `AlertLog.fired(...)`:
     ```
     [ALERT_FIRED] id=orb-up ticker=PLTR dir=UP price=205.36 tf=M5 state=BREAKOUT_UP_2026-10-09 reason="channel=@x"
     [ALERT_FIRED] id=swift-trend ticker=PLTR dir=UP price=203.53 tf=M3 state=flip@... reason="swift UP: travel 2.20xATR, 2/5 strong"
     ```
     Grep `\[ALERT_FIRED\]` and parse the `key=value` fields — the SAME parser works for every
     alert type (orb-up, orb-down, swift-trend, st-adx). Fields: `id, ticker, dir, price, tf,
     state, reason` (reason is quoted; any field except id/ticker may be absent).
   - Log files: `logs/tradingautomation.log` (current day) and `logs/tradingautomation-<date>.N.log`.
   - Swift signals + prices can ALSO be pulled from
     `GET /api/swift-scan/backtest/{ticker}?date=YYYY-MM-DD&timeframe=M3` → `signals[]` (useful
     when the log for that date is no longer on disk — the log is still the primary source).

2. **Price series to VERIFY the move (the key enabler):**
   Fetch the day's bars for the ticker and read `close` per bar AFTER the alert time.
   - `GET /api/swift-scan/backtest/{ticker}?date=YYYY-MM-DD&timeframe=<tf>&debug=true`
     → `debugTrace[]` with `{timeEt, close, ...}` for every bar on the date. This is the
     simplest per-bar close series and works for ANY alert (not just swift) on that ticker.
   - Use the SAME timeframe the alert fired on (ORB scan tf, Swift timeframe) so bar times align.
   - The app must be running with valid creds (`./scripts/start.sh`). Requires network to the broker.

---

## 2. Define "did the move happen?" (be explicit and consistent)

For each alert at time **T**, entry price **P**, direction **D** (UP/DOWN), measure over a fixed
**evaluation horizon H** (default: to end of the RTH session; also report a short horizon of
**H_short = 30 minutes** for responsiveness). Using the post-T bar closes:

- **MFE (max favorable excursion):** the largest move in the alert's direction, as a % of P.
  - UP: `max(close[i] - P)/P` for bars after T. DOWN: `max(P - close[i])/P`.
- **MAE (max adverse excursion):** the largest move AGAINST the direction, as a % of P (≥ 0).
- **End move:** `(lastClose - P)/P` signed to the direction (so + = went the predicted way).
- **Hit target?** TRUE if MFE ≥ **target_pct** (default **0.5%**) BEFORE MAE ≥ **stop_pct**
  (default **0.3%**). This models "the move happened and was tradeable."
- **Stopped out?** TRUE if MAE ≥ stop_pct first.
- **Time-to-target:** minutes from T until MFE first reached target_pct (blank if never).
- **Classification per alert:**
  - **WIN** — hit target before stop.
  - **LOSS** — hit stop before target.
  - **FLAT** — neither within H (chop/no follow-through).

All thresholds (target_pct, stop_pct, H, H_short) are PARAMETERS — state them at the top of
every report so results are reproducible and comparable across days. Defaults above.

> Honest caveat: this is a mechanical, close-based proxy for "the move happened" — it does not
> model fills, slippage, spread, or intrabar high/low (it uses bar CLOSES). It is for COMPARING
> alert types on equal footing, not a P&L backtest. Say this in the report.

---

## 3. Procedure (step by step)

1. **Pick the date** (ET) and confirm bars exist for it (weekday, market open).
2. **Extract fired alerts** for the day, per type, into a table: `time, ticker, alertType,
   direction, entryPrice` (entryPrice from the log for Swift; for ORB use the breakout bar close
   — read it from the debugTrace close at the alert minute).
   - De-dupe repeated re-fires per the alert's own semantics (e.g. Swift one-per-leg; ORB
     re-breaks). Note any collapsing you do.
3. **For each ticker that fired**, fetch its per-bar close series for the day (debug trace).
4. **For each alert**, compute MFE, MAE, end-move, hit-target?, stopped-out?, time-to-target,
   and WIN/LOSS/FLAT per §2, using the bars strictly AFTER T.
5. **Build the per-alert detail table** (one row per fired alert) with those columns.
6. **Roll up per alert TYPE** (ORB-up, ORB-down, Swift-up, Swift-down, StAdx, …) → §4 stats.
7. **Write the report** (markdown + optional HTML dashboard like the Oct-9 one) with the
   parameters, per-alert detail, and the comparison table. Save under `logs/`.

---

## 4. Per-alert-type statistics (the comparison the user wants)

For EACH alert type produce a row:

| Stat | Meaning |
|------|---------|
| alerts | count fired (after de-dup) |
| win / loss / flat | counts per §2 classification |
| win-rate % | win / (win+loss+flat) |
| avg MFE % | mean max-favorable move |
| avg MAE % | mean max-adverse move |
| avg end-move % | mean signed end move (did it net go the right way?) |
| median time-to-target (min) | responsiveness when it worked |
| expectancy proxy | `win-rate*avg_target − loss-rate*avg_stop` (rough edge indicator) |

Then a **comparison table** with one row per alert type side by side, sorted by win-rate (or
expectancy). This is how you compare: e.g. "SwiftTrend M3 win-rate 58% vs ORB-up 41% — Swift
followed through more often but ORB-up had larger avg MFE when it worked."

Also report, per type: the distribution of outcomes by hour (do morning alerts work better?),
and the best/worst individual alerts (largest MFE, worst MAE).

---

## 5. Output format

Produce BOTH, saved in `logs/`:
- `alert-report-<date>.md` — parameters header, per-alert detail table, per-type stats, comparison
  table, and a short written takeaway (which alert type performed best and why, with the caveat
  from §2).
- `alert-report-<date>.html` — a self-contained dashboard (no external deps), mirroring the
  Oct-9 report style: KPI cards, per-type comparison bars, a timeline, and per-stock cards. Embed
  the computed data inline.

Register the HTML as an artifact so it previews in chat.

---

## 6. Quick command reference

```bash
# App must be running with creds:
./scripts/start.sh

# Swift signals + prices for a ticker (JSON signals[]):
curl -s "http://localhost:8080/api/swift-scan/backtest/PLTR?date=2026-10-09&timeframe=M3"

# Per-bar close series to verify the move (debugTrace[].timeEt/close):
curl -s "http://localhost:8080/api/swift-scan/backtest/PLTR?date=2026-10-09&timeframe=M3&debug=true"

# ALL fired alerts (uniform line) from the logs — one grep, one parser:
grep "\[ALERT_FIRED\]" logs/tradingautomation*.log
# filter by type if needed:
grep "\[ALERT_FIRED\] id=orb" logs/tradingautomation*.log
grep "\[ALERT_FIRED\] id=swift-trend" logs/tradingautomation*.log
```

Timeframe tips: use the alert's own timeframe (ORB scan-minutes, Swift timeframe). Synthetic
M2/M3 are supported by the endpoint (resampled from M1). Broker caps history at ~1650 M1 bars
(~2 days back on M2/M3), so verify recent dates.

---

## 7. Reproducibility checklist (every report MUST state)
- Date, parameters (target_pct, stop_pct, H, H_short), timeframe per alert type.
- How re-fires were de-duped per alert type.
- The caveat: close-based proxy, no fills/slippage/intrabar — for comparison, not P&L.
- Data coverage note if any ticker's bars didn't reach the date (broker 1650-bar limit).
