# Alert Report — Friday, October 9, 2026 (Stock-wise)

Source: `logs/tradingautomation.log` · Session window 09:42–15:55 ET
Two alert engines are combined per stock:

- **ORB** (`AlertEngine`) — Opening Range Breakout. Fires `orb-up` / `orb-down` when price breaks the opening-range high/low for the day. The "why" is the breakout itself.
- **SwiftTrend** (`SwiftTrendAlert`) — trend-continuation signal. The "why" is captured by four metrics:
  - **flip** — bars since the trend last flipped direction (lower = fresher trend)
  - **travel** — distance price has moved since the flip, in ×ATR (higher = stronger move)
  - **sep** — separation of price from the trend baseline, in ×ATR (higher = more extended)
  - **strong candles** — count of strong-bodied candles out of the last 5 (momentum quality)

> Note: SwiftTrend's 09:42–09:48 signals were emitted repeatedly across three app restarts (PIDs 47445 → 49894 → 50268). Duplicate identical signals are collapsed to their first occurrence below and marked *(repeated during startup)*.

Legend: 🟢 UP · 🔴 DOWN

---

## Conviction Ranking

Each stock is scored 0–10 for how strong and reliable its directional signal was during the day. Higher = more actionable.

**Scoring model**
| Component | Points | Rule |
|-----------|--------|------|
| Engine agreement | +3 | Both ORB and SwiftTrend fired the same net direction |
| Directional consistency | +2 / +1 / 0 | No reversals / one reversal / multiple flips |
| Flip freshness | +2 / +1 / 0 | Best SwiftTrend flip ≤5 bars / ≤20 bars / older (no SwiftTrend = 0) |
| Momentum | +2 / +1 / 0 | Best strong-candle count 4–5 / 3 / ≤2 (no SwiftTrend = 0) |
| Persistence | +1 | ORB re-confirmed the same direction ≥3 times |

### Ranked (strongest first)

| Rank | Stock | Net Dir | Score | Agree | Consistent | Flip | Momentum | Persist | Notes |
|------|-------|---------|:-----:|:-----:|:----------:|:----:|:--------:|:-------:|-------|
| 1 | **AMD** | 🔴 DOWN | **9** | +3 | +2 | +2 | +2 | 0 | Both engines down; fresh flip (3 bars), 4/5 strong candles |
| 2 | **NVDA** | 🔴 DOWN | **8** | +3 | +2 | +1 | +1 | +1 | Both down; 5 ORB re-confirms, no reversal |
| 3 | **USO** | 🟢 UP | **7** | +3 | +2 | +1 | 0 | +1 | Both up; persistent (5 ORB), fresh 7-bar flip late |
| 4 | **MU** | 🔴 DOWN | **7** | +3 | +2 | +1 | +1 | 0 | Both down; 3/5 strong, consistent |
| 5 | **SPCX** | 🔴 DOWN | **7** | +3 | +2 | +1 | 0 | +1 | Both down; 3 ORB re-confirms |
| 6 | **QQQ** | 🔴 DOWN | **7** | +3 | +2 | +2 | +1 | 0 | Both down; very fresh flip (2 bars), 3/5 strong |
| 7 | **AMZN** | 🟢 UP | **6** | +3 | +2 | 0 | 0 | +1 | Both up; 3 ORB re-confirms, trend not fresh |
| 8 | **COIN** | 🟢 UP | **6** | +3 | +2 | +2 | +2 | 0 (−1) | Fresh 2-bar flip, 4/5 strong — only 2 alerts total |
| 9 | **HOOD** | 🟢 UP | **6** | 0 | +2 | 0 | 0 | +1 | ORB only but 7 up re-confirms, zero reversals |
| 10 | **MSFT** | 🟢 UP | **6** | +3 | +2 | 0 | 0 | +1 | Both up; very extended trend (travel 4.63×ATR) |
| 11 | **BA** | 🟢 UP | **6** | +3 | +2 | 0 | 0 | +1 | Both up; 4 ORB re-confirms |
| 12 | **SPY** | 🟢 UP | **6** | +3 | +2 | 0 | 0 | 0 (+1) | Both up; index, 2 ORB re-confirms |
| 13 | **ORCL** | 🟢 UP | **6** | +3 | +2 | +1 | 0 | 0 | Both up; clean, few alerts |
| 14 | **UVXY** | 🔴 DOWN | **4** | 0 | 0 | +1 | +1 | +1 | ORB down persistent but SwiftTrend flipped up midday |
| 15 | **DELL** | 🔴/🟢 | **3** | 0 | 0 | +1 | 0 | +1 | ORB down early, SwiftTrend flipped up 14:14 — mixed |
| 16 | **GS** | 🟢 UP | **3** | — | +1 | +1 | 0 | +1 | Reversed down→up, then held up |
| 17 | **JPM** | 🟢 UP | **3** | — | +1 | 0 | 0 | +1 | Reversed down→up, SwiftTrend confirmed late |
| 18 | **TSLA** | 🟢/🔴 | **3** | — | +1 | 0 | 0 | +1 | Up most of day, reversed down 11:33 |
| 19 | **CVX** | 🟢/🔴 | **2** | — | +1 | 0 | +1 | 0 | Up early, SwiftTrend reversed down 14:54 |
| 20 | **PLTR** | 🟢 UP | **2** | — | +1 | 0 | 0 | 0 | Reversed down→up |
| 21 | **GOOGL** | 🟢/🔴 | **2** | — | +1 | 0 | 0 | 0 | Up then SwiftTrend reversed down |
| 22 | **RKLB** | 🔴/🟢 | **2** | — | +1 | +2 | 0 | 0 | Down early, flipped up 14:04 (fresh) |
| 23 | **META** | 🟢/🔴 | **1** | — | +1 | 0 | 0 | 0 | Up breakout then SwiftTrend down |
| — | AAPL | 🟢 UP | 5 | +3 | +2 | 0 | +1 | 0 | Both up, 3/5 strong |
| — | GLD | 🟢 UP | 5 | +3 | +2 | 0 | 0 | 0 | Both up, clean |
| — | GOOGL/MRNA/IWM/NFLX/SLV | mixed | — | — | — | — | — | — | Low alert count, see detail |

> Reversal names (score ≤4) scored low on purpose — they whipsawed and are higher-risk. The cleanest, highest-conviction trades of the day were **AMD / NVDA / MU / QQQ / SPCX short** and **USO / AMZN / BA / MSFT / HOOD long**.

---

## AAPL
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:43 | ORB | 🟢 UP | Opening-range breakout up |
| 10:58 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 11:23 | SwiftTrend | 🟢 UP | flip 20 bars, travel 2.21×ATR, sep 3.24×ATR, 3/5 strong |

## AMD
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:45 | SwiftTrend | 🔴 DOWN | flip 3 bars (fresh), travel 2.84×ATR, sep 3.65×ATR, 4/5 strong *(repeated during startup)* |
| 09:53 | ORB | 🔴 DOWN | Opening-range breakout down |
| 10:18 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 10:43 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 11:04 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 11:13 | ORB | 🔴 DOWN | Breakout down (re-trigger) |

## AMZN
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:42 | SwiftTrend | 🟢 UP | flip 21 bars, travel 2.80×ATR, sep 3.59×ATR, 2/5 strong |
| 10:03 | SwiftTrend | 🟢 UP | flip 25 bars, travel 2.39×ATR, sep 2.88×ATR, 1/5 strong |
| 10:13 | ORB | 🟢 UP | Opening-range breakout up |
| 10:23 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 10:43 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 15:24 | SwiftTrend | 🟢 UP | flip 15 bars, travel 2.38×ATR, sep 3.56×ATR, 1/5 strong |

## BA
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:53 | ORB | 🟢 UP | Opening-range breakout up |
| 10:58 | SwiftTrend | 🟢 UP | flip 15 bars, travel 2.28×ATR, sep 3.27×ATR, 1/5 strong |
| 12:33 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 13:09 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:54 | ORB | 🟢 UP | Breakout up (re-trigger) |

## COIN
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:23 | SwiftTrend | 🟢 UP | flip 2 bars (fresh), travel 2.08×ATR, sep 3.63×ATR, 4/5 strong |
| 10:13 | ORB | 🟢 UP | Opening-range breakout up |

## CVX
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:47 | SwiftTrend | 🟢 UP | flip 29 bars, travel 2.77×ATR, sep 3.48×ATR, 1/5 strong *(repeated during startup)* |
| 10:23 | ORB | 🟢 UP | Opening-range breakout up |
| 10:38 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:04 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:54 | SwiftTrend | 🔴 DOWN | flip 18 bars, travel 2.66×ATR, sep 3.16×ATR, 3/5 strong — **reversed to down** |

## DELL
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:58 | ORB | 🔴 DOWN | Opening-range breakout down |
| 10:18 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 10:38 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 10:53 | SwiftTrend | 🔴 DOWN | flip 13 bars, travel 2.29×ATR, sep 3.23×ATR, 2/5 strong |
| 11:38 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 14:14 | SwiftTrend | 🟢 UP | flip 15 bars, travel 2.00×ATR, sep 3.01×ATR, 1/5 strong — **reversed to up** |

## GLD
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 12:08 | ORB | 🟢 UP | Opening-range breakout up |
| 13:09 | SwiftTrend | 🟢 UP | flip 44 bars, travel 2.14×ATR, sep 3.14×ATR, 1/5 strong |
| 13:24 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 13:44 | ORB | 🟢 UP | Breakout up (re-trigger) |

## GOOGL
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 11:53 | SwiftTrend | 🟢 UP | flip 49 bars, travel 3.22×ATR, sep 3.47×ATR, 2/5 strong |
| 12:13 | ORB | 🟢 UP | Opening-range breakout up |
| 12:48 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:19 | SwiftTrend | 🔴 DOWN | flip 12 bars, travel 2.08×ATR, sep 2.97×ATR, 2/5 strong — **reversed to down** |

## GS
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:08 | ORB | 🔴 DOWN | Opening-range breakout down |
| 11:08 | ORB | 🟢 UP | Breakout up — **reversed to up** |
| 11:18 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 11:58 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 12:18 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 12:54 | SwiftTrend | 🟢 UP | flip 21 bars, travel 2.01×ATR, sep 2.96×ATR, 2/5 strong |

## HOOD
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:28 | ORB | 🟢 UP | Opening-range breakout up |
| 11:04 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 11:23 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 12:38 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 13:59 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:54 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 15:09 | ORB | 🟢 UP | Breakout up (re-trigger) |

## IWM
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 12:38 | ORB | 🟢 UP | Opening-range breakout up |
| 12:54 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 13:24 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 13:49 | SwiftTrend | 🟢 UP | flip 44 bars, travel 2.67×ATR, sep 3.26×ATR, 1/5 strong |

## JPM
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:08 | ORB | 🔴 DOWN | Opening-range breakout down |
| 11:23 | ORB | 🟢 UP | Breakout up — **reversed to up** |
| 12:08 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:34 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 15:29 | SwiftTrend | 🟢 UP | flip 40 bars, travel 2.17×ATR, sep 2.94×ATR, 1/5 strong |

## META
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 12:48 | ORB | 🟢 UP | Opening-range breakout up |
| 14:39 | SwiftTrend | 🔴 DOWN | flip 12 bars, travel 2.03×ATR, sep 3.20×ATR, 2/5 strong — **reversed to down** |

## MRNA
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:45 | SwiftTrend | 🟢 UP | flip 37 bars, travel 3.18×ATR, sep 2.60×ATR, 2/5 strong *(repeated during startup)* |
| 10:03 | ORB | 🟢 UP | Opening-range breakout up |
| 11:13 | ORB | 🟢 UP | Breakout up (re-trigger) |

## MSFT
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:33 | ORB | 🟢 UP | Opening-range breakout up |
| 10:43 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 10:53 | SwiftTrend | 🟢 UP | flip 81 bars, travel 4.63×ATR, sep 3.19×ATR, 1/5 strong (very extended trend) |

## MU
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:45 | SwiftTrend | 🔴 DOWN | flip 12 bars, travel 2.79×ATR, sep 3.12×ATR, 3/5 strong *(repeated during startup)* |
| 10:18 | ORB | 🔴 DOWN | Opening-range breakout down |
| 15:44 | SwiftTrend | 🔴 DOWN | flip 8 bars, travel 2.14×ATR, sep 3.57×ATR, 1/5 strong |

## NFLX
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 13:04 | SwiftTrend | 🔴 DOWN | flip 18 bars, travel 2.45×ATR, sep 3.39×ATR, 3/5 strong |
| 15:55 | ORB | 🔴 DOWN | Opening-range breakout down |

## NVDA
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:42 | SwiftTrend | 🔴 DOWN | flip 31 bars, travel 3.41×ATR, sep 2.48×ATR, 1/5 strong *(repeated during startup)* |
| 10:03 | ORB | 🔴 DOWN | Opening-range breakout down |
| 10:28 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 10:48 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 11:13 | ORB | 🔴 DOWN | Breakout down (re-trigger) |

## ORCL
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:28 | ORB | 🟢 UP | Opening-range breakout up |
| 10:38 | SwiftTrend | 🟢 UP | flip 12 bars, travel 2.25×ATR, sep 3.24×ATR, 1/5 strong |

## PLTR
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:53 | ORB | 🔴 DOWN | Opening-range breakout down |
| 11:28 | ORB | 🟢 UP | Breakout up — **reversed to up** |
| 11:58 | SwiftTrend | 🟢 UP | flip 7 bars (fresh), travel 2.20×ATR, sep 3.20×ATR, 1/5 strong |

## QQQ
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:45 | SwiftTrend | 🔴 DOWN | flip 2 bars (fresh), travel 2.28×ATR, sep 3.81×ATR, 3/5 strong *(repeated during startup)* |
| 09:58 | ORB | 🔴 DOWN | Opening-range breakout down |

## RKLB
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:42 | SwiftTrend | 🔴 DOWN | flip 30 bars, travel 2.95×ATR, sep 2.40×ATR, 2/5 strong *(repeated during startup)* |
| 14:04 | SwiftTrend | 🟢 UP | flip 2 bars (fresh), travel 2.03×ATR, sep 2.88×ATR, 2/5 strong — **reversed to up** |

## SLV
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:03 | ORB | 🟢 UP | Opening-range breakout up |

## SPCX
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:53 | SwiftTrend | 🔴 DOWN | flip 17 bars, travel 2.70×ATR, sep 2.58×ATR, 2/5 strong |
| 09:58 | ORB | 🔴 DOWN | Opening-range breakout down |
| 11:04 | ORB | 🔴 DOWN | Breakout down (re-trigger) |

## SPY
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:58 | ORB | 🟢 UP | Opening-range breakout up |
| 11:18 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 12:54 | SwiftTrend | 🟢 UP | flip 22 bars, travel 2.86×ATR, sep 3.33×ATR, 1/5 strong |

## TSLA
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 09:42 | SwiftTrend | 🟢 UP | flip 67 bars, travel 3.73×ATR, sep 2.94×ATR, 1/5 strong *(repeated during startup, travel grew to 4.51×ATR)* |
| 09:53 | ORB | 🟢 UP | Opening-range breakout up |
| 10:53 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 11:33 | ORB | 🔴 DOWN | Breakout down — **reversed to down** |

## USO
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:03 | ORB | 🟢 UP | Opening-range breakout up |
| 10:43 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 11:04 | SwiftTrend | 🟢 UP | flip 13 bars, travel 2.39×ATR, sep 3.49×ATR, 1/5 strong |
| 12:33 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 13:29 | ORB | 🟢 UP | Breakout up (re-trigger) |
| 14:09 | SwiftTrend | 🟢 UP | flip 7 bars (fresh), travel 2.49×ATR, sep 3.55×ATR, 2/5 strong |
| 15:44 | ORB | 🟢 UP | Breakout up (re-trigger) |

## UVXY
| Time | Engine | Dir | Why |
|------|--------|-----|-----|
| 10:18 | ORB | 🔴 DOWN | Opening-range breakout down |
| 10:38 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 11:18 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 12:03 | ORB | 🔴 DOWN | Breakout down (re-trigger) |
| 12:23 | SwiftTrend | 🔴 DOWN | flip 24 bars, travel 2.33×ATR, sep 3.41×ATR, 3/5 strong |
| 13:49 | SwiftTrend | 🟢 UP | flip 9 bars, travel 2.11×ATR, sep 3.12×ATR, 1/5 strong — **reversed to up** |
| 15:24 | ORB | 🔴 DOWN | Breakout down (re-trigger) |

---

## Reading the "why"

- **ORB alerts** mean price broke the day's opening range in that direction. Repeat lines are the same breakout re-confirming on later 5-minute bars.
- **SwiftTrend alerts** mean a trend-continuation setup qualified. Interpret the metrics together:
  - **Fresh flip (low bars) + high strong-candle count** = early, high-momentum entry (e.g. AMD 09:45 flip 3 bars 4/5 strong, COIN 10:23 flip 2 bars 4/5 strong).
  - **Old flip + high travel/sep** = mature, extended trend that may be late (e.g. MSFT 10:53 flip 81 bars travel 4.63×ATR).
- **Reversals** (flagged **reversed**): CVX, DELL, GOOGL, GS, JPM, META, PLTR, RKLB, TSLA, UVXY all changed direction during the day — treat these as chop/whipsaw risk.
- **Agreement across engines** (both ORB and SwiftTrend same direction) is the strongest conviction: AMD down, NVDA down, MU down, SPCX down, QQQ down on the short side; TSLA, AMZN, USO, MSFT, GS up on the long side.
