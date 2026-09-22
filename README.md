# Webull Automated Trading Bot

A production-grade Spring Boot trading bot for US equities that connects to the **Webull OpenAPI**, manages risk automatically, and runs multiple concurrent strategies across a configurable ticker watchlist.

Defaults to **paper/sandbox mode** — no real money moves until you explicitly activate live trading.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Set Environment Variables](#2-set-environment-variables)
3. [Configuration Reference](#3-configuration-reference)
4. [Build the Project](#4-build-the-project)
5. [Run in Paper Mode](#5-run-in-paper-mode)
6. [Run in Live Trading Mode](#6-run-in-live-trading-mode)
7. [Ticker Watchlist](#7-ticker-watchlist)
8. [Strategies](#8-strategies)
9. [Risk Management](#9-risk-management)
10. [Market Hours](#10-market-hours)
11. [REST API Reference](#11-rest-api-reference)
12. [Project Structure](#12-project-structure)
13. [Adding a New Strategy](#13-adding-a-new-strategy)
14. [Common Commands](#14-common-commands)
15. [Troubleshooting](#15-troubleshooting)

---

## 1. Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 17 or higher |
| Gradle | Bundled via `gradlew` wrapper — no separate install needed |
| Webull OpenAPI credentials | App Key + App Secret |

Get your API credentials from the [Webull US Open API portal](https://www.webull.com/center#openApiManagement). For sandbox/paper testing, use the shared test credentials from the [Webull developer docs](https://developer.webull.com/apis/docs/getting-started/).

---

## 2. Set Environment Variables

Secrets are never hardcoded. Export them before running:

**macOS / Linux:**
```bash
export WEBULL_APP_KEY=your_app_key_here
export WEBULL_APP_SECRET=your_app_secret_here
# Optional — only if Two-Factor Authentication is enabled on the account:
export WEBULL_ACCESS_TOKEN=your_access_token
```

**Windows (PowerShell):**
```powershell
$env:WEBULL_APP_KEY="your_app_key_here"
$env:WEBULL_APP_SECRET="your_app_secret_here"
```

**Windows (Command Prompt):**
```cmd
set WEBULL_APP_KEY=your_app_key_here
set WEBULL_APP_SECRET=your_app_secret_here
```

---

## 3. Configuration Reference

All settings live in `src/main/resources/application.yml`.

```yaml
webull:
  api:
    app-key: ${WEBULL_APP_KEY}         # from environment variable
    app-secret: ${WEBULL_APP_SECRET}   # from environment variable
    region-id: "us"
    endpoint: ""                       # blank = auto (sandbox or prod based on mode)
    access-token: ${WEBULL_ACCESS_TOKEN:}  # optional 2FA token (x-access-token); blank when 2FA off

  trading:
    mode: PAPER                        # PAPER | LIVE
    ticker: AAPL                       # fallback when tickers.txt is absent
    watchlist-path: "classpath:tickers.txt"
    warmup-bars: 700                   # historical bars fetched per ticker on startup
    ema-period: 600                    # period for the 600-EMA strategy
    order-quantity: 1                  # shares per order (all strategies)
    ema-periods: [20, 100, 200, 600]   # periods returned by GET /api/ema/{ticker}
    trading-sessions: "RTH,PRE,ATH"    # sessions in bar requests (RTH,PRE,ATH,OVN)

  strategies:
    ema600-enabled: true               # 600-EMA momentum strategy
    ema-crossover-enabled: true        # 100/20-EMA golden-cross strategy

  risk:
    max-daily-drawdown-pct: 0.10       # halt trading at 10% daily loss
    min-buying-power-usd: 100.0        # refuse BUY if cash below $100
    account-refresh-enabled: true      # refresh account after every order

  market-hours:
    core-open: "09:30"                 # regular session open (ET)
    core-close: "16:00"                # regular session close (ET)
    extended-hours-enabled: false      # set true for pre/after-hours
    pre-market-open: "04:00"           # pre-market start (ET)
    after-hours-close: "20:00"         # after-hours end (ET)

  diagnostics:
    credentials-endpoint-enabled: false  # expose GET /api/diagnostics/credentials (local debug only)

  endpoints:                           # Webull v3 API paths — override if a route changes
    account-list: "/trading/accounts/list"
    account-balance: "/trading/assets/balances/get"
    positions: "/trading/assets/positions/list"
    place-order: "/trading/orders/place"
    order-history: "/trading/orders/historical-orders/list"
    open-orders: "/trading/orders/open-orders/list"
    bars: "/market-data/stocks/bars/list"
```

### API endpoint resolution

The `endpoint` field is optional. Leave it blank and the bot picks the correct host automatically:

| Mode | Endpoint used |
|---|---|
| `PAPER` | `api.sandbox.webull.com` |
| `LIVE` | `api.webull.com` |

Set it explicitly to override for a custom proxy or non-US region (e.g. `api.sg.webull.com`).

### Paper vs live: the single `mode` switch

`webull.trading.mode` is the **only** setting that controls paper vs live. It selects
both the endpoint and where real orders go:

| `mode` | Endpoint | Orders |
|---|---|---|
| `PAPER` (default) | `api.sandbox.webull.com` | Sent to the Webull **sandbox** — visible on the sandbox terminal, **no real money** |
| `LIVE` | `api.webull.com` | Sent to **production** — **real orders, real money** |

Flip the value in `application.yml`, or override at launch without editing the file:
```bash
./gradlew bootRun --args='--webull.trading.mode=LIVE'
```

In both modes the bot fetches **real account balances** from the resolved endpoint, so
the buying-power guard and drawdown protection use actual account data.

---

## 4. Build the Project

> **Note:** This is a Gradle project. Use `./gradlew`, not `mvn`.

```bash
# Full build + tests
./gradlew clean build

# Build without tests
./gradlew clean build -x test

# Run tests only
./gradlew test
```

Test reports: `build/reports/tests/test/index.html`

---

## 5. Run in Paper Mode

Paper mode is the default (`webull.trading.mode: PAPER`). All API calls go to
`api.sandbox.webull.com` and orders are sent to the Webull **sandbox** — they appear on
the sandbox terminal with **no real money**.

```bash
./gradlew bootRun
```

Or run the JAR directly:
```bash
java -jar build/libs/tradingautomation-0.0.1-SNAPSHOT.jar
```

**What happens on startup:**
1. Account snapshot is fetched and start-of-day equity is seeded for drawdown tracking.
2. Historical 1-minute bars (700 per ticker) are fetched for every ticker in `tickers.txt`.
3. Both EMA strategies are warmed up from the historical bars.
4. A scheduler fires at the top of every minute and:
   - Checks market hours — skips if outside session.
   - Checks the daily drawdown halt flag — skips if halted.
   - Fetches the latest 1-minute bar for each ticker and dispatches it to all enabled strategies.
5. A daily reset fires at 04:00 ET to re-enable trading for the new session.

---

## 6. Run in Live Trading Mode

> ⚠️ **Warning:** Live mode places **real orders** against your Webull account with real money.

Set the single switch `webull.trading.mode` to `LIVE`. Either edit `application.yml`:
```yaml
webull:
  trading:
    mode: LIVE
```

Or override at launch without editing the file:
```bash
./gradlew bootRun --args='--webull.trading.mode=LIVE'
```

Or with the JAR:
```bash
java -jar build/libs/tradingautomation-0.0.1-SNAPSHOT.jar --webull.trading.mode=LIVE
```

That's the only setting — no separate profile or submit flag. In LIVE mode all API
calls go to `api.webull.com` and orders are submitted to production.

---

## 7. Ticker Watchlist

Edit `src/main/resources/tickers.txt` — one symbol per line. `#` comments and blank lines are ignored. Symbols are auto-uppercased.

```
# tickers.txt
AAPL
MSFT
NVDA
TSLA
AMZN
```

Every enabled strategy runs against every ticker in this file simultaneously.

**Use an external file** (no recompile needed):
```yaml
webull:
  trading:
    watchlist-path: "file:/Users/you/my-tickers.txt"
```

If the file is missing or empty, the bot falls back to the single `webull.trading.ticker` value.

---

## 8. Strategies

### 8.1 600-EMA Momentum (`ema600-enabled`)

| Setting | Value |
|---|---|
| Timeframe | 1-minute candles |
| Indicator | 600-period EMA |
| Entry | Candle open price **strictly above** the 600-EMA |
| Stop-Loss | 2% below fill price |
| Take-Profit | 5% above fill price |
| Order type | Market BUY + STOP SELL + LIMIT SELL |

### 8.2 100/20-EMA Golden Cross (`ema-crossover-enabled`)

| Setting | Value |
|---|---|
| Timeframe | 1-minute candles |
| Indicators | 20-period EMA and 100-period EMA |
| Entry | 20-EMA crosses **above** 100-EMA (golden cross) |
| Stop-Loss | Fill price **− $0.05** (fixed dollar) |
| Take-Profit | Fill price **+ $0.10** (fixed dollar) |
| Order type | Market BUY + STOP SELL + LIMIT SELL |

Both strategies share the same position tracker — a duplicate BUY for the same ticker is always blocked regardless of which strategy fires the signal.

Enable or disable either strategy in `application.yml` without touching code:
```yaml
webull:
  strategies:
    ema600-enabled: true
    ema-crossover-enabled: false   # disabled
```

---

## 9. Risk Management

All risk parameters are configurable in `application.yml` under `webull.risk`.

### Daily drawdown protection

The bot records account equity at startup as the day's baseline. After every order it recalculates the drawdown:

```
drawdown = (start_equity - current_equity) / start_equity
```

When `drawdown ≥ max-daily-drawdown-pct` (default 10%):
- All trading is **halted immediately** for the rest of the day.
- Every open position tracked by the bot is **closed with a market SELL**.
- The halt is logged at ERROR level.
- Trading **resumes automatically at 04:00 ET the next day**.

### Low buying-power guard

Before every BUY order, the bot checks available cash:
- If `buying_power < min-buying-power-usd` (default $100), the order is rejected.
- The check uses a cached account snapshot refreshed after every order.

### Account snapshot refresh

After every successful order (`account-refresh-enabled: true`), the bot:
1. Fetches a fresh balance (net liquidation, buying power).
2. Fetches open positions (count and cost basis).
3. Re-evaluates the drawdown against the current equity.

Set `account-refresh-enabled: false` to reduce API traffic in paper mode.

### Duplicate position guard

Tracked per-ticker in `PositionTracker`. If a position for `AAPL` is already open, no strategy can open another `AAPL` position until the first is closed — regardless of which strategy fires the signal.

---

## 10. Market Hours

All times are **America/New_York (ET)**. Weekends are always blocked.

| Session | Window | Controlled by |
|---|---|---|
| Core (regular) | 09:30 – 16:00 | Always active |
| Pre-market | 04:00 – 09:30 | `extended-hours-enabled: true` |
| After-hours | 16:00 – 20:00 | `extended-hours-enabled: true` |

To enable extended hours trading:
```yaml
webull:
  market-hours:
    extended-hours-enabled: true
```

All window boundaries are configurable. For example, to restrict to a tighter window:
```yaml
webull:
  market-hours:
    core-open: "09:35"    # skip first 5 minutes of volatility
    core-close: "15:55"   # exit before close auction
```

Outside trading hours, every minute tick is silently skipped and logged at DEBUG level.

---

## 11. REST API Reference

The bot exposes a REST API on `http://localhost:8080`. All endpoints return JSON.

### Account endpoints

#### `GET /api/account/summary`
Combined account metadata, live balance, and bot state in one call.

```json
{
  "accountNumber": "U12345678",
  "accountType": "MARGIN",
  "accountStatus": "ACTIVE",
  "netLiquidationValue": "24150.00",
  "buyingPower": "12000.00",
  "totalCashBalance": "12000.00",
  "totalMarketValue": "12150.00",
  "tradingHalted": false,
  "tradingAllowed": true,
  "marketSession": "CORE (09:30–16:00 ET)",
  "openPositions": 2,
  "mode": "PAPER",
  "timestamp": "2024-01-15T14:30:00Z"
}
```

#### `GET /api/account/balance`
Force-refreshes the account balance and returns full currency breakdown.

```json
{
  "totalAsset": "24150.00",
  "totalCashBalance": "12000.00",
  "totalMarketValue": "12150.00",
  "netLiquidationValue": "24150.00",
  "buyingPower": "12000.00",
  "currencyAssets": [
    {
      "currency": "USD",
      "netLiquidationValue": "24150.00",
      "cashPower": "12000.00",
      "cashBalance": "12000.00",
      "positionsMarketValue": "12150.00",
      "marginPower": "24000.00"
    }
  ],
  "mode": "PAPER",
  "timestamp": "2024-01-15T14:30:00Z"
}
```

#### `GET /api/account/status`
Bot operating state — ideal for health checks and monitoring dashboards.

```json
{
  "tradingHalted": false,
  "tradingAllowed": true,
  "marketSession": "CORE (09:30–16:00 ET)",
  "openPositions": 1,
  "mode": "PAPER",
  "endpoint": "api.sandbox.webull.com",
  "maxDrawdownPct": "0.10",
  "minBuyingPower": "100.0",
  "extendedHours": false,
  "timestamp": "2024-01-15T14:30:00Z"
}
```

---

### Positions endpoints

#### `GET /api/positions[?pageSize=50&lastId=cursor]`
All open holdings from the Webull account with full P&L detail.

```json
{
  "holdings": [
    {
      "symbol": "AAPL",
      "instrumentId": "913256135",
      "shortName": "Apple Inc",
      "qty": "10",
      "unitCost": "182.50",
      "totalCost": "1825.00",
      "lastPrice": "185.20",
      "marketValue": "1852.00",
      "unrealizedPnl": "27.00",
      "unrealizedPnlPct": "1.48%",
      "holdingProportion": "7.67%",
      "currency": "USD"
    }
  ],
  "hasNext": false,
  "count": 1,
  "timestamp": "2024-01-15T14:30:00Z"
}
```

#### `GET /api/positions/tracked`
Positions tracked **in-memory by the bot** — trades opened by a strategy that have not yet been closed by stop-loss or take-profit.

```json
{
  "positions": [
    {
      "ticker": "AAPL",
      "entryPrice": "185.20",
      "quantity": "1",
      "stopPrice": "181.50",
      "targetPrice": "194.46",
      "clientOrderId": "a1b2c3d4e5f6"
    }
  ],
  "count": 1,
  "timestamp": "2024-01-15T14:30:00Z"
}
```

> These may differ from `/api/positions` if external orders were placed or if exit fills have not yet been acknowledged.

---

### Order endpoints

These map to two distinct Webull v3 endpoints. Both accept `pageSize` (default 50)
and `lastId` (a pagination cursor / `pagination_key` from the previous response).

#### `GET /api/orders/today[?pageSize=50&lastId=cursor]`
Historical orders — backed by `/trading/orders/historical-orders/list`.
Despite the name, this returns orders from the **last 7 days by default** (the
Webull endpoint's default window), including filled, cancelled, and rejected orders.

#### `GET /api/orders/open[?pageSize=50&lastId=cursor]`
Currently resting/working orders — backed by `/trading/orders/open-orders/list`.
Includes GTC stop-loss and take-profit bracket orders that have not yet filled.

```json
{
  "orders": [
    {
      "orderId": "ord_abc123",
      "clientOrderId": "a1b2c3d4",
      "symbol": "AAPL",
      "shortName": "Apple Inc",
      "side": "BUY",
      "orderType": "MKT",
      "status": "FILLED",
      "qty": "1",
      "filledQty": "1",
      "filledPrice": "185.20",
      "limitPrice": "",
      "stopPrice": "",
      "tif": "DAY",
      "placeTime": "1705329000000",
      "lastFilledTime": "1705329001234",
      "currency": "USD"
    }
  ],
  "hasNext": false,
  "count": 1,
  "timestamp": "2024-01-15T14:30:00Z"
}
```

---

### EMA endpoint

#### `GET /api/ema/{ticker}[?timespan=M1]`
Returns the configured EMA values for a watchlist ticker, computed from fresh
bars at the requested timespan. The periods are configurable via
`webull.trading.ema-periods` (default `[20, 100, 200, 600]`).

The ticker must be present in the watchlist (`tickers.txt`), otherwise a `404` is returned.

- `timespan` — bar granularity (default `M1`). Supported: `M1, M5, M15, M30, M60, M120, M240, D, W, M, Y`.
  Example: `?timespan=M30` for a 30-minute EMA.

```json
{
  "ticker": "AAPL",
  "timespan": "M30",
  "barsUsed": 700,
  "lastClose": "185.20",
  "emaValues": {
    "20":  "184.95000000",
    "100": "183.10000000",
    "200": "181.72000000",
    "600": "178.40000000"
  },
  "periods": [20, 100, 200, 600],
  "timestamp": "2024-01-15T14:30:00Z"
}
```

Response codes:
- `200` — EMA values returned. A period needing more bars than available is reported as `"INSUFFICIENT_DATA"`.
- `400` — invalid timespan (response lists the supported values).
- `404` — ticker not in the watchlist (response includes the current watchlist).
- `503` — market-data API returned no bars (common in paper/sandbox off-hours).

> Longer timespans with high periods need a lot of history — e.g. a 30-minute
> 600-EMA needs 600 × 30-min bars. If the API returns fewer, that period reports
> `"INSUFFICIENT_DATA"`.

---

### Manual trade endpoints (testing)

Place orders by hand to test the pipeline. Both go through the same `OrderService`
the strategies use, so all guardrails apply, and both are recorded in the trade log
under strategy `MANUAL`. Simulated in PAPER mode; real orders in LIVE mode.

#### `POST /api/trade/buy/{ticker}?quantity=N`
Manual market BUY (default quantity 1). Respects the trading-halt and buying-power
guards. On success the position is registered/increased in the tracker so a later
SELL is permitted.

#### `POST /api/trade/sell/{ticker}?quantity=N`
Manual market SELL (default quantity 1). Subject to the **no-short guardrail** — the
quantity cannot exceed the tracked held quantity, so it can only flatten/reduce a
long position, never open a short.

```json
{
  "side": "BUY",
  "ticker": "AAPL",
  "quantity": 2,
  "success": true,
  "clientOrderId": "a1b2c3d4",
  "orderId": "ord_x",
  "message": "Accepted by Webull",
  "orderRequest": {
    "client_order_id": "a1b2c3d4",
    "combo_type": "NORMAL",
    "instrument_type": "EQUITY",
    "market": "US",
    "symbol": "AAPL",
    "side": "BUY",
    "order_type": "MARKET",
    "quantity": "2",
    "time_in_force": "DAY",
    "entrust_type": "QTY",
    "support_trading_session": "CORE"
  },
  "webullHttpStatus": 200,
  "webullResponse": "{\"order_id\":\"ord_x\", ...}",
  "submittedToWebull": true,
  "mode": "PAPER",
  "heldAfter": 2,
  "timestamp": "2024-01-15T09:30:00 EST"
}
```

The response shows both **what was sent** and **what Webull returned**, for easy debugging:
- `orderRequest` — the exact payload submitted (order type, prices, quantity, session).
  BUY/market-sell use `MARKET`; strategy exits use `STOP_LOSS` (+`stop_price`) and `LIMIT` (+`limit_price`).
- `webullHttpStatus` — HTTP status from Webull (`-1` = blocked by a guardrail before sending).
- `webullResponse` — the raw Webull success/failure body.
- `submittedToWebull` — `true` when the order reached Webull (the sandbox in PAPER mode, production in LIVE).

Response codes:
- `200` — order accepted by Webull.
- `422` — guardrail rejection (`SHORT_BLOCKED`, `TRADING_HALTED`, `INSUFFICIENT_BUYING_POWER`) or a Webull rejection.
- `400` — invalid input (non-positive quantity).

```bash
# Paper-mode test flow
curl -X POST "http://localhost:8080/api/trade/buy/AAPL?quantity=2"    # heldAfter: 2
curl -X POST "http://localhost:8080/api/trade/sell/AAPL?quantity=1"   # heldAfter: 1
curl -X POST "http://localhost:8080/api/trade/sell/AAPL?quantity=5"   # 422 SHORT_BLOCKED (only 1 held)
```

---

### History endpoint

#### `GET /api/history/{ticker}[?count=200&timespan=M1]`
Returns all fetched historical bars (OHLCV) for a stock. Backed by the
Webull v3 Data API (`POST /market-data/stocks/bars/list`). Unlike the EMA endpoint,
the ticker does **not** need to be in the watchlist — any US stock symbol works.

- `count` — number of bars (default = `webull.trading.warmup-bars`; M1 max 1650)
- `timespan` — bar granularity (default `M1`). Supported: `M1, M5, M15, M30, M60, M120, M240, D, W, M, Y`.

```json
{
  "ticker": "AAPL",
  "timespan": "M1",
  "count": 200,
  "bars": [
    {
      "timestamp": "2024-01-15T14:30:00Z",
      "open": "185.10",
      "high": "185.40",
      "low": "184.90",
      "close": "185.20",
      "volume": 12345
    }
  ],
  "timestamp": "2024-01-15T14:31:00Z"
}
```

Response codes:
- `200` — bars returned (oldest-first).
- `400` — invalid timespan (response lists the supported values).
- `502` — Webull error; includes a `webullError` block with the failure detail.

---

### Health endpoints

#### `GET /api/health`
Liveness probe. Returns `200` as long as the app is running. Makes no external
calls — safe to poll frequently from a load balancer.

```json
{ "status": "UP", "application": "tradingautomation", "mode": "PAPER", "timestamp": "..." }
```

#### `GET /api/health/webull`
Actively verifies connectivity **and** authentication against the Webull **v3**
API by calling `GET /trading/accounts/list` through the direct signed-HTTP client
(`WebullV3Client`, HMAC-SHA1 signing verified against the Webull docs' test vector).

- `200 UP` — call succeeded (`accountsFound` and the raw v3 `response` are included)
- `503 DOWN` — failed; includes `httpStatus` and `rawResponse` showing exactly what Webull returned

```json
{
  "status": "UP",
  "webullReachable": true,
  "authenticated": true,
  "accountsFound": 1,
  "mode": "PAPER",
  "endpoint": "api.sandbox.webull.com",
  "path": "/trading/accounts/list",
  "apiVersion": "v3",
  "httpStatus": 200,
  "latencyMs": 142,
  "timestamp": "..."
}
```

> This issues a real authenticated request — poll it sparingly to respect Webull
> rate limits. Use `/api/health` for frequent liveness checks.

---

### Diagnostics endpoint

#### `GET /api/diagnostics/credentials`
Verifies which Webull credentials the app loaded, **without exposing the full secret**.
Disabled by default — returns `404` unless enabled via
`webull.diagnostics.credentials-endpoint-enabled: true`.

```json
{
  "appKey": "us.6b1aafc70cd3cb426f1992bd879a101c",
  "appKeyLength": 35,
  "appSecretMasked": "b2e3****************a27b",
  "appSecretLength": 32,
  "appSecretSha256": "9f2c8ab3...e41d",
  "regionId": "us",
  "endpoint": "api.sandbox.webull.com",
  "mode": "PAPER",
  "timestamp": "..."
}
```

Verify an exact match by comparing the fingerprint locally:
```bash
echo -n "your_secret_here" | shasum -a 256
```
A matching `appSecretSha256` proves the exact value was loaded — no plaintext leaves the process.
**Local debugging only — never enable in a shared or production environment.**

---

### Webull error surfacing

When any endpoint's underlying Webull call fails, the response returns `502 Bad Gateway`
(or `503` for health) with a `webullError` block containing the real Webull details
instead of a generic message:

```json
{
  "error": "Failed to fetch orders",
  "mode": "PAPER",
  "endpoint": "api.sandbox.webull.com",
  "webullError": {
    "kind": "HTTP_SERVER_ERROR",
    "httpStatus": 404,
    "webullErrorCode": "UnknownServerError",
    "webullErrorMsg": "...",
    "webullRequestId": "a45b80c2-a11a-42aa-8ea8-923ca5df64f2"
  },
  "timestamp": "..."
}
```

The `kind` field classifies the failure: `HTTP_SERVER_ERROR` (includes `httpStatus`),
`SERVER_ERROR`, `CLIENT_ERROR`, or `UNKNOWN`. The `webullRequestId` is useful when
raising an issue with Webull support.

Applies to: `/api/account/summary`, `/api/account/balance`, `/api/positions`,
`/api/orders/today`, `/api/orders/open`, `/api/ema/{ticker}`, and `/api/health/webull`.

---

### Quick API test (paper mode)

```bash
# Check bot status
curl http://localhost:8080/api/account/status | python3 -m json.tool

# Get account balance
curl http://localhost:8080/api/account/balance | python3 -m json.tool

# View bot-tracked positions
curl http://localhost:8080/api/positions/tracked | python3 -m json.tool

# Today's orders
curl http://localhost:8080/api/orders/today | python3 -m json.tool

# Open GTC orders (stop-loss + take-profit)
curl http://localhost:8080/api/orders/open | python3 -m json.tool

# EMA values for a watchlist ticker (20/100/200/600 by default, 1-minute bars)
curl http://localhost:8080/api/ema/AAPL | python3 -m json.tool

# 30-minute EMA values
curl "http://localhost:8080/api/ema/AAPL?timespan=M30" | python3 -m json.tool

# Full historical bars for any stock (not restricted to the watchlist)
curl "http://localhost:8080/api/history/AAPL?count=200&timespan=M30" | python3 -m json.tool

# Liveness
curl http://localhost:8080/api/health | python3 -m json.tool

# Active Webull connectivity + auth probe
curl http://localhost:8080/api/health/webull | python3 -m json.tool
```

---

## 12. Project Structure

```
src/
├── main/
│   ├── java/com/trading/
│   │   ├── TradingAutomationApplication.java     # Spring Boot entry point
│   │   │
│   │   ├── api/                                  # REST controllers
│   │   │   ├── AccountController.java            # /api/account/*
│   │   │   ├── PositionsController.java          # /api/positions/*
│   │   │   ├── OrderHistoryController.java       # /api/orders/*
│   │   │   ├── EmaController.java                # /api/ema/{ticker}
│   │   │   ├── HistoryController.java            # /api/history/{ticker}
│   │   │   ├── PriceController.java              # /api/price/{ticker}
│   │   │   ├── ManualTradeController.java        # /api/trade/buy|sell/{ticker}
│   │   │   ├── TradeLogController.java           # /api/trades
│   │   │   ├── HealthController.java             # /api/health, /api/health/webull
│   │   │   ├── CredentialsDiagnosticsController.java  # /api/diagnostics/credentials
│   │   │   └── WebullErrors.java                 # shared Webull error → JSON helper
│   │   │
│   │   ├── webull/                               # Direct Webull v3 client (no SDK)
│   │   │   ├── WebullSigner.java                 # HMAC-SHA1 v3 signing (verified vs docs vector)
│   │   │   └── WebullV3Client.java               # OkHttp signed client for /trading/* and /market-data/*
│   │   │
│   │   ├── config/
│   │   │   ├── WebullProperties.java             # All config (api/trading/strategies/risk/marketHours/endpoints)
│   │   │   ├── CredentialsValidator.java         # Fail-fast if app key/secret missing
│   │   │   └── WatchlistLoader.java              # Reads tickers.txt at startup
│   │   │
│   │   ├── model/
│   │   │   └── Candle.java                       # Immutable OHLCV record (BigDecimal)
│   │   │
│   │   ├── indicator/
│   │   │   └── EmaCalculator.java                # SMA-seeded EMA + incremental update (DECIMAL128)
│   │   │
│   │   ├── state/
│   │   │   └── PositionTracker.java              # Thread-safe per-ticker position registry
│   │   │
│   │   ├── strategy/
│   │   │   ├── TradingStrategy.java              # Pluggable interface (warmUp + onCandle)
│   │   │   ├── EmaStrategyService.java           # 600-EMA momentum strategy
│   │   │   └── EmaCrossoverStrategyService.java  # 100/20-EMA golden-cross strategy
│   │   │
│   │   └── service/
│   │       ├── OrderService.java                 # All order execution (BUY/STOP-SELL/LIMIT-SELL)
│   │       ├── AccountService.java               # Account snapshot: balance + positions
│   │       ├── RiskManager.java                  # Daily drawdown halt + close-all on breach
│   │       ├── MarketHoursGuard.java             # Core + extended hours enforcement (ET)
│   │       ├── MarketDataService.java            # v3 /market-data/bars: historical + latest bar
│   │       └── TradingOrchestrator.java          # Startup warmup + per-minute scheduler
│   │
│   └── resources/
│       ├── application.yml                       # All configuration
│       └── tickers.txt                           # Ticker watchlist
│
└── test/
    └── java/com/trading/
        ├── indicator/
        │   └── EmaCalculatorTest.java            # EMA math tests
        └── webull/
            └── WebullSignerTest.java             # HMAC-SHA1 signer verified vs docs vector
```

---

## 13. Adding a New Strategy

1. Create a `@Service` class implementing `TradingStrategy`:

```java
package com.trading.strategy;

import com.trading.model.Candle;
import com.trading.service.OrderService;
import com.trading.state.PositionTracker;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
public class MyStrategy implements TradingStrategy {

    private final OrderService orderService;
    private final PositionTracker positionTracker;

    public MyStrategy(OrderService orderService, PositionTracker positionTracker) {
        this.orderService = orderService;
        this.positionTracker = positionTracker;
    }

    @Override
    public String name() { return "My Strategy"; }

    @Override
    public void warmUp(String ticker, List<BigDecimal> historicalCloses) {
        // seed indicators here
    }

    @Override
    public void onCandle(Candle candle) {
        // signal logic here — call orderService.placeMarketBuy() to enter
    }
}
```

2. Add an enable flag to `WebullProperties.Strategies`:
```java
@DefaultValue("true") boolean myStrategyEnabled
```

3. Add one `instanceof` condition to `TradingOrchestrator.isEnabled()`:
```java
if (strategy instanceof MyStrategy) {
    return props.strategies().myStrategyEnabled();
}
```

4. Add the flag to `application.yml`:
```yaml
webull:
  strategies:
    my-strategy-enabled: true
```

The orchestrator will automatically warm it up and dispatch candles to it — no other wiring needed.

---

## 14. Common Commands

| Goal | Command |
|---|---|
| Full build + tests | `./gradlew clean build` |
| Build, skip tests | `./gradlew clean build -x test` |
| Run tests only | `./gradlew test` |
| Run (paper mode) | `./gradlew bootRun` |
| Run (live mode) | `./gradlew bootRun --args='--webull.trading.mode=LIVE'` |
| List available tasks | `./gradlew tasks` |

---

## 15. Troubleshooting

**`Task 'install' not found`**
This is a Gradle project, not Maven. Use `./gradlew clean build` instead of `mvn clean install`.

**`Warm-up aborted — only X bars returned`**
The API returned fewer bars than required. Common causes: market closed, invalid ticker symbol, or wrong credentials. Check `WEBULL_APP_KEY` and `WEBULL_APP_SECRET`.

**`java.lang.ClassNotFoundException: javax.xml.bind.DatatypeConverter`**
The Webull SDK requires the JAXB runtime (removed in Java 11+). The `build.gradle` already includes the fix:
```groovy
runtimeOnly 'javax.xml.bind:jaxb-api:2.3.1'
runtimeOnly 'com.sun.xml.bind:jaxb-impl:2.3.9'
```
If you see this error, confirm you are using `./gradlew bootRun` (not running the class directly from an IDE without the runtime classpath).

**`Could not resolve account ID`**
Only relevant in LIVE mode. Confirm your Webull account has OpenAPI access approved.

**`[RiskManager] DAILY DRAWDOWN LIMIT BREACHED`**
The bot has halted trading and closed all positions. It will automatically re-enable at 04:00 ET. Adjust `max-daily-drawdown-pct` in `application.yml` if needed.

**Bot skips every tick with "Outside trading hours"**
Check `market-hours` config and your server timezone. The guard uses `America/New_York` internally regardless of server timezone. If you're testing outside market hours, you can temporarily widen the window in `application.yml`.

**Gradle deprecation warnings about Gradle 10**
Informational only — does not affect the build.
