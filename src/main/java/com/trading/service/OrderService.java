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
    private final EntryGuard entryGuard;
    private final MarketHoursGuard marketHoursGuard;

    public OrderService(WebullProperties props,
                        WebullV3Client client,
                        @Lazy AccountService accountService,
                        @Lazy RiskManager riskManager,
                        PositionTracker positionTracker,
                        TradeLog tradeLog,
                        @Lazy EntryGuard entryGuard,
                        MarketHoursGuard marketHoursGuard) {
        this.props = props;
        this.client = client;
        this.accountService = accountService;
        this.riskManager = riskManager;
        this.positionTracker = positionTracker;
        this.tradeLog = tradeLog;
        this.entryGuard = entryGuard;
        this.marketHoursGuard = marketHoursGuard;
    }

    // -----------------------------------------------------------------------
    // Session-aware order helpers (spread guard + BUY/SELL routing)
    // -----------------------------------------------------------------------

    /**
     * Checks the bid/ask spread guard. Returns a failure reason string when the
     * trade must be blocked (spread too wide or bid/ask unavailable), else null.
     */
    private String spreadBlockReason(String ticker, Quote q) {
        if (!q.hasBidAsk()) {
            return "SPREAD_GUARD: bid/ask unavailable for " + ticker;
        }
        BigDecimal spread = q.spread();
        BigDecimal max = props.trading().maxSpreadUsd();
        if (spread.compareTo(max) > 0) {
            return "SPREAD_GUARD: spread=" + spread + " > max=" + max + " for " + ticker;
        }
        return null;
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

    /** Strategy BUY — subject to the entry guard. */
    public OrderResult placeMarketBuy(String ticker, int qty, String strategy) {
        return placeMarketBuy(ticker, qty, strategy, false);
    }

    /**
     * Market BUY.
     *
     * @param bypassEntryGuard when {@code true}, skip the ST/EMA entry-guard "armed"
     *                         check — used for MANUAL trades placed by the operator.
     *                         Strategy buys pass {@code false} so the guard applies.
     *                         (The trading-halt and buying-power checks always apply.)
     */
    public OrderResult placeMarketBuy(String ticker, int qty, String strategy, boolean bypassEntryGuard) {
        String clientOrderId = newClientOrderId();

        if (riskManager.isTradingHalted()) {
            log.warn("[OrderService] BUY BLOCKED — trading halted: ticker={}", ticker);
            recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, "TRADING_HALTED");
            return OrderResult.failure(clientOrderId, "TRADING_HALTED");
        }

        // Universal entry guard (strategy buys only): the ticker must be ARMED — i.e.
        // the guard (30m ST + EMA stack) passed on this cycle's refresh. A strategy
        // signal while un-armed is dropped. MANUAL trades bypass this.
        if (!bypassEntryGuard && !entryGuard.isArmed(ticker)) {
            String reason = entryGuard.lastReason(ticker);   // e.g. ST_NOT_UP_M30, EMA_STACK_FAIL, ST_UNAVAILABLE_M30
            String msg = "ENTRY_GUARD_NOT_ARMED: " + reason;
            log.info("[OrderService] BUY not taken — entry guard not satisfied: ticker={} reason={}", ticker, reason);
            recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, msg);
            return OrderResult.failure(clientOrderId, msg);
        }

        // Live quote for the spread guard, affordability, and pre-market limit price.
        Quote quote = fetchQuote(ticker);

        // Spread guard — applies in EVERY session.
        String spreadBlock = spreadBlockReason(ticker, quote);
        if (spreadBlock != null) {
            log.warn("[OrderService] BUY BLOCKED — {}", spreadBlock);
            recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, spreadBlock);
            return OrderResult.failure(clientOrderId, spreadBlock);
        }

        // Real-time balance + affordability. Price basis = ask (marketable) if known,
        // else last. Confirms buying power covers qty * price.
        BigDecimal buyingPower = accountService.getBuyingPowerLive();
        BigDecimal price = quote.ask() != null ? quote.ask() : fetchFillPrice(ticker, null);
        if (!riskManager.canAfford(buyingPower, qty, price)) {
            BigDecimal estCost = price == null ? null : price.multiply(BigDecimal.valueOf(qty));
            log.warn("[OrderService] BUY BLOCKED — insufficient buying power: ticker={} qty={} price={} estCost={} available={}",
                    ticker, qty, price, estCost, buyingPower);
            recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, "INSUFFICIENT_BUYING_POWER");
            return OrderResult.failure(clientOrderId, "INSUFFICIENT_BUYING_POWER");
        }

        // Session-aware order routing: REGULAR → MARKET; PRE_MARKET → LIMIT at bid.
        MarketHoursGuard.Session session = marketHoursGuard.currentSession();
        Map<String, Object> body;
        String orderType;
        if (session == MarketHoursGuard.Session.PRE_MARKET) {
            orderType = TYPE_LIMIT;
            body = baseOrder(clientOrderId, ticker, qty, "BUY", TYPE_LIMIT);
            body.put("limit_price", quote.ask().toPlainString());   // marketable: lift the offer
            body.put("support_trading_session", "ALL");   // allow the order to work pre-market
        } else {
            orderType = TYPE_MARKET;
            body = baseOrder(clientOrderId, ticker, qty, "BUY", TYPE_MARKET);
        }

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED BUY  | ticker={} qty={} type={} session={} id={}",
                    ticker, qty, orderType, session, clientOrderId);
            recordSuccess(strategy, "BUY", ticker, qty, null, orderType, clientOrderId, null);
            if (!bypassEntryGuard) entryGuard.clearArmed(ticker);   // consume the armed state on strategy entry
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, body);
        }

        OrderResult result = submit(strategy, "BUY", ticker, qty, price, orderType, clientOrderId, body);
        if (result.success() && !bypassEntryGuard) {
            entryGuard.clearArmed(ticker);   // consume the armed state once a strategy buy is accepted
        }
        return result;
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

    /**
     * Session-aware EXIT sell used by the {@code ExitManager}: subject to the spread
     * guard (all sessions) and the no-short guard; routed by session — REGULAR →
     * MARKET, PRE_MARKET → LIMIT at the current bid.
     */
    public OrderResult placeExitSell(String ticker, int qty, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "EXIT-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId);
        if (shortBlock != null) return shortBlock;

        Quote quote = fetchQuote(ticker);
        String spreadBlock = spreadBlockReason(ticker, quote);
        if (spreadBlock != null) {
            log.warn("[OrderService] EXIT-SELL BLOCKED — {}", spreadBlock);
            recordFailure(strategy, "EXIT-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId, spreadBlock);
            return OrderResult.failure(clientOrderId, spreadBlock);
        }

        MarketHoursGuard.Session session = marketHoursGuard.currentSession();
        Map<String, Object> body;
        String orderType;
        BigDecimal price;
        if (session == MarketHoursGuard.Session.PRE_MARKET) {
            orderType = TYPE_LIMIT;
            price = quote.bid();
            body = baseOrder(clientOrderId, ticker, qty, "SELL", TYPE_LIMIT);
            body.put("limit_price", quote.bid().toPlainString());
            body.put("support_trading_session", "ALL");
        } else {
            orderType = TYPE_MARKET;
            price = quote.last();
            body = baseOrder(clientOrderId, ticker, qty, "SELL", TYPE_MARKET);
        }

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED EXIT-SELL | ticker={} qty={} type={} session={} id={}",
                    ticker, qty, orderType, session, clientOrderId);
            recordSuccess(strategy, "EXIT-SELL", ticker, qty, price, orderType, clientOrderId, null);
            return OrderResult.paper(clientOrderId, body);
        }

        return submit(strategy, "EXIT-SELL", ticker, qty, price, orderType, clientOrderId, body);
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
        // Held quantity = max of what THIS bot instance tracks in memory and what the
        // account ACTUALLY holds on Webull (positions fetched live). This lets a SELL
        // go through for shares you genuinely own — e.g. bought manually or in a prior
        // run — while still blocking a true short (selling more than is held anywhere).
        int trackedHeld = positionTracker.getPosition(ticker).map(PositionTracker.Position::quantity).orElse(0);
        int liveHeld = accountService.getHeldQuantity(ticker);   // -1 = unknown (positions call failed)
        // On unknown live holdings, fall back to the tracked quantity (don't relax the guard).
        int held = Math.max(trackedHeld, Math.max(liveHeld, 0));
        if (qty > held) {
            String webullStr = liveHeld == AccountService.HELD_UNKNOWN ? "unknown" : String.valueOf(liveHeld);
            String msg = "SHORT_BLOCKED: sell qty=" + qty + " exceeds held qty=" + held
                    + " (tracked=" + trackedHeld + ", webull=" + webullStr + ")"
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
     * @param ticker   the symbol
     * @param fallback price returned when the snapshot can't be fetched; may be
     *                 {@code null} when the caller wants to detect an unresolved price
     * @return the snapshot price, or {@code fallback} when it can't be resolved
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

    /** Live quote: bid, ask, last. Any field may be null when unavailable. */
    public record Quote(BigDecimal bid, BigDecimal ask, BigDecimal last) {
        public boolean hasBidAsk() {
            return bid != null && ask != null
                    && bid.compareTo(BigDecimal.ZERO) > 0 && ask.compareTo(BigDecimal.ZERO) > 0;
        }
        public BigDecimal spread() {
            return hasBidAsk() ? ask.subtract(bid) : null;
        }
    }

    /**
     * Fetches the current bid/ask/last from the Webull snapshot. Reads both flat
     * ({@code bid_price}/{@code ask_price}) and nested ({@code bid_list[0].price})
     * shapes. Returns a {@link Quote} with null fields when unavailable.
     */
    public Quote fetchQuote(String ticker) {
        try {
            WebullV3Client.V3Response resp = client.snapshot(ticker, CATEGORY_US_STOCK);
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
            log.warn("[OrderService] Quote lookup failed for {}: {}", ticker, e.getMessage());
        }
        return new Quote(null, null, null);
    }

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
