# Alert Accuracy Report — SwiftTrend, 2026-10-09

Generated per `logs/ALERT-REPORT-GUIDE.md`. This verifies, for each fired SwiftTrend signal,
whether the predicted move actually happened, and rolls the results up per alert type.

## Parameters (reproducibility)
- **Date:** 2026-10-09 (ET) · **Timeframe:** M3 (synthetic, resampled from M1)
- **Target:** 0.5% in the signal direction · **Stop:** 0.3% against it
- **Horizon:** rest of the fetched session (uses bar CLOSES after the signal)
- **Classification:** WIN = hit target before stop · LOSS = hit stop before target · FLAT = neither
- **Source:** `GET /api/swift-scan/backtest/{ticker}?date=2026-10-09&timeframe=M3&debug=true`
  for all 29 universe symbols. `signals[]` are the latch/cooldown-deduped fired signals; the
  `debugTrace[]` closes after each signal time drive MFE/MAE.
- **Metrics:** MFE = max favorable excursion; MAE = max adverse excursion; End = signed move at
  session end; T2T = minutes to first reach target.

> **Scope caveat (important):** This covers **SwiftTrend only**. ORB and StAdx are NOT included —
> their Oct-9 fired-alert logs are no longer on disk (dated logs were rolled/removed) and there is
> no ORB/StAdx backtest endpoint to reconstruct them. To compare ORB vs Swift, re-run on a day
> whose alert logs are present (see guide §1), or add an ORB backtest endpoint.
>
> **Methodology caveat:** close-based proxy — no fills, slippage, spread, or intrabar high/low.
> It measures whether price *closed* far enough in the signal's direction. Use for comparing
> alert behaviour, NOT as P&L.

## Headline — per-type statistics

| Type | Alerts | W/L/F | Win% | AvgMFE | AvgMAE | AvgEnd | MedT2T(min) |
|------|-------:|-------|-----:|-------:|-------:|-------:|------------:|
| Swift **UP**   | 44 | 18/15/11 | **41%** | 0.94% | 0.42% | **+0.43%** | 39 |
| Swift **DOWN** | 40 | 6/21/13 | **15%** | 0.43% | 0.76% | **−0.29%** | 90 |
| Swift **ALL**  | 84 | 24/36/24 | 29% | 0.70% | 0.58% | +0.09% | 54 |

### Read of the day
- **UP signals clearly outperformed DOWN** on 2026-10-09: UP won 41% with a positive average end
  move (+0.43%) and favorable MFE > MAE (0.94% vs 0.42%). DOWN signals won only 15%, with adverse
  MAE exceeding favorable MFE (0.76% vs 0.43%) and a negative average end move (−0.29%).
- This matches a **risk-on / up-trending session**: shorting into a rising tape mostly failed,
  while longs followed through. The engine fired roughly as many DOWN (40) as UP (44), so the
  edge is in the *outcome*, not the count — a directional-regime filter would have helped.
- **Overall 29% win-rate** at a 0.5%/0.3% target/stop with 24 FLAT (no follow-through) says many
  M3 signals chop sideways; the winners that did work reached target in a median ~54 min.
- **Best winners:** MRNA UP 09:36 (MFE 9.41%), MRNA UP 12:00 (5.05%), COIN UP 10:12 (2.95%),
  PLTR UP 11:15 (2.71%), AMD DOWN 09:30 (2.47%). **Worst adverse:** COIN DOWN 09:33 (MAE 5.95%),
  DELL DOWN 10:36 (3.41%), HOOD DOWN 09:33 (2.40%).

## Per-signal detail
(84 fired signals, chronological. MFE/MAE/End as % of entry; T2T in minutes.)

| Time | Ticker | Dir | Entry | MFE | MAE | End | T2T | Result |
|------|--------|-----|------:|----:|----:|----:|----:|--------|
| 09:30 | QQQ | DOWN | 751.77 | 0.38% | 0.02% | +0.05% | – | FLAT |
| 09:30 | AMD | DOWN | 622.00 | 2.47% | 0.00% | +2.19% | 12 | WIN |
| 09:30 | SLV | UP | 54.85 | 0.66% | 0.13% | +0.09% | 18 | WIN |
| 09:30 | RKLB | DOWN | 68.22 | 1.64% | 1.35% | +0.04% | 3 | WIN |
| 09:33 | COIN | DOWN | 172.24 | 0.00% | 5.95% | −4.51% | – | LOSS |
| 09:33 | HOOD | DOWN | 107.08 | 0.00% | 2.40% | −1.87% | – | LOSS |
| 09:33 | MU | DOWN | 1039.39 | 2.08% | 0.32% | +1.05% | 48 | LOSS |
| 09:36 | BA | UP | 189.53 | 0.72% | 0.27% | +0.56% | 12 | WIN |
| 09:36 | MRNA | UP | 206.11 | 9.41% | 1.58% | +9.38% | 18 | LOSS |
| 09:39 | CVX | UP | 212.22 | 0.65% | 0.37% | −0.17% | 87 | WIN |
| 09:39 | NFLX | UP | 71.84 | 0.14% | 2.18% | −2.12% | – | LOSS |
| 09:42 | TSLA | UP | 386.69 | 0.44% | 1.71% | −1.02% | – | LOSS |
| 09:51 | GLD | UP | 384.45 | 0.18% | 0.28% | +0.01% | – | FLAT |
| 10:00 | USO | UP | 147.99 | 0.63% | 0.81% | +0.28% | 66 | LOSS |
| 10:03 | GS | DOWN | 880.16 | 0.07% | 2.18% | −1.96% | – | LOSS |
| 10:12 | COIN | UP | 177.26 | 2.95% | 0.00% | +1.55% | 3 | WIN |
| 10:15 | SPCX | DOWN | 162.14 | 0.94% | 1.27% | −0.38% | 186 | LOSS |
| 10:18 | GOOGL | UP | 352.90 | 0.48% | 0.57% | −0.37% | – | LOSS |
| 10:21 | HOOD | UP | 109.65 | 0.00% | 0.94% | −0.52% | – | LOSS |
| 10:21 | ORCL | UP | 141.25 | 1.09% | 0.00% | +0.22% | 15 | WIN |
| 10:36 | DELL | DOWN | 569.41 | 0.37% | 3.41% | −2.85% | – | LOSS |
| 10:42 | AMZN | UP | 259.48 | 1.23% | 0.00% | +1.17% | 60 | WIN |
| 10:45 | MSFT | UP | 532.69 | 0.80% | 0.11% | +0.43% | 234 | WIN |
| 10:57 | AAPL | UP | 334.06 | 1.35% | 0.08% | +0.60% | 198 | WIN |
| 11:00 | GS | UP | 887.71 | 1.31% | 0.08% | +1.09% | 177 | WIN |
| 11:03 | NFLX | DOWN | 71.13 | 1.20% | 0.00% | +1.14% | 105 | WIN |
| 11:15 | PLTR | UP | 203.53 | 2.71% | 0.09% | +2.56% | 9 | WIN |
| 11:21 | TSLA | DOWN | 383.05 | 0.77% | 0.45% | +0.08% | 12 | WIN |
| 11:45 | GOOGL | UP | 353.35 | 0.35% | 0.70% | −0.49% | – | LOSS |
| 11:57 | NVDA | DOWN | 229.28 | 0.00% | 0.30% | −0.02% | – | FLAT |
| 12:00 | MRNA | UP | 214.67 | 5.05% | 0.25% | +5.02% | 24 | WIN |
| 12:03 | UVXY | DOWN | 16.27 | 0.18% | 0.92% | −0.31% | – | LOSS |
| 12:03 | CVX | DOWN | 212.66 | 0.57% | 0.34% | +0.37% | 165 | LOSS |
| 12:06 | META | UP | 726.11 | 0.25% | 1.34% | −1.14% | – | LOSS |
| 12:12 | BA | DOWN | 189.81 | 0.04% | 0.50% | −0.42% | – | LOSS |
| 12:12 | DELL | UP | 575.60 | 2.29% | 0.09% | +1.75% | 33 | WIN |
| 12:24 | USO | DOWN | 147.81 | 0.69% | 0.69% | −0.40% | 144 | LOSS |
| 12:30 | SLV | DOWN | 54.82 | 0.08% | 0.54% | −0.15% | – | LOSS |
| 12:30 | MU | UP | 1028.73 | 0.61% | 0.47% | −0.03% | 99 | WIN |
| 12:39 | SPCX | DOWN | 162.07 | 0.89% | 0.65% | −0.43% | 51 | LOSS |
| 12:45 | SPY | UP | 778.15 | 0.14% | 0.04% | +0.05% | – | FLAT |
| 12:48 | COIN | UP | 181.85 | 0.35% | 1.36% | −1.02% | – | LOSS |
| 12:48 | JPM | UP | 333.15 | 0.14% | 0.20% | +0.14% | – | FLAT |
| 12:54 | IWM | UP | 278.99 | 0.30% | 0.09% | −0.04% | – | FLAT |
| 12:54 | UVXY | UP | 16.34 | 0.49% | 0.37% | −0.12% | – | LOSS |
| 13:06 | AMZN | DOWN | 260.52 | 0.20% | 0.83% | −0.77% | – | LOSS |
| 13:09 | GOOGL | DOWN | 352.82 | 0.55% | 0.00% | +0.34% | 93 | WIN |
| 13:15 | GLD | UP | 384.48 | 0.17% | 0.11% | +0.01% | – | FLAT |
| 13:18 | CVX | DOWN | 212.58 | 0.54% | 0.26% | +0.34% | 90 | WIN |
| 13:21 | HOOD | DOWN | 108.65 | 0.03% | 0.75% | −0.40% | – | LOSS |
| 13:30 | BA | UP | 190.49 | 0.14% | 0.37% | +0.06% | – | LOSS |
| 13:33 | META | DOWN | 718.89 | 0.35% | 0.29% | +0.15% | – | FLAT |
| 13:36 | AAPL | UP | 335.27 | 0.99% | 0.14% | +0.23% | 69 | WIN |
| 13:48 | ORCL | UP | 142.46 | 0.23% | 0.84% | −0.63% | – | LOSS |
| 13:48 | RKLB | UP | 67.89 | 1.84% | 0.00% | +0.44% | 6 | WIN |
| 14:15 | JPM | DOWN | 332.48 | 0.00% | 0.34% | −0.34% | – | LOSS |
| 14:21 | SPY | UP | 778.68 | 0.07% | 0.06% | −0.01% | – | FLAT |
| 14:21 | QQQ | UP | 751.50 | 0.06% | 0.10% | −0.02% | – | FLAT |
| 14:24 | COIN | DOWN | 180.60 | 0.67% | 0.70% | +0.33% | 90 | LOSS |
| 14:24 | DELL | UP | 585.87 | 0.50% | 0.26% | −0.04% | 90 | WIN |
| 14:30 | HOOD | DOWN | 108.63 | 0.01% | 0.76% | −0.41% | – | LOSS |
| 14:30 | CVX | DOWN | 212.39 | 0.45% | 0.15% | +0.25% | – | FLAT |
| 14:33 | GOOGL | DOWN | 351.17 | 0.08% | 0.44% | −0.13% | – | LOSS |
| 14:33 | SPCX | DOWN | 160.87 | 0.16% | 1.40% | −1.17% | – | LOSS |
| 14:39 | MSFT | UP | 535.74 | 0.22% | 0.23% | −0.14% | – | FLAT |
| 14:42 | BA | DOWN | 189.79 | 0.00% | 0.51% | −0.43% | – | LOSS |
| 14:45 | AMZN | UP | 261.18 | 0.57% | 0.00% | +0.52% | 54 | WIN |
| 14:48 | SLV | UP | 55.12 | 0.00% | 0.62% | −0.40% | – | LOSS |
| 14:48 | USO | DOWN | 146.79 | 0.00% | 1.16% | −1.10% | – | LOSS |
| 14:54 | MU | DOWN | 1028.39 | 0.44% | 0.18% | −0.01% | – | FLAT |
| 15:00 | NFLX | DOWN | 70.38 | 0.15% | 0.18% | +0.09% | – | FLAT |
| 15:09 | UVXY | DOWN | 16.34 | 0.37% | 0.15% | +0.12% | – | FLAT |
| 15:21 | HOOD | UP | 109.36 | 0.09% | 0.42% | −0.26% | – | LOSS |
| 15:21 | JPM | UP | 333.43 | 0.05% | 0.21% | +0.05% | – | FLAT |
| 15:24 | SPCX | UP | 162.10 | 0.63% | 0.59% | +0.41% | 39 | LOSS |
| 15:27 | AMD | DOWN | 609.64 | 0.49% | 0.00% | +0.20% | – | FLAT |
| 15:30 | IWM | DOWN | 279.06 | 0.12% | 0.06% | +0.06% | – | FLAT |
| 15:36 | BA | UP | 190.65 | 0.06% | 0.25% | −0.03% | – | FLAT |
| 15:42 | AAPL | DOWN | 336.31 | 0.17% | 0.17% | +0.08% | – | FLAT |
| 15:51 | SPY | DOWN | 778.43 | 0.03% | 0.08% | −0.02% | – | FLAT |
| 15:51 | SLV | DOWN | 54.78 | 0.00% | 0.29% | −0.22% | – | FLAT |
| 15:54 | USO | UP | 148.20 | 0.20% | 0.08% | +0.13% | – | FLAT |
| 15:54 | MRNA | UP | 224.08 | 0.64% | 0.00% | +0.61% | 6 | WIN |
| 15:57 | ORCL | DOWN | 141.40 | 0.10% | 0.28% | −0.11% | – | FLAT |

## Suggested follow-ups
- Add an **ORB/StAdx backtest endpoint** (or preserve dated alert logs) so all three alert types
  can be compared on the same move-verification basis.
- The UP-vs-DOWN asymmetry suggests testing a **market-regime/direction filter** (only take DOWN
  signals when a broad index like SPY/QQQ is also trending down).
- Sweep target/stop and horizon to see how win-rate and expectancy shift (the guide's parameters).
