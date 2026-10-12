# LLM Alert-Review Report

How to generate the HTML dashboard that compares the **deterministic alerts the code fired**
against the **parallel LLM review verdicts** (AGREE / NEUTRAL / DISAGREE) and the **actual price
outcome** — i.e. "where did the code's alerts fail, and did the LLM catch them?"

> Companion to `ALERT-REPORT-GUIDE.md` (which measures raw alert accuracy). This one adds the
> LLM layer on top: it answers whether the LLM filter adds value.

## Prerequisite: capture the data (a live session)

The report needs **real fired alerts + their reviews**, which only happen during market hours:

1. Ollama up with the review model: `./scripts/llm-start.sh` (needs `qwen3:14b`).
2. App up with real creds: `./scripts/start.sh`.
3. `app.alerts.enabled: true` and `app.alert-review.enabled: true` (both default on).
4. Let it run through a session. Watch it live:
   ```bash
   tail -f logs/tradingautomation.log | grep -E "ALERT_FIRED|ALERT_LLM_REVIEW"
   ```
   Each fired alert → an `[ALERT_FIRED]` line, then (async) an `[ALERT_LLM_REVIEW]` line and a
   JSON sidecar under `logs/alert-reviews/`.

## Generate the report

```bash
# App still running → outcomes are verified against real bars:
python3 scripts/llm_review_report.py --date 2026-10-13

# App not running → skip move verification (confusion matrix shows "no verified outcomes"):
python3 scripts/llm_review_report.py --date 2026-10-13 --no-verify
```

Output: `reports/llm-review-report-YYYY-MM-DD.html` (self-contained, dark theme, data embedded).

Options: `--target 0.5 --stop 0.3` (move thresholds, % — same meaning as ALERT-REPORT-GUIDE),
`--log`, `--reviews`, `--out`, `--base-url`.

## What the report shows

- **Overview KPIs** — reviews, Agree/Disagree/Neutral counts, average conviction.
- **Confusion matrix** (verified rows only) — the headline:

  |              | move happened (WIN)   | move failed (LOSS/FLAT)              |
  |--------------|-----------------------|--------------------------------------|
  | LLM AGREE    | true positive         | false positive (LLM backed a loser)  |
  | LLM DISAGREE | missed a winner       | **true negative — LLM caught it** ←  |

  The highlighted cell (DISAGREE + failed) is the whole point: alerts the code fired that the
  LLM flagged, which then failed. If it's consistently populated, the LLM is filtering losers.
- **Per-strategy breakdown** — Agree/Disagree/Neutral + avg conviction + "caught" count per
  alert type (swift-trend, orb-up, orb-down, st-adx).
- **Per-stock detail** — one row per review: time, ticker, strategy, alert dir, LLM verdict,
  conviction, outcome, MFE/MAE, and the LLM's reason. Filter by verdict; sort by any column.

## How outcome is measured

Close-based proxy, same as `ALERT-REPORT-GUIDE.md` §2: from the alert's entry price, over the
post-alert bars (via `GET /api/swift-scan/backtest/{ticker}?date=&timeframe=&debug=true`):
WIN if MFE ≥ target% before MAE ≥ stop%, LOSS if stopped first, else FLAT.

> ⚠️ Honest caveats: close-based proxy (no fills/slippage/intrabar); "N/A" outcome = not
> verified (app not running at generation time, or no bars). The LLM review is **advisory** —
> it never placed or blocked an order. Reports always save under `reports/` (never `logs/`,
> which is gitignored).
