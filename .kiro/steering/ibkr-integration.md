---
inclusion: manual
---

# Interactive Brokers (IBKR) Web API Integration — Working Context

> **How to use this file:** it is a MANUAL steering doc. In a new session, pull it in with
> `#ibkr-integration` to load the full context for this work. Keep the "Progress log" and
> "Open decisions" sections updated as the work advances so any session can resume cleanly.

## Goal
Add Interactive Brokers as a SECOND broker behind the existing `BrokerClient` abstraction,
using the **IBKR Web API** (REST). **Webull stays the default and fully active** until IBKR is
tested end-to-end. Start against the **IBKR paper account**, move to live only after proven.

## Why this is clean to add (the seam already exists)
The app is broker-neutral behind `com.trading.broker.BrokerClient`. Broker selection is the
`broker.provider` property via `@ConditionalOnProperty`:
- Webull adapter beans use `@ConditionalOnProperty(name="broker.provider", havingValue="webull", matchIfMissing=true)` → Webull is the DEFAULT when the property is absent.
- IBKR adapter beans will use `havingValue="ibkr"`.
Config is already split (see the project-overview steering): `app.*` = broker-neutral domain
config (untouched by this work); `broker.<provider>.*` = per-broker. IBKR gets `broker.ibkr.*`
and a new `com.trading.ibkr` package. **No `app.*` config, strategy, indicator, risk, exit, or
alert code should change.**

## The interface to implement: `com.trading.broker.BrokerClient`
A single synchronous, REST-shaped interface returning neutral domain records. Methods to map:
- Accounts: `resolveAccountId()`, `fetchBalance(accountId)` → `AccountBalance`,
  `fetchHoldings(accountId)` → `HoldingsResult`.
- Market data: `fetchBars(symbol,count,timespan,sessions)` → `List<Candle>`,
  `fetchBarsBatch(symbols,...)` → `Map<symbol,List<Candle>>`, `fetchRecentBars(symbol,count)`,
  `fetchQuote(symbol)` → `Quote`, `fetchFillPrice(symbol,fallback)`,
  `fetchPriceSnapshot(symbol)` → `PriceSnapshot`.
- Orders: `submitOrder(accountId,OrderRequest)` → `OrderAck`,
  `fetchOrderHistory(...)`/`fetchOpenOrders(...)` → `List<OrderSummary>`.
- Health: `checkConnectivity()` → `ConnectivityStatus` (never throws).
Reference implementation to mirror in style: `com.trading.webull.WebullV3Client` (OkHttp +
Jackson, throws `BrokerException` on failures, returns neutral records). All HTTP/auth/JSON/
vocabulary translation lives INSIDE the adapter.

## IBKR Web API facts (verified from interactivebrokers.com/docs/web-api, 2026)
- Base URL (OAuth path): `https://api.ibkr.com` — no local gateway needed once authenticated.
- **Auth is heavy and session-stateful** (unlike Webull's per-request HMAC):
  - OAuth 2.0 uses a **signed JWT client assertion (RFC 7523, RSA-SHA256)** to get an access
    token (`POST /oauth2/api/v1/token`), then a bearer token, then **initialize a brokerage
    session** (`POST /v1/api/iserver/auth/ssodh/init`) and keep it alive via `/tickle`.
  - OAuth 2.0 self-service is granted to "licensed Organizations, Financial Advisors, IBrokers."
    A retail individual account typically uses **OAuth 1.0a** or the **Client Portal Gateway**
    (run a local gateway, browser login, hit `https://localhost:5000/v1/api/...`). The gateway
    path contradicts the headless-server design but is often the only retail option.
  - **AUTH METHOD IS THE GATING DECISION — see Open decisions.** Each path is a different impl.
- **Market data** `GET /iserver/marketdata/history`:
  - Keyed by numeric **`conid`**, NOT ticker. Need a symbol→conid lookup/cache (contract search)
    — new infra Webull didn't need.
  - Params are `period` + `bar` step sizes, NOT "count + timespan". Must translate the app's
    canonical `M1/M5/...` + count into IBKR period/bar.
  - Max **1000 data points** per request. Rate limit **10 req/sec, 50 req/min** (429 on excess).
  - Bars come back as `{o,c,h,l,v,t}` (t = epoch) → maps cleanly to `Candle`.
  - **No multi-symbol batch bars endpoint** → `fetchBarsBatch` must loop per symbol under the
    rate limit (universe ≈ 29 symbols; 50/min is workable but needs throttling, like
    `BarDataManager` already does for Webull).
- **Orders** `/iserver/account/{accountId}/orders`: conid-based, and placement has a
  **reply/confirmation step** (IBKR returns questions that must be answered to confirm) — more
  stateful than Webull's one-shot place. `/iserver/account/trades` for history (max 7 days).
- Accounts/positions via `/portfolio/*` and `/iserver/account/*` endpoints (conid-based).

## Plan — thin, verifiable slices (Webull untouched throughout)
1. **Skeleton + config:** new `com.trading.ibkr` package; `broker.ibkr.*` config; `IbkrClient
   implements BrokerClient` registered `@ConditionalOnProperty(havingValue="ibkr")`. Webull stays
   default. App must still boot on Webull. (Low risk — net-new, nothing wired until selected.)
2. **Auth module + `checkConnectivity()`** against PAPER — prove authentication works first.
3. **conid resolver (symbol→conid, cached) + `fetchBars`/`fetchRecentBars`** — prove market data.
4. **Quotes/snapshots + accounts/balances/positions.**
5. **Order placement** (with the reply/confirm flow) — LAST. PAUSE for explicit confirmation
   before any code that can place orders, even on paper.

## Guardrails for this work
- Webull remains `broker.provider` default (`matchIfMissing=true`); never break it.
- `app.engine.mode` is currently **LIVE** — be extremely careful; order-placing code must be
  gated and confirmed with the user before running, even against IBKR paper.
- Build + tests after each slice (`./gradlew compileJava test`); prefer a real `bootRun` for
  DI-affecting changes. Reuse `TimeframeSupport`/`BarResampler`/`BarDataManager` throttling.
- Prices in `BigDecimal`. Match `WebullV3Client` style. Feature branch off `main` (recommended),
  independent of the current stacked PRs since this is net-new `com.trading.ibkr` code.

## Open decisions (RESOLVE BEFORE CODING)
1. **Auth method** — which does the user actually have access to?
   (a) OAuth 2.0 self-service, (b) OAuth 1.0a, or (c) Client Portal Gateway (local, browser login).
   → This determines the entire auth implementation and the base URL (api.ibkr.com vs localhost:5000).
   STATUS: **unresolved** — user asked for this steering file before answering.
2. Paper base URL / approach confirmed once (1) is chosen.
3. Branch name for the IBKR work (suggest `ibkr-webapi-adapter` off `main`).

## Progress log (update each session)
- 2026-10-11: Researched IBKR API families; chose **Web API (REST)**, paper first, Webull stays
  default. Confirmed `BrokerClient` interface shape and the IBKR facts above. Created this
  steering file. **No IBKR code written yet** — blocked on the auth-method decision (Open #1).

## Key references (IBKR official docs)
- Web API intro: https://www.interactivebrokers.com/docs/web-api/introduction
- OAuth 2.0 intro: https://www.interactivebrokers.com/docs/web-api/authentication/oauth-2/introduction
- Brokerage session init: https://www.interactivebrokers.com/docs/web-api/authentication/oauth-2/authenticated-requests
- Historical market data: https://www.interactivebrokers.com/docs/web-api/v1/endpoints/market-data/historical-market-data
- Trade history: https://www.interactivebrokers.com/docs/web-api/api-reference/trading/trading-orders/get-trade-history
(Content summarised/paraphrased from IBKR docs for licensing compliance.)
