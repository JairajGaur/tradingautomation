package com.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.config.WebullProperties;
import com.trading.state.PositionTracker;
import com.trading.webull.WebullV3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central service for all order execution against the Webull <b>v3</b> API via
 * {@link WebullV3Client}.
 *
 * <h2>Guardrails</h2>
 * <ol>
 *   <li><b>Trading halted</b> — BUYs rejected if {@link RiskManager#isTradingHalted()}.</li>
 *   <li><b>Low buying power</b> — BUYs rejected if cash below the configured minimum.</li>
 *   <li><b>No short selling</b> — a SELL is never allowed to exceed the quantity
 *       currently held for the ticker (tracked in {@link PositionTracker}).
 *       This makes it impossible for the bot to open or increase a short position.</li>
 * </ol>
 *
 * <h2>Trade attribution</h2>
 * Every order records a {@link TradeLog.TradeEntry} tagging the strategy (or
 * system source) that fired it, so {@code GET /api/trades} shows what happened.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    // ── Webull-supported standalone order types ──────────────────────────────
    //   LIMIT | MARKET | STOP | STOP_LIMIT | TRAILING_STOP_LOSS
    // (STOP_LOSS / STOP_PROFIT are also combo-leg types when used inside a MASTER.)
    public static final String TYPE_MARKET             = "MARKET";
    public static final String TYPE_LIMIT              = "LIMIT";
    public static final String TYPE_STOP               = "STOP";
    public static final String TYPE_STOP_LIMIT         = "STOP_LIMIT";
    public static final String TYPE_TRAILING_STOP_LOSS = "TRAILING_STOP_LOSS";

    // Trailing-stop trail measure: PERCENTAGE (e.g. 0.01 = 1%) or AMOUNT (dollars).
    public static final String TRAIL_PERCENTAGE = "PERCENTAGE";
    public static final String TRAIL_AMOUNT     = "AMOUNT";

    private final WebullProperties props;
    private final WebullV3Client client;
    private final AccountService accountService;
    private final RiskManager riskManager;
    private final PositionTracker positionTracker;
    private final TradeLog tradeLog;

    public OrderService(WebullProperties props,
                        WebullV3Client client,
                        @Lazy AccountService accountService,
                        @Lazy RiskManager riskManager,
                        PositionTracker positionTracker,
                        TradeLog tradeLog) {
        this.props = props;
        this.client = client;
        this.accountService = accountService;
        this.riskManager = riskManager;
        this.positionTracker = positionTracker;
        this.tradeLog = tradeLog;
    }

    /**
     * Outcome of an order attempt.
     *
     * @param success      whether the order was accepted (or simulated)
     * @param clientOrderId client-assigned id
     * @param orderId      broker order id (when returned)
     * @param message      short summary / failure reason
     * @param httpStatus   HTTP status from Webull (-1 when not an HTTP call, e.g. simulated/guardrail)
     * @param rawResponse  the raw Webull response body (or null) — the actual success/failure payload
     * @param requestSent  the exact order fields we submitted (order_type, prices, qty, ...),
     *                     for debugging what was sent vs. what Webull returned
     */
    public record OrderResult(
            boolean success,
            String clientOrderId,
            String orderId,
            String message,
            int httpStatus,
            String rawResponse,
            Map<String, Object> requestSent
    ) {
        static OrderResult paper(String clientOrderId, Map<String, Object> requestSent) {
            return new OrderResult(true, clientOrderId, null,
                    "SIMULATED (not sent to Webull)", -1, null, requestSent);
        }
        static OrderResult failure(String clientOrderId, String message) {
            return new OrderResult(false, clientOrderId, null, message, -1, null, null);
        }
    }

    // -----------------------------------------------------------------------
    // BUY
    // -----------------------------------------------------------------------

    /** Backward-compatible BUY (strategy attributed as UNKNOWN). */
    public OrderResult placeMarketBuy(String ticker, int qty) {
        return placeMarketBuy(ticker, qty, "UNKNOWN");
    }

    public OrderResult placeMarketBuy(String ticker, int qty, String strategy) {
        String clientOrderId = newClientOrderId();

        if (riskManager.isTradingHalted()) {
            log.warn("[OrderService] BUY BLOCKED — trading halted: ticker={}", ticker);
            recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, "TRADING_HALTED");
            return OrderResult.failure(clientOrderId, "TRADING_HALTED");
        }

        BigDecimal buyingPower = accountService.getBuyingPower();
        if (!riskManager.hasSufficientBuyingPower(buyingPower)) {
            log.warn("[OrderService] BUY BLOCKED — insufficient buying power: ticker={} available={}",
                    ticker, buyingPower);
            recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, "INSUFFICIENT_BUYING_POWER");
            return OrderResult.failure(clientOrderId, "INSUFFICIENT_BUYING_POWER");
        }

        Map<String, Object> body = baseOrder(clientOrderId, ticker, qty, "BUY", TYPE_MARKET);

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED BUY  | ticker={} qty={} type=MARKET tif=DAY id={}",
                    ticker, qty, clientOrderId);
            recordSuccess(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, body);
        }

        return submit(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, body);
    }

    // -----------------------------------------------------------------------
    // SELL variants (all subject to the no-short guardrail)
    // -----------------------------------------------------------------------

    public OrderResult placeStopSell(String ticker, int qty, BigDecimal stopPrice) {
        return placeStopSell(ticker, qty, stopPrice, "UNKNOWN");
    }

    public OrderResult placeStopSell(String ticker, int qty, BigDecimal stopPrice, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "STOP-SELL", ticker, qty, stopPrice, TYPE_STOP, clientOrderId);
        if (shortBlock != null) return shortBlock;

        Map<String, Object> body = baseOrder(clientOrderId, ticker, qty, "SELL", TYPE_STOP);
        body.put("stop_price", stopPrice.toPlainString());
        body.put("time_in_force", "GTC");

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED STOP-SELL | ticker={} qty={} stopPrice={} id={}",
                    ticker, qty, stopPrice.toPlainString(), clientOrderId);
            recordSuccess(strategy, "STOP-SELL", ticker, qty, stopPrice, TYPE_STOP, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, body);
        }

        return submit(strategy, "STOP-SELL", ticker, qty, stopPrice, TYPE_STOP, clientOrderId, body);
    }

    public OrderResult placeLimitSell(String ticker, int qty, BigDecimal limitPrice) {
        return placeLimitSell(ticker, qty, limitPrice, "UNKNOWN");
    }

    public OrderResult placeLimitSell(String ticker, int qty, BigDecimal limitPrice, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "LIMIT-SELL", ticker, qty, limitPrice, TYPE_LIMIT, clientOrderId);
        if (shortBlock != null) return shortBlock;

        Map<String, Object> body = baseOrder(clientOrderId, ticker, qty, "SELL", TYPE_LIMIT);
        body.put("limit_price", limitPrice.toPlainString());
        body.put("time_in_force", "GTC");

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED LIMIT-SELL | ticker={} qty={} limitPrice={} id={}",
                    ticker, qty, limitPrice.toPlainString(), clientOrderId);
            recordSuccess(strategy, "LIMIT-SELL", ticker, qty, limitPrice, TYPE_LIMIT, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, body);
        }

        return submit(strategy, "LIMIT-SELL", ticker, qty, limitPrice, TYPE_LIMIT, clientOrderId, body);
    }

    public OrderResult placeTrailingStopSell(String ticker, int qty, BigDecimal trailPct) {
        return placeTrailingStopSell(ticker, qty, trailPct, "UNKNOWN");
    }

    /**
     * Places a percentage-based TRAILING STOP sell. The stop price trails the market
     * price upward by {@code trailPct} and triggers a market sell once price falls
     * that far from its high — letting a winner run while protecting gains.
     *
     * <p>Webull fields: {@code order_type=TRAILING_STOP_LOSS}, {@code trailing_type=PERCENTAGE},
     * {@code trailing_stop_step=<pct>} where {@code 0.01 = 1%}. Trailing stops only
     * support {@code DAY} time in force.</p>
     *
     * @param trailPct trail fraction, e.g. {@code 0.01} for 1%
     */
    public OrderResult placeTrailingStopSell(String ticker, int qty, BigDecimal trailPct, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "TRAIL-STOP-SELL", ticker, qty, trailPct,
                TYPE_TRAILING_STOP_LOSS, clientOrderId);
        if (shortBlock != null) return shortBlock;

        Map<String, Object> body = baseOrder(clientOrderId, ticker, qty, "SELL", TYPE_TRAILING_STOP_LOSS);
        body.put("trailing_type", TRAIL_PERCENTAGE);
        body.put("trailing_stop_step", trailPct.toPlainString());
        // Trailing stops only support DAY (baseOrder already sets DAY) — do not override to GTC.

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED TRAIL-STOP-SELL | ticker={} qty={} trail={}% id={}",
                    ticker, qty, trailPct.movePointRight(2).toPlainString(), clientOrderId);
            recordSuccess(strategy, "TRAIL-STOP-SELL", ticker, qty, trailPct,
                    TYPE_TRAILING_STOP_LOSS, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, body);
        }

        return submit(strategy, "TRAIL-STOP-SELL", ticker, qty, trailPct,
                TYPE_TRAILING_STOP_LOSS, clientOrderId, body);
    }

    /** Backward-compatible emergency market SELL. */
    public OrderResult placeMarketSell(String ticker, int qty) {
        return placeMarketSell(ticker, qty, "RISK_MANAGER");
    }

    /**
     * Market SELL to flatten a position (used by RiskManager and the close-all
     * endpoint). Bypasses the halt/buying-power gates but is STILL subject to the
     * no-short guardrail — it can only sell what is held.
     */
    public OrderResult placeMarketSell(String ticker, int qty, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "MARKET-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId);
        if (shortBlock != null) return shortBlock;

        Map<String, Object> body = baseOrder(clientOrderId, ticker, qty, "SELL", TYPE_MARKET);

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED MARKET-SELL | ticker={} qty={} id={}", ticker, qty, clientOrderId);
            recordSuccess(strategy, "MARKET-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId, null);
            return OrderResult.paper(clientOrderId, body);
        }

        return submit(strategy, "MARKET-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId, body);
    }

    // -----------------------------------------------------------------------
    // No-short guardrail
    // -----------------------------------------------------------------------

    /**
     * Blocks any SELL that would exceed the quantity currently held for the ticker.
     * Since the bot only ever holds long positions it opened, selling more than the
     * held quantity would open/increase a short — which is never permitted.
     *
     * @return an {@link OrderResult} failure when the sell is blocked, or {@code null} when allowed
     */
    private OrderResult guardNoShort(String strategy, String action, String ticker, int qty,
                                     BigDecimal price, String orderType, String clientOrderId) {
        if (qty <= 0) {
            recordFailure(strategy, action, ticker, qty, price, orderType, clientOrderId, "INVALID_QUANTITY");
            return OrderResult.failure(clientOrderId, "INVALID_QUANTITY");
        }
        int held = positionTracker.getPosition(ticker).map(PositionTracker.Position::quantity).orElse(0);
        if (qty > held) {
            String msg = "SHORT_BLOCKED: sell qty=" + qty + " exceeds held qty=" + held
                    + " for " + ticker + " (short selling is disabled)";
            log.warn("[OrderService] {} BLOCKED — {}", action, msg);
            recordFailure(strategy, action, ticker, qty, price, orderType, clientOrderId, msg);
            return OrderResult.failure(clientOrderId, msg);
        }
        return null; // allowed
    }

    // -----------------------------------------------------------------------
    // Submission
    // -----------------------------------------------------------------------

    private OrderResult submit(String strategy, String label, String ticker, int qty,
                               BigDecimal price, String orderType,
                               String clientOrderId, Map<String, Object> body) {
        String accountId = client.resolveAccountId();
        if (accountId == null) {
            recordFailure(strategy, label, ticker, qty, price, orderType, clientOrderId, "NO_ACCOUNT");
            return new OrderResult(false, clientOrderId, null,
                    "Could not resolve account ID", -1, null, body);
        }
        try {
            log.info("[OrderService] SUBMIT {} | ticker={} id={}", label, ticker, clientOrderId);
            WebullV3Client.V3Response resp = client.placeOrder(accountId, body);

            if (resp.success()) {
                String orderId = extractOrderId(resp.body());
                log.info("[OrderService] {} accepted | ticker={} orderId={} webullResponse={}",
                        label, ticker, orderId, resp.rawBody());
                recordSuccess(strategy, label, ticker, qty, price, orderType, clientOrderId, orderId);
                postOrderRefresh();
                return new OrderResult(true, clientOrderId, orderId,
                        "Accepted by Webull", resp.statusCode(), resp.rawBody(), body);
            }

            String msg = "Rejected by Webull (status=" + resp.statusCode() + ")";
            log.error("[OrderService] {} rejected | ticker={} status={} body={}",
                    label, ticker, resp.statusCode(), resp.rawBody());
            recordFailure(strategy, label, ticker, qty, price, orderType, clientOrderId,
                    msg + " body=" + resp.rawBody());
            return new OrderResult(false, clientOrderId, null, msg, resp.statusCode(), resp.rawBody(), body);

        } catch (Exception e) {
            log.error("[OrderService] {} exception | ticker={}", label, ticker, e);
            recordFailure(strategy, label, ticker, qty, price, orderType, clientOrderId, String.valueOf(e.getMessage()));
            return new OrderResult(false, clientOrderId, null,
                    "Exception: " + e.getMessage(), -1, null, body);
        }
    }

    private Map<String, Object> baseOrder(String clientOrderId, String ticker,
                                          int qty, String side, String orderType) {
        // NOTE: no option_strategy for equities — including it triggers
        // "Instrument type invalid." Fields mirror the official Equity example.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_order_id", clientOrderId);
        m.put("combo_type", "NORMAL");
        m.put("instrument_type", "EQUITY");
        m.put("market", "US");
        m.put("symbol", ticker);
        m.put("side", side);
        m.put("order_type", orderType);
        m.put("quantity", String.valueOf(qty));
        m.put("time_in_force", "DAY");
        m.put("entrust_type", "QTY");
        m.put("support_trading_session", "CORE");
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

    private void postOrderRefresh() {
        if (!props.risk().accountRefreshEnabled()) return;
        try {
            AccountService.AccountSnapshot snap = accountService.refresh();
            riskManager.evaluateDrawdown(snap.netLiquidationValue());
        } catch (Exception e) {
            log.error("[OrderService] Post-order account refresh failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // Trade-log recording
    // -----------------------------------------------------------------------

    private void recordSuccess(String strategy, String action, String ticker, int qty,
                               BigDecimal price, String orderType, String clientOrderId, String orderId) {
        tradeLog.record(new TradeLog.TradeEntry(
                Instant.now(), strategy, action, ticker, qty, price, orderType,
                clientOrderId, orderId, props.trading().mode().name(), true, ""));
    }

    private void recordFailure(String strategy, String action, String ticker, int qty,
                               BigDecimal price, String orderType, String clientOrderId, String message) {
        tradeLog.record(new TradeLog.TradeEntry(
                Instant.now(), strategy, action, ticker, qty, price, orderType,
                clientOrderId, null, props.trading().mode().name(), false, message));
    }

    private static String newClientOrderId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // -----------------------------------------------------------------------
    // Fill price
    // -----------------------------------------------------------------------

    private static final String CATEGORY_US_STOCK = "US_STOCK";

    /**
     * Resolves the price to anchor a bracket (stop-loss / take-profit) on, after a
     * MARKET buy. A market order has no limit price, so we approximate the fill with
     * the current market snapshot price (a market buy fills at ~the current price).
     *
     * <p>If the snapshot is unavailable (Webull error, no data, PAPER quirks), the
     * supplied {@code fallback} — normally the signal candle's open — is returned so
     * the strategy can still place its bracket.</p>
     *
     * @param ticker   the symbol just bought
     * @param fallback price used when the snapshot can't be fetched (must be non-null)
     * @return the resolved fill-anchor price (never null)
     */
    public BigDecimal fetchFillPrice(String ticker, BigDecimal fallback) {
        try {
            WebullV3Client.V3Response resp = client.snapshot(ticker, CATEGORY_US_STOCK);
            if (resp.success() && resp.body() != null) {
                JsonNode snap = firstSnapshot(resp.body());
                BigDecimal price = num(snap, "price", "last_price", "close");
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("[OrderService] Fill price for {} resolved from snapshot: {}", ticker, price);
                    return price;
                }
            }
            log.warn("[OrderService] Snapshot fill price unavailable for {} (status={}) — using fallback {}",
                    ticker, resp.statusCode(), fallback);
        } catch (Exception e) {
            log.warn("[OrderService] Snapshot fill price lookup failed for {} — using fallback {}: {}",
                    ticker, fallback, e.getMessage());
        }
        return fallback;
    }

    /** Digs into {array} / {data:[]} / {result:[]} / single-object snapshot shapes. */
    private static JsonNode firstSnapshot(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root.size() > 0 ? root.get(0) : null;
        if (root.has("data")) return firstSnapshot(root.get("data"));
        if (root.has("result")) return firstSnapshot(root.get("result"));
        return (root.has("price") || root.has("last_price") || root.has("close")) ? root : null;
    }

    /** Reads the first present numeric field from {@code fields}, or null. */
    private static BigDecimal num(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull()) {
                try {
                    return new BigDecimal(v.asText());
                } catch (NumberFormatException ignore) {
                    // try next field name
                }
            }
        }
        return null;
    }
}
