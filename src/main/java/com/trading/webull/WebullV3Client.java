package com.trading.webull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.broker.BrokerClient;
import com.trading.broker.BrokerException;
import com.trading.broker.BrokerResponse;
import com.trading.broker.model.Quote;
import com.trading.model.Candle;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Direct HTTP client for the Webull OpenAPI <b>v3</b> endpoints.
 *
 * <p>Built because the bundled {@code webull-java-sdk} 0.2.17 targets the legacy
 * {@code v1} API (paths like {@code /app/subscriptions/list}) which the current
 * sandbox no longer serves — producing {@code 404 UnknownServerError}. This client
 * calls the current {@code v3} routes (e.g. {@code /trading/accounts/list}) and
 * signs each request with {@link WebullSigner} following the official
 * <a href="https://developer.webull.com/apis/docs/authentication/signature">
 * authentication spec</a>.</p>
 *
 * <h2>Headers sent</h2>
 * {@code x-app-key}, {@code x-timestamp}, {@code x-signature-algorithm=HMAC-SHA1},
 * {@code x-signature-version=1.0}, {@code x-signature-nonce}, {@code x-version=v3},
 * {@code x-signature}, and (when configured) {@code x-access-token}.
 * The app secret is never sent — it is used only to compute the signature.
 */
@Component
@ConditionalOnProperty(name = "broker.provider", havingValue = "webull", matchIfMissing = true)
public class WebullV3Client implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(WebullV3Client.class);

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final MediaType JSON = MediaType.parse("application/json");

    private final WebullApiProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebullV3Client(WebullApiProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(30))
                .build();
    }

    // -----------------------------------------------------------------------
    // Public request methods — all return the broker-neutral BrokerResponse
    // -----------------------------------------------------------------------

    /**
     * Sends a signed GET request to a v3 path.
     *
     * @param path        request path, e.g. {@code /trading/accounts/list}
     * @param queryParams query parameters (may be null/empty)
     */
    public BrokerResponse get(String path, Map<String, String> queryParams) {
        return execute("GET", path, queryParams, null);
    }

    /**
     * Sends a signed POST request with a JSON body to a v3 path.
     *
     * @param path        request path
     * @param queryParams query parameters (may be null/empty)
     * @param bodyObject  object serialised to compact JSON as the request body
     */
    public BrokerResponse post(String path, Map<String, String> queryParams, Object bodyObject) {
        String bodyJson = serializeCompact(bodyObject);
        return execute("POST", path, queryParams, bodyJson);
    }

    // -----------------------------------------------------------------------
    // Typed helpers for the specific v3 endpoints the bot uses
    // -----------------------------------------------------------------------

    /** GET account list — {@code /trading/accounts/list}. */
    public BrokerResponse accountList() {
        return get(props.endpoints().accountList(), null);
    }

    /** Cached first account ID. */
    private volatile String cachedAccountId = null;

    /**
     * Resolves and caches the first account ID from {@code /trading/accounts/list}.
     * The v3 response is an array of account objects each with an {@code account_id}.
     *
     * @return the account ID, or {@code null} if the call failed or returned none
     */
    /** Account classes/labels that are NOT equity (stock) accounts. */
    private static final java.util.Set<String> NON_EQUITY = java.util.Set.of(
            "CRYPTO", "FUTURES", "EVENTS_CASH");

    /**
     * Returns true when the account is the MARGIN <em>equity</em> account.
     *
     * <p>IMPORTANT: {@code account_type=MARGIN} alone is NOT sufficient — Webull
     * marks the FUTURES account as {@code account_type=MARGIN} too. We must match
     * on the equity margin class/label ({@code INDIVIDUAL_MARGIN} / "Individual
     * Margin") AND confirm it is an equity account (not futures/crypto/events).
     * Selecting the futures account for a stock order returns
     * {@code OPENAPI_TRADE_WEBULL_FUTURES_ACCOUNT_STOCK_ERROR}.</p>
     */
    private static boolean isMargin(JsonNode acct) {
        if (!isEquity(acct)) return false;
        String clazz = text(acct, "account_class");
        String label = text(acct, "account_label");
        return (clazz != null && clazz.equalsIgnoreCase("INDIVIDUAL_MARGIN"))
                || (label != null && label.equalsIgnoreCase("Individual Margin"));
    }

    /** Returns true when the account is an equity (stock) account — not crypto/futures/events. */
    private static boolean isEquity(JsonNode acct) {
        String clazz = text(acct, "account_class");
        String label = text(acct, "account_label");
        boolean nonEquity = (clazz != null && NON_EQUITY.contains(clazz.toUpperCase()))
                || (label != null && (label.equalsIgnoreCase("Crypto")
                                    || label.equalsIgnoreCase("Futures")
                                    || label.equalsIgnoreCase("Events Cash")));
        return !nonEquity && acct.get("account_id") != null;
    }

    @Override
    public String resolveAccountId() {
        if (cachedAccountId != null) return cachedAccountId;

        // 1. Explicit override from config wins.
        String configured = props.api().accountId();
        if (configured != null && !configured.isBlank()) {
            cachedAccountId = configured.trim();
            log.info("[WebullV3Client] Using configured accountId={}", cachedAccountId);
            return cachedAccountId;
        }

        BrokerResponse resp = accountList();
        if (!resp.success() || resp.body() == null) {
            log.warn("[WebullV3Client] accountList failed: status={} body={}",
                    resp.statusCode(), resp.rawBody());
            return null;
        }
        JsonNode arr = resp.body();
        if (!arr.isArray() || arr.size() == 0) {
            log.warn("[WebullV3Client] accountList returned no accounts: {}", resp.rawBody());
            return null;
        }

        // 2. Prefer the MARGIN account for equity trading. Fall back to any equity
        //    (non-crypto/futures/events) account, then finally to the first account.
        //    Equity orders placed against a non-equity account get "Instrument type invalid".
        JsonNode margin = null;
        JsonNode equity = null;
        for (JsonNode acct : arr) {
            if (acct.get("account_id") == null) continue;
            if (margin == null && isMargin(acct)) {
                margin = acct;
            }
            if (equity == null && isEquity(acct)) {
                equity = acct;
            }
        }

        JsonNode chosen = margin != null ? margin
                : equity != null ? equity
                : arr.get(0);
        JsonNode id = chosen.get("account_id");
        if (id == null) {
            log.warn("[WebullV3Client] No usable account_id in account list: {}", resp.rawBody());
            return null;
        }
        cachedAccountId = id.asText();
        String kind = margin != null ? "MARGIN"
                : equity != null ? "EQUITY (non-margin)"
                : "FALLBACK (first account)";
        log.info("[WebullV3Client] Resolved {} accountId={} (class={} label={} type={})",
                kind, cachedAccountId, text(chosen, "account_class"),
                text(chosen, "account_label"), text(chosen, "account_type"));
        if (margin == null && equity == null) {
            log.warn("[WebullV3Client] No margin or equity account matched — fell back to first account. " +
                    "If orders fail with 'Instrument type invalid', set webull.trading.account-id explicitly.");
        } else if (margin == null) {
            log.warn("[WebullV3Client] No MARGIN account found — using a non-margin equity account instead.");
        }
        return cachedAccountId;
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode v = node.get(field);
            if (v != null && !v.isNull()) return v.asText();
        }
        return null;
    }

    private static JsonNode firstNonNull(JsonNode a, JsonNode b) {
        return a != null ? a : b;
    }

    /** GET account balance for an account — {@code /trading/assets/balances/get?account_id=}. */
    public BrokerResponse accountBalance(String accountId) {
        return get(props.endpoints().accountBalance(), Map.of("account_id", accountId));
    }

    /** GET positions for an account — {@code /trading/assets/positions/list?account_id=}. */
    public BrokerResponse positions(String accountId, int pageSize, String lastId) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("account_id", accountId);
        q.put("page_size", String.valueOf(pageSize));
        if (lastId != null && !lastId.isBlank()) {
            q.put("last_instrument_id", lastId);
        }
        return get(props.endpoints().positions(), q);
    }

    // -----------------------------------------------------------------------
    // Neutral account methods (BrokerClient) — Webull JSON parsing lives here
    // -----------------------------------------------------------------------

    @Override
    public com.trading.broker.model.AccountBalance fetchBalance(String accountId) {
        BrokerResponse bal = accountBalance(accountId);
        if (!bal.success() || bal.body() == null) {
            log.warn("[WebullV3Client] balance call failed: status={} body={}",
                    bal.statusCode(), bal.rawBody());
            return com.trading.broker.model.AccountBalance.INVALID;
        }
        JsonNode b = bal.body();
        BigDecimal netLiq  = num(b, "net_liquidation_value", "total_asset", "netLiquidationValue");
        BigDecimal cashPow = num(b, "cash_power", "buying_power", "total_cash_balance", "cashPower");
        if (netLiq == null) netLiq = BigDecimal.ZERO;
        if (cashPow == null) cashPow = BigDecimal.ZERO;

        // Some responses nest per-currency assets under "account_currency_assets"
        JsonNode assets = firstNonNull(b.get("account_currency_assets"),
                                       b.get("accountCurrencyAssets"));
        if ((netLiq.signum() == 0 || cashPow.signum() == 0)
                && assets != null && assets.isArray() && assets.size() > 0) {
            JsonNode a = assets.get(0);
            if (netLiq.signum() == 0) {
                BigDecimal v = num(a, "net_liquidation_value", "netLiquidationValue");
                if (v != null) netLiq = v;
            }
            if (cashPow.signum() == 0) {
                BigDecimal v = num(a, "cash_power", "cashPower", "cash_balance");
                if (v != null) cashPow = v;
            }
        }
        return new com.trading.broker.model.AccountBalance(netLiq, cashPow, true);
    }

    @Override
    public com.trading.broker.model.HoldingsResult fetchHoldings(String accountId) {
        java.util.List<com.trading.broker.model.Holding> out = new java.util.ArrayList<>();
        if (accountId == null) return com.trading.broker.model.HoldingsResult.INVALID;
        try {
            BrokerResponse pos = positions(accountId, 100, null);
            if (pos.success() && pos.body() != null) {
                JsonNode arr = firstNonNull(pos.body().get("holdings"), pos.body());
                if (arr != null && arr.isArray()) {
                    for (JsonNode h : arr) {
                        String sym = text(h, "symbol", "ticker", "instrument_symbol");
                        BigDecimal qtyNum = num(h, "quantity", "qty");
                        int qty = qtyNum == null ? 0 : qtyNum.max(BigDecimal.ZERO).intValue();
                        if (sym != null && qty > 0) {
                            BigDecimal unitCost = num(h, "unit_cost", "unitCost");
                            out.add(new com.trading.broker.model.Holding(
                                    sym.trim().toUpperCase(), qty,
                                    unitCost == null ? BigDecimal.ZERO : unitCost));
                        }
                    }
                    return new com.trading.broker.model.HoldingsResult(out, true);
                }
            } else {
                log.warn("[WebullV3Client] holdings fetch failed: status={}", pos.statusCode());
            }
        } catch (Exception e) {
            log.warn("[WebullV3Client] holdings fetch failed: {}", e.getMessage());
        }
        return com.trading.broker.model.HoldingsResult.INVALID;
    }

    /**
     * POST historical bars — {@code /market-data/stocks/bars/list}.
     *
     * <p>Body: {@code {symbols:[...], category, timespan, count, real_time_required}}.
     * Response: {@code {result:[{symbol, instrument_id, result:[{time,open,close,high,low,volume}]}]}}.</p>
     */
    public BrokerResponse bars(String symbol, String category, String timespan, int count) {
        return bars(symbol, category, timespan, count, null);
    }

    /**
     * POST historical bars with an explicit trading-sessions filter.
     *
     * @param tradingSessions comma-separated sessions (RTH,PRE,ATH,OVN), or null to
     *                        omit the field (Webull default applies)
     */
    public BrokerResponse bars(String symbol, String category, String timespan, int count,
                           String tradingSessions) {
        return bars(java.util.List.of(symbol), category, timespan, count, tradingSessions);
    }

    /**
     * POST historical bars for MULTIPLE symbols in a single request — the bars
     * endpoint accepts a {@code symbols} array. This is the rate-limit-friendly path:
     * one HTTP call covers many tickers instead of one call per ticker.
     *
     * <p>Response: {@code {result:[{symbol, result:[{time,open,close,...}]}, ...]}}.</p>
     */
    public BrokerResponse bars(java.util.List<String> symbols, String category, String timespan, int count,
                           String tradingSessions) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symbols", symbols);
        body.put("category", category);
        body.put("timespan", timespan);
        body.put("count", count);
        body.put("real_time_required", true);
        if (tradingSessions != null && !tradingSessions.isBlank()) {
            body.put("trading_sessions", tradingSessions);
        }
        return post(props.endpoints().bars(), null, body);
    }

    /**
     * POST place order — {@code /trading/orders/place}.
     *
     * <p>Webull expects the request body to wrap the order(s) in a {@code new_orders}
     * array, with {@code account_id} and {@code client_combo_order_id} at the top level:
     * <pre>{@code
     * { "account_id": "...", "client_combo_order_id": "...", "new_orders": [ { ...order... } ] }
     * }</pre>
     *
     * @param accountId the account to trade in
     * @param order     a single order as a Map (the fields inside one {@code new_orders} entry)
     */
    public BrokerResponse placeOrder(String accountId, Map<String, Object> order) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("account_id", accountId);
        // A combo id is required to group the order(s); reuse the order's client id.
        Object clientOrderId = order.get("client_order_id");
        body.put("client_combo_order_id",
                clientOrderId != null ? clientOrderId.toString() : java.util.UUID.randomUUID().toString().replace("-", ""));
        body.put("new_orders", java.util.List.of(order));
        return post(props.endpoints().placeOrder(), null, body);
    }

    // -----------------------------------------------------------------------
    // Neutral order submission (BrokerClient) — Webull order-map building here
    // -----------------------------------------------------------------------

    // Webull standalone order-type vocabulary.
    private static final String WB_MARKET             = "MARKET";
    private static final String WB_LIMIT              = "LIMIT";
    private static final String WB_STOP               = "STOP";
    private static final String WB_STOP_LIMIT         = "STOP_LIMIT";
    private static final String WB_TRAILING_STOP_LOSS = "TRAILING_STOP_LOSS";
    private static final String WB_TRAIL_PERCENTAGE   = "PERCENTAGE";

    @Override
    public com.trading.broker.model.OrderAck submitOrder(
            String accountId, com.trading.broker.model.OrderRequest order) {
        Map<String, Object> body = buildOrderMap(order);
        try {
            BrokerResponse resp = placeOrder(accountId, body);
            if (resp.success()) {
                String orderId = extractOrderId(resp.body());
                return new com.trading.broker.model.OrderAck(
                        true, orderId, "Accepted by Webull",
                        resp.statusCode(), resp.rawBody(), body);
            }
            String msg = "Rejected by Webull (status=" + resp.statusCode() + ")";
            return new com.trading.broker.model.OrderAck(
                    false, null, msg + " body=" + resp.rawBody(),
                    resp.statusCode(), resp.rawBody(), body);
        } catch (Exception e) {
            return new com.trading.broker.model.OrderAck(
                    false, null, "Exception: " + e.getMessage(), -1, null, body);
        }
    }

    /** Translates a neutral {@link com.trading.broker.model.OrderRequest} into the Webull order map. */
    private Map<String, Object> buildOrderMap(com.trading.broker.model.OrderRequest o) {
        String orderType = switch (o.type()) {
            case MARKET        -> WB_MARKET;
            case LIMIT         -> WB_LIMIT;
            case STOP          -> WB_STOP;
            case STOP_LIMIT    -> WB_STOP_LIMIT;
            case TRAILING_STOP -> WB_TRAILING_STOP_LOSS;
        };
        String side = o.side().name();   // BUY / SELL

        // NOTE: no option_strategy for equities — including it triggers
        // "Instrument type invalid." Fields mirror the official Equity example.
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("client_order_id", o.clientOrderId());
        m.put("combo_type", "NORMAL");
        m.put("instrument_type", "EQUITY");
        m.put("market", "US");
        m.put("symbol", o.symbol());
        m.put("side", side);
        m.put("order_type", orderType);
        m.put("quantity", String.valueOf(o.quantity()));
        m.put("time_in_force", o.timeInForce() == com.trading.broker.model.OrderRequest.TimeInForce.GTC ? "GTC" : "DAY");
        m.put("entrust_type", "QTY");
        m.put("support_trading_session", o.extendedHours() ? "ALL" : "CORE");

        if (o.limitPrice() != null) {
            m.put("limit_price", o.limitPrice().toPlainString());
        }
        if (o.stopPrice() != null) {
            m.put("stop_price", o.stopPrice().toPlainString());
        }
        if (o.type() == com.trading.broker.model.OrderRequest.Type.TRAILING_STOP && o.trailPct() != null) {
            m.put("trailing_type", WB_TRAIL_PERCENTAGE);
            m.put("trailing_stop_step", o.trailPct().toPlainString());
        }
        return m;
    }

    private String extractOrderId(JsonNode body) {
        if (body == null) return null;
        JsonNode id = body.get("order_id");
        if (id == null) id = body.get("orderId");
        if (id == null && body.has("data")) {
            JsonNode data = body.get("data");
            id = data.get("order_id");
            if (id == null) id = data.get("orderId");
        }
        return id == null ? null : id.asText();
    }

    /**
     * GET real-time snapshot — {@code /market-data/stocks/snapshots/list?symbols=&category=}.
     */
    public BrokerResponse snapshot(String symbol, String category) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("symbols", symbol);
        q.put("category", category);
        return get(props.endpoints().snapshot(), q);
    }

    // -----------------------------------------------------------------------
    // Neutral market-data methods (BrokerClient) — Webull JSON parsing lives here
    // -----------------------------------------------------------------------

    /** Webull market category for US equities. */
    private static final String CATEGORY_US_STOCK = "US_STOCK";

    @Override
    public List<Candle> fetchBars(String symbol, int count, String timespan, String sessions) {
        BrokerResponse resp = bars(symbol, CATEGORY_US_STOCK, timespan, count, sessions);
        if (!resp.success()) {
            throw new BrokerException(
                    "bars request failed for " + symbol + " timespan=" + timespan + " sessions=" + sessions
                    + " (status=" + resp.statusCode() + ", body=" + resp.rawBody() + ")", resp.statusCode());
        }
        return parseBars(symbol, resp.body());
    }

    @Override
    public Map<String, List<Candle>> fetchBarsBatch(List<String> symbols, int count, String timespan, String sessions) {
        Map<String, List<Candle>> out = new java.util.LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        BrokerResponse resp = bars(symbols, CATEGORY_US_STOCK, timespan, count, sessions);
        if (!resp.success()) {
            throw new BrokerException(
                    "batch bars request failed timespan=" + timespan + " sessions=" + sessions
                    + " symbols=" + symbols + " (status=" + resp.statusCode() + ", body=" + resp.rawBody() + ")",
                    resp.statusCode());
        }

        JsonNode root = resp.body();
        JsonNode result = root == null ? null
                : (root.has("result") ? root.get("result")
                : (root.has("data") ? root.get("data") : root));
        if (result == null || !result.isArray()) {
            log.warn("[WebullV3Client] Batch bars: no result array in response");
            return out;
        }

        // Each element is a per-symbol object: { symbol, result:[bars...] }.
        for (JsonNode entry : result) {
            String sym = text(entry, "symbol", "ticker");
            if (sym == null) continue;
            JsonNode barsArr = entry.has("result") ? entry.get("result")
                    : (entry.has("bars") ? entry.get("bars") : null);
            if (barsArr == null || !barsArr.isArray()) continue;

            List<Candle> candles = new java.util.ArrayList<>(barsArr.size());
            for (JsonNode bar : barsArr) {
                Candle c = mapBar(sym.trim().toUpperCase(), bar);
                if (c != null) candles.add(c);
            }
            if (!candles.isEmpty()) {
                out.put(sym.trim().toUpperCase(), candles);
            }
        }
        return out;
    }

    @Override
    public List<Candle> fetchRecentBars(String symbol, int count) {
        try {
            BrokerResponse resp = bars(symbol, CATEGORY_US_STOCK, "M1", count);
            if (!resp.success()) {
                log.warn("[WebullV3Client] recent bars call failed for symbol={} status={}",
                        symbol, resp.statusCode());
                return java.util.List.of();
            }
            return parseBars(symbol, resp.body());   // newest-first
        } catch (Exception e) {
            log.error("[WebullV3Client] Exception fetching recent bars for symbol={}", symbol, e);
            return java.util.List.of();
        }
    }

    @Override
    public Quote fetchQuote(String symbol) {
        try {
            BrokerResponse resp = snapshot(symbol, CATEGORY_US_STOCK);
            if (resp.success() && resp.body() != null) {
                JsonNode snap = firstSnapshot(resp.body());
                BigDecimal bid  = firstNum(snap, "bid_price", "bidPrice", "bid");
                BigDecimal ask  = firstNum(snap, "ask_price", "askPrice", "ask");
                if (bid == null) bid = nestedPrice(snap, "bid_list", "bidList");
                if (ask == null) ask = nestedPrice(snap, "ask_list", "askList");
                BigDecimal last = firstNum(snap, "price", "last_price", "close");
                return new Quote(bid, ask, last);
            }
        } catch (Exception e) {
            log.warn("[WebullV3Client] Quote lookup failed for {}: {}", symbol, e.getMessage());
        }
        return Quote.EMPTY;
    }

    @Override
    public BigDecimal fetchFillPrice(String symbol, BigDecimal fallback) {
        try {
            BrokerResponse resp = snapshot(symbol, CATEGORY_US_STOCK);
            if (resp.success() && resp.body() != null) {
                JsonNode snap = firstSnapshot(resp.body());

                // Prefer the LIVE quote mid (bid/ask). The snapshot's `price`/`close`
                // is the last TRADE, which in pre/after-hours can be hours stale while
                // the quote is current — so bid/ask is the real market price.
                BigDecimal bid = firstNum(snap, "bid_price", "bidPrice", "bid");
                BigDecimal ask = firstNum(snap, "ask_price", "askPrice", "ask");
                if (bid == null) bid = nestedPrice(snap, "bid_list", "bidList");
                if (ask == null) ask = nestedPrice(snap, "ask_list", "askList");
                if (bid != null && ask != null
                        && bid.compareTo(BigDecimal.ZERO) > 0 && ask.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2));
                    log.debug("[WebullV3Client] Fill price for {} = quote mid {} (bid={} ask={})",
                            symbol, mid, bid, ask);
                    return mid;
                }

                // No usable quote → fall back to last-trade price (regular hours this is fine).
                BigDecimal price = num(snap, "price", "last_price", "close");
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    log.debug("[WebullV3Client] Fill price for {} = last-trade {} (no live quote)", symbol, price);
                    return price;
                }
            }
            log.warn("[WebullV3Client] Snapshot fill price unavailable for {} (status={}) — using fallback {}",
                    symbol, resp.statusCode(), fallback);
        } catch (Exception e) {
            log.warn("[WebullV3Client] Snapshot fill price lookup failed for {} — using fallback {}: {}",
                    symbol, fallback, e.getMessage());
        }
        return fallback;
    }

    @Override
    public com.trading.broker.model.PriceSnapshot fetchPriceSnapshot(String symbol) {
        BrokerResponse resp;
        try {
            resp = snapshot(symbol, CATEGORY_US_STOCK);
        } catch (Exception e) {
            throw new BrokerException("snapshot request failed for " + symbol + ": " + e.getMessage(), -1, e);
        }
        if (!resp.success()) {
            throw new BrokerException("snapshot request failed for " + symbol
                    + " (status=" + resp.statusCode() + ", body=" + resp.rawBody() + ")", resp.statusCode());
        }
        JsonNode snap = firstSnapshot(resp.body());
        if (snap == null) return null;
        return new com.trading.broker.model.PriceSnapshot(
                text(snap, "price", "last_price", "close"),
                text(snap, "open"),
                text(snap, "high"),
                text(snap, "low"),
                text(snap, "pre_close", "preClose"),
                text(snap, "volume"),
                text(snap, "change"),
                text(snap, "change_ratio", "changeRatio"));
    }

    // ── Bars JSON parsing (moved verbatim from MarketDataService) ────────────

    /**
     * Parses the v3 bars JSON into candles (in the order returned — newest-first).
     */
    private List<Candle> parseBars(String ticker, JsonNode root) {
        List<Candle> out = new java.util.ArrayList<>();
        if (root == null) return out;

        JsonNode barsArray = locateBarsArray(root);
        if (barsArray == null || !barsArray.isArray()) {
            log.debug("[WebullV3Client] Could not locate bars array for ticker={} in: {}", ticker, root);
            return out;
        }

        for (JsonNode bar : barsArray) {
            Candle c = mapBar(ticker, bar);
            if (c != null) out.add(c);
        }
        return out;
    }

    private JsonNode locateBarsArray(JsonNode root) {
        // v3 shape: { "result": [ { "symbol":..., "result": [ {bar}, ... ] } ] }
        if (root.has("result")) {
            JsonNode result = root.get("result");
            if (result.isArray() && result.size() > 0) {
                JsonNode first = result.get(0);
                // per-symbol object with a nested "result" bar array
                if (first.has("result")) return first.get("result");
                // or the result array is already the bars
                if (first.has("close") || first.has("c")) return result;
            }
            return null;
        }
        // Fallbacks for alternative shapes
        if (root.isArray()) {
            if (root.size() == 0) return null;
            JsonNode first = root.get(0);
            if (first.has("result")) return first.get("result");
            if (first.has("bars")) return first.get("bars");
            if (first.has("close") || first.has("c")) return root;
            return null;
        }
        if (root.has("bars")) return root.get("bars");
        if (root.has("data")) return locateBarsArray(root.get("data"));
        return null;
    }

    private Candle mapBar(String ticker, JsonNode bar) {
        if (bar == null) return null;
        try {
            BigDecimal close = num(bar, "close", "c");
            if (close == null || close.compareTo(BigDecimal.ZERO) <= 0) return null;

            BigDecimal open = orElse(num(bar, "open", "o"), close);
            BigDecimal high = orElse(num(bar, "high", "h"), close);
            BigDecimal low  = orElse(num(bar, "low", "l"), close);
            long volume     = longOf(bar, "volume", "v");
            Instant ts      = timestampOf(bar, "timestamp", "t", "time");

            return new Candle(ticker, ts, open, high, low, close, volume);
        } catch (Exception e) {
            log.debug("[WebullV3Client] Failed to map bar for ticker={}: {}", ticker, bar);
            return null;
        }
    }

    // ── Snapshot/quote JSON helpers (moved verbatim from OrderService) ───────

    private static BigDecimal firstNum(JsonNode node, String... fields) {
        return num(node, fields);
    }

    /** Reads {@code price} from the first element of a bid_list/ask_list array field. */
    private static BigDecimal nestedPrice(JsonNode node, String... arrayFields) {
        if (node == null) return null;
        for (String f : arrayFields) {
            JsonNode arr = node.get(f);
            if (arr != null && arr.isArray() && arr.size() > 0) {
                BigDecimal p = num(arr.get(0), "price", "value");
                if (p != null) return p;
            }
        }
        return null;
    }

    /** Digs into {array} / {data:[]} / {result:[]} / single-object snapshot shapes. */
    private static JsonNode firstSnapshot(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root.size() > 0 ? root.get(0) : null;
        if (root.has("data")) return firstSnapshot(root.get("data"));
        if (root.has("result")) return firstSnapshot(root.get("result"));
        return (root.has("price") || root.has("last_price") || root.has("close")) ? root : null;
    }

    // ── Numeric/time field helpers (tolerant of snake/camel case) ────────────

    private static BigDecimal num(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                try { return new BigDecimal(v.asText().trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static BigDecimal orElse(BigDecimal v, BigDecimal fallback) {
        return v != null ? v : fallback;
    }

    private static long longOf(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                try { return Long.parseLong(v.asText().trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return 0L;
    }

    private static Instant timestampOf(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                String s = v.asText().trim();
                // 1) epoch millis (13 digits) or seconds (10 digits)
                try {
                    long epoch = Long.parseLong(s);
                    return s.length() > 12
                            ? Instant.ofEpochMilli(epoch)
                            : Instant.ofEpochSecond(epoch);
                } catch (NumberFormatException ignored) { }
                // 2) ISO-8601 with offset, e.g. 2021-12-28T09:00:09.945+0000
                try {
                    return java.time.OffsetDateTime
                            .parse(s, java.time.format.DateTimeFormatter.ofPattern(
                                    "yyyy-MM-dd'T'HH:mm:ss[.SSS]Z"))
                            .toInstant();
                } catch (Exception ignored) { }
                // 3) plain ISO instant
                try {
                    String iso = s.endsWith("Z") ? s : s + "Z";
                    return Instant.parse(iso);
                } catch (Exception ignored) { }
            }
        }
        return Instant.now();
    }

    /**
     * GET historical orders — {@code /trading/orders/historical-orders/list?account_id=}.
     * Defaults to the last 7 days when no time range is supplied.
     */
    public BrokerResponse orderHistory(String accountId, int pageSize, String paginationKey) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("account_id", accountId);
        q.put("page_size", String.valueOf(pageSize));
        if (paginationKey != null && !paginationKey.isBlank()) {
            q.put("pagination_key", paginationKey);
        }
        return get(props.endpoints().orderHistory(), q);
    }

    /** GET open (resting) orders — {@code /trading/orders/open-orders/list?account_id=}. */
    public BrokerResponse openOrders(String accountId, int pageSize, String paginationKey) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("account_id", accountId);
        q.put("page_size", String.valueOf(pageSize));
        if (paginationKey != null && !paginationKey.isBlank()) {
            q.put("pagination_key", paginationKey);
        }
        return get(props.endpoints().openOrders(), q);
    }

    // -----------------------------------------------------------------------
    // Neutral order-listing + connectivity (BrokerClient)
    // -----------------------------------------------------------------------

    @Override
    public List<com.trading.broker.model.OrderSummary> fetchOrderHistory(
            String accountId, int pageSize, String paginationKey) {
        return parseOrders(orderHistory(accountId, pageSize, paginationKey), "order history");
    }

    @Override
    public List<com.trading.broker.model.OrderSummary> fetchOpenOrders(
            String accountId, int pageSize, String paginationKey) {
        return parseOrders(openOrders(accountId, pageSize, paginationKey), "open orders");
    }

    private List<com.trading.broker.model.OrderSummary> parseOrders(BrokerResponse resp, String what) {
        if (!resp.success()) {
            throw new BrokerException(what + " request failed (status=" + resp.statusCode()
                    + ", body=" + resp.rawBody() + ")", resp.statusCode());
        }
        List<com.trading.broker.model.OrderSummary> out = new java.util.ArrayList<>();
        JsonNode root = resp.body();
        if (root == null) return out;
        JsonNode arr = root.has("orders") ? root.get("orders")
                : (root.has("data") ? root.get("data") : root);
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode o : arr) {
            out.add(new com.trading.broker.model.OrderSummary(
                    text(o, "order_id", "orderId"),
                    text(o, "client_order_id", "clientOrderId"),
                    text(o, "symbol", "ticker"),
                    text(o, "side"),
                    text(o, "order_type", "orderType"),
                    text(o, "status", "order_status", "orderStatus"),
                    text(o, "quantity", "qty"),
                    text(o, "filled_quantity", "filledQuantity", "filled_qty"),
                    text(o, "limit_price", "limitPrice"),
                    text(o, "stop_price", "stopPrice")));
        }
        return out;
    }

    @Override
    public com.trading.broker.model.ConnectivityStatus checkConnectivity() {
        BrokerResponse resp = accountList();
        if (resp.success()) {
            int accounts = (resp.body() != null && resp.body().isArray()) ? resp.body().size() : 0;
            return new com.trading.broker.model.ConnectivityStatus(
                    true, true, accounts, resp.statusCode(), null);
        }
        return new com.trading.broker.model.ConnectivityStatus(
                false, false, 0, resp.statusCode(),
                resp.statusCode() > 0 ? resp.rawBody() : resp.rawBody());
    }

    // -----------------------------------------------------------------------
    // Core execution
    // -----------------------------------------------------------------------

    /** Max total attempts for idempotent GETs (1 = no retry). POSTs are never retried. */
    private static final int GET_MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_BACKOFF_MS = 300;

    private BrokerResponse execute(String method, String path,
                               Map<String, String> queryParams, String bodyJson) {
        // Order placement and any other POST is NEVER retried: a retry after a
        // request that actually succeeded (but whose response was lost) would place
        // a duplicate order. Only idempotent GETs are retried.
        if (!"GET".equals(method)) {
            return executeOnce(method, path, queryParams, bodyJson);
        }

        BrokerResponse resp = null;
        for (int attempt = 1; attempt <= GET_MAX_ATTEMPTS; attempt++) {
            resp = executeOnce(method, path, queryParams, bodyJson);
            if (!isRetryable(resp)) {
                return resp;
            }
            if (attempt < GET_MAX_ATTEMPTS) {
                long backoff = RETRY_BASE_BACKOFF_MS * attempt;   // linear backoff
                log.warn("[WebullV3Client] GET {} attempt {}/{} failed (status={}); retrying in {}ms",
                        path, attempt, GET_MAX_ATTEMPTS, resp.statusCode(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return resp;
                }
            }
        }
        log.warn("[WebullV3Client] GET {} failed after {} attempts (status={})",
                path, GET_MAX_ATTEMPTS, resp == null ? "n/a" : resp.statusCode());
        return resp;
    }

    /** Retry only on transport failure (-1), 429, or 5xx. 4xx (except 429) are not retried. */
    private static boolean isRetryable(BrokerResponse resp) {
        int s = resp.statusCode();
        return s == -1 || s == 429 || (s >= 500 && s <= 599);
    }

    private BrokerResponse executeOnce(String method, String path,
                                   Map<String, String> queryParams, String bodyJson) {
        String host      = props.resolvedApiHost();
        String timestamp = TS_FORMAT.format(Instant.now());
        String nonce     = UUID.randomUUID().toString().replace("-", "");

        // Sorted query params also feed the signature — keep them consistent.
        TreeMap<String, String> query = new TreeMap<>();
        if (queryParams != null) query.putAll(queryParams);

        String signature = WebullSigner.sign(
                path, query, bodyJson,
                props.api().appKey(), props.api().appSecret(),
                host, timestamp, nonce);

        // Build the URL
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(host)
                .addPathSegments(path.startsWith("/") ? path.substring(1) : path);
        for (Map.Entry<String, String> e : query.entrySet()) {
            urlBuilder.addQueryParameter(e.getKey(), e.getValue());
        }
        HttpUrl url = urlBuilder.build();

        // Build the request with the signed headers
        Request.Builder rb = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("x-app-key", props.api().appKey())
                .addHeader("x-timestamp", timestamp)
                .addHeader("x-signature-algorithm", WebullSigner.ALGORITHM)
                .addHeader("x-signature-version", WebullSigner.SIGNATURE_VERSION)
                .addHeader("x-signature-nonce", nonce)
                .addHeader("x-version", WebullSigner.API_VERSION)
                .addHeader("x-signature", signature);

        // Optional 2FA access token (only if configured)
        String token = props.api().accessToken();
        if (token != null && !token.isBlank()) {
            rb.addHeader("x-access-token", token);
        }

        if ("POST".equals(method)) {
            rb.post(RequestBody.create(bodyJson == null ? "" : bodyJson, JSON));
        } else {
            rb.get();
        }

        // ── Log the outgoing request (DEBUG, opt-in via webull.api.log-requests) ──
        // Secrets are never logged: the app secret is not a header, and the
        // x-signature / x-access-token values are masked.
        boolean logRequests = props.api().logRequests() && log.isDebugEnabled();
        if (logRequests) {
            log.debug("[WebullV3Client] --> {} https://{}{}", method, host, url.encodedQuery() == null
                    ? path : path + "?" + url.encodedQuery());
            log.debug("[WebullV3Client]     headers: x-app-key={} x-timestamp={} x-version={} " +
                            "x-signature-algorithm={} x-signature-nonce={} x-signature={} x-access-token={}",
                    props.api().appKey(), timestamp, WebullSigner.API_VERSION, WebullSigner.ALGORITHM,
                    nonce, mask(signature), token != null && !token.isBlank() ? mask(token) : "(none)");
            if (bodyJson != null && !bodyJson.isEmpty()) {
                log.debug("[WebullV3Client]     body: {}", bodyJson);
            }
        }

        long start = System.currentTimeMillis();
        try (Response resp = http.newCall(rb.build()).execute()) {
            String raw = resp.body() != null ? resp.body().string() : "";
            JsonNode json = parseJsonSafe(raw);
            boolean ok = resp.isSuccessful();
            long ms = System.currentTimeMillis() - start;
            if (logRequests) {
                log.debug("[WebullV3Client] <-- {} {} HTTP {} ({}ms) body={}",
                        method, path, resp.code(), ms, raw);
            }
            return new BrokerResponse(resp.code(), ok, json, raw);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("[WebullV3Client] <-- {} {} FAILED ({}ms)", method, path, ms, e);
            return new BrokerResponse(-1, false, null, e.getMessage());
        }
    }

    /** Masks a sensitive value, showing only the first 4 and last 4 characters. */
    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "(empty)";
        if (value.length() <= 8) return "*".repeat(value.length());
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String serializeCompact(Object obj) {
        if (obj == null) return null;
        try {
            // Jackson emits compact JSON by default (no spaces) — matches the
            // signature MD5 requirement.
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize request body", e);
        }
    }

    private JsonNode parseJsonSafe(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
