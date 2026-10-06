package com.trading.service;

import com.trading.broker.BrokerClient;
import com.trading.broker.model.OrderAck;
import com.trading.broker.model.OrderRequest;
import com.trading.broker.model.Quote;
import com.trading.config.WebullProperties;
import com.trading.state.PositionTracker;
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
 * Central service for all order execution against the broker API via the
 * {@link BrokerClient} abstraction (Webull today).
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

    // ── Canonical order-type LABELS used for trade-log attribution/reporting ──
    // (Broker wire vocabulary lives in the broker adapter, keyed off OrderRequest.Type.)
    public static final String TYPE_MARKET             = "MARKET";
    public static final String TYPE_LIMIT              = "LIMIT";
    public static final String TYPE_STOP               = "STOP";
    public static final String TYPE_TRAILING_STOP_LOSS = "TRAILING_STOP_LOSS";

    private final WebullProperties props;
    private final BrokerClient client;
    private final AccountService accountService;
    private final RiskManager riskManager;
    private final PositionTracker positionTracker;
    private final TradeLog tradeLog;
    private final EntryGuard entryGuard;
    private final MarketHoursGuard marketHoursGuard;

    public OrderService(WebullProperties props,
                        BrokerClient client,
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
     * Quote/spread guard. Blocks a trade when bid/ask are unavailable (BUY/EXIT limit
     * orders in extended hours are priced off the ask/bid — no quote means no valid
     * price reference), or when the spread is too wide RELATIVE TO PRICE. The width cap
     * is a percentage of the mid ({@code webull.trading.max-spread-pct}) so it scales
     * across price levels — a flat dollar cap would wrongly pass wide spreads on cheap
     * stocks and block tight spreads on expensive ones. Returns a failure reason string
     * when the trade must be blocked, else null. A cap of 0 disables the width check.
     */
    private String spreadBlockReason(String ticker, Quote q) {
        if (!q.hasBidAsk()) {
            return "SPREAD_GUARD: bid/ask unavailable for " + ticker;
        }
        BigDecimal max = props.trading().maxSpreadPct();
        if (max.signum() > 0) {
            BigDecimal pct = q.spreadPct();
            if (pct != null && pct.compareTo(max) > 0) {
                return "SPREAD_GUARD: spread=" + q.spread() + " (" + pct.movePointRight(2) + "% of mid)"
                        + " > max=" + max.movePointRight(2) + "% for " + ticker;
            }
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
            // Not in the armed set (the periodic arm sweep only runs at :01/:31).
            // Evaluate the guard LIVE right now: if it passes, arm and proceed; the
            // strategy signal + a currently-satisfied guard is a valid entry. Only
            // block when the live guard actually fails.
            EntryGuard.Decision d = entryGuard.evaluate(ticker);
            if (!d.allowed()) {
                String msg = "ENTRY_GUARD_NOT_ARMED: " + d.reason();
                log.info("[OrderService] BUY not taken — entry guard not satisfied: ticker={} reason={}",
                        ticker, d.reason());
                recordFailure(strategy, "BUY", ticker, qty, null, TYPE_MARKET, clientOrderId, msg);
                return OrderResult.failure(clientOrderId, msg);
            }
            entryGuard.arm(ticker);   // guard passes live → arm so the buy proceeds
            log.info("[OrderService] Guard passed live for {} — arming and proceeding with BUY", ticker);
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
        OrderRequest request;
        String orderType;
        if (session == MarketHoursGuard.Session.PRE_MARKET) {
            orderType = TYPE_LIMIT;
            request = new OrderRequest(clientOrderId, ticker, OrderRequest.Side.BUY,
                    OrderRequest.Type.LIMIT, qty,
                    quote.ask(),   // marketable: lift the offer
                    null, null, OrderRequest.TimeInForce.DAY,
                    true);         // allow the order to work pre-market
        } else {
            orderType = TYPE_MARKET;
            request = OrderRequest.market(clientOrderId, ticker, OrderRequest.Side.BUY, qty);
        }

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED BUY  | ticker={} qty={} type={} session={} id={}",
                    ticker, qty, orderType, session, clientOrderId);
            recordSuccess(strategy, "BUY", ticker, qty, null, orderType, clientOrderId, null);
            if (!bypassEntryGuard) entryGuard.clearArmed(ticker);   // consume the armed state on strategy entry
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, requestSnapshot(request));
        }

        OrderResult result = submit(strategy, "BUY", ticker, qty, price, orderType, clientOrderId, request);
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

        OrderRequest request = new OrderRequest(clientOrderId, ticker, OrderRequest.Side.SELL,
                OrderRequest.Type.STOP, qty, null, stopPrice, null,
                OrderRequest.TimeInForce.GTC, false);

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED STOP-SELL | ticker={} qty={} stopPrice={} id={}",
                    ticker, qty, stopPrice.toPlainString(), clientOrderId);
            recordSuccess(strategy, "STOP-SELL", ticker, qty, stopPrice, TYPE_STOP, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, requestSnapshot(request));
        }

        return submit(strategy, "STOP-SELL", ticker, qty, stopPrice, TYPE_STOP, clientOrderId, request);
    }

    public OrderResult placeLimitSell(String ticker, int qty, BigDecimal limitPrice) {
        return placeLimitSell(ticker, qty, limitPrice, "UNKNOWN");
    }

    public OrderResult placeLimitSell(String ticker, int qty, BigDecimal limitPrice, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "LIMIT-SELL", ticker, qty, limitPrice, TYPE_LIMIT, clientOrderId);
        if (shortBlock != null) return shortBlock;

        OrderRequest request = new OrderRequest(clientOrderId, ticker, OrderRequest.Side.SELL,
                OrderRequest.Type.LIMIT, qty, limitPrice, null, null,
                OrderRequest.TimeInForce.GTC, false);

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED LIMIT-SELL | ticker={} qty={} limitPrice={} id={}",
                    ticker, qty, limitPrice.toPlainString(), clientOrderId);
            recordSuccess(strategy, "LIMIT-SELL", ticker, qty, limitPrice, TYPE_LIMIT, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, requestSnapshot(request));
        }

        return submit(strategy, "LIMIT-SELL", ticker, qty, limitPrice, TYPE_LIMIT, clientOrderId, request);
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

        // Trailing stops only support DAY — do not override to GTC.
        OrderRequest request = new OrderRequest(clientOrderId, ticker, OrderRequest.Side.SELL,
                OrderRequest.Type.TRAILING_STOP, qty, null, null, trailPct,
                OrderRequest.TimeInForce.DAY, false);

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED TRAIL-STOP-SELL | ticker={} qty={} trail={}% id={}",
                    ticker, qty, trailPct.movePointRight(2).toPlainString(), clientOrderId);
            recordSuccess(strategy, "TRAIL-STOP-SELL", ticker, qty, trailPct,
                    TYPE_TRAILING_STOP_LOSS, clientOrderId, null);
            postOrderRefresh();
            return OrderResult.paper(clientOrderId, requestSnapshot(request));
        }

        return submit(strategy, "TRAIL-STOP-SELL", ticker, qty, trailPct,
                TYPE_TRAILING_STOP_LOSS, clientOrderId, request);
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

        OrderRequest request = OrderRequest.market(clientOrderId, ticker, OrderRequest.Side.SELL, qty);

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED MARKET-SELL | ticker={} qty={} id={}", ticker, qty, clientOrderId);
            recordSuccess(strategy, "MARKET-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId, null);
            return OrderResult.paper(clientOrderId, requestSnapshot(request));
        }

        return submit(strategy, "MARKET-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId, request);
    }

    /**
     * Session-aware EXIT sell used by the exit managers. Subject to the no-short guard,
     * but <b>NOT the spread guard</b> — an exit must never be blocked by a wide or
     * unavailable spread (that would trap the position in exactly the volatile
     * conditions where getting out matters most). Routed by session — REGULAR → MARKET,
     * PRE_MARKET → LIMIT at the current bid (falling back to last when bid is missing).
     */
    public OrderResult placeExitSell(String ticker, int qty, String strategy) {
        String clientOrderId = newClientOrderId();

        OrderResult shortBlock = guardNoShort(strategy, "EXIT-SELL", ticker, qty, null, TYPE_MARKET, clientOrderId);
        if (shortBlock != null) return shortBlock;

        Quote quote = fetchQuote(ticker);
        // NOTE: the spread guard is intentionally NOT applied to exits — see Javadoc.

        MarketHoursGuard.Session session = marketHoursGuard.currentSession();
        OrderRequest request;
        String orderType;
        BigDecimal price;
        if (session == MarketHoursGuard.Session.PRE_MARKET) {
            orderType = TYPE_LIMIT;
            // Prefer the live bid; fall back to last trade so a missing bid can't
            // stop the exit. A pre-market limit still needs SOME price to submit.
            price = quote.bid() != null ? quote.bid() : quote.last();
            if (price == null) {
                log.error("[OrderService] EXIT-SELL {} — no bid/last available pre-market; cannot price limit exit", ticker);
                recordFailure(strategy, "EXIT-SELL", ticker, qty, null, TYPE_LIMIT, clientOrderId, "NO_PRICE_FOR_EXIT");
                return OrderResult.failure(clientOrderId, "NO_PRICE_FOR_EXIT");
            }
            request = new OrderRequest(clientOrderId, ticker, OrderRequest.Side.SELL,
                    OrderRequest.Type.LIMIT, qty, price, null, null,
                    OrderRequest.TimeInForce.DAY, true);
        } else {
            orderType = TYPE_MARKET;
            price = quote.last();
            request = OrderRequest.market(clientOrderId, ticker, OrderRequest.Side.SELL, qty);
        }

        if (!props.shouldSubmitOrders()) {
            log.info("[OrderService] SIMULATED EXIT-SELL | ticker={} qty={} type={} session={} id={}",
                    ticker, qty, orderType, session, clientOrderId);
            recordSuccess(strategy, "EXIT-SELL", ticker, qty, price, orderType, clientOrderId, null);
            return OrderResult.paper(clientOrderId, requestSnapshot(request));
        }

        return submit(strategy, "EXIT-SELL", ticker, qty, price, orderType, clientOrderId, request);
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
                               String clientOrderId, OrderRequest request) {
        String accountId = client.resolveAccountId();
        if (accountId == null) {
            recordFailure(strategy, label, ticker, qty, price, orderType, clientOrderId, "NO_ACCOUNT");
            return new OrderResult(false, clientOrderId, null,
                    "Could not resolve account ID", -1, null, request == null ? null : requestSnapshot(request));
        }
        try {
            log.info("[OrderService] SUBMIT {} | ticker={} id={}", label, ticker, clientOrderId);
            OrderAck ack = client.submitOrder(accountId, request);

            if (ack.success()) {
                log.info("[OrderService] {} accepted | ticker={} orderId={} brokerResponse={}",
                        label, ticker, ack.orderId(), ack.rawResponse());
                recordSuccess(strategy, label, ticker, qty, price, orderType, clientOrderId, ack.orderId());
                postOrderRefresh();
                return new OrderResult(true, clientOrderId, ack.orderId(),
                        "Accepted by Webull", ack.httpStatus(), ack.rawResponse(), ack.requestSent());
            }

            log.error("[OrderService] {} rejected | ticker={} status={} message={}",
                    label, ticker, ack.httpStatus(), ack.message());
            recordFailure(strategy, label, ticker, qty, price, orderType, clientOrderId, ack.message());
            return new OrderResult(false, clientOrderId, null,
                    "Rejected by Webull (status=" + ack.httpStatus() + ")",
                    ack.httpStatus(), ack.rawResponse(), ack.requestSent());

        } catch (Exception e) {
            log.error("[OrderService] {} exception | ticker={}", label, ticker, e);
            recordFailure(strategy, label, ticker, qty, price, orderType, clientOrderId, String.valueOf(e.getMessage()));
            return new OrderResult(false, clientOrderId, null,
                    "Exception: " + e.getMessage(), -1, null, requestSnapshot(request));
        }
    }

    /** A neutral map view of the request, for paper-mode results and error payloads. */
    private static Map<String, Object> requestSnapshot(OrderRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) return m;
        m.put("client_order_id", r.clientOrderId());
        m.put("symbol", r.symbol());
        m.put("side", r.side().name());
        m.put("order_type", r.type().name());
        m.put("quantity", String.valueOf(r.quantity()));
        m.put("time_in_force", r.timeInForce().name());
        m.put("extended_hours", r.extendedHours());
        if (r.limitPrice() != null) m.put("limit_price", r.limitPrice().toPlainString());
        if (r.stopPrice() != null)  m.put("stop_price", r.stopPrice().toPlainString());
        if (r.trailPct() != null)   m.put("trailing_stop_step", r.trailPct().toPlainString());
        return m;
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
    // Fill price / quotes — delegated to the broker adapter (neutral records)
    // -----------------------------------------------------------------------

    /**
     * Resolves the price to anchor a bracket (stop-loss / take-profit) on, after a
     * MARKET buy. A market order has no limit price, so we approximate the fill with
     * the current market price (a market buy fills at ~the current price).
     *
     * <p>Delegates to the broker adapter, which returns the live quote mid (bid/ask)
     * when available, else the last-trade price. If unavailable, the supplied
     * {@code fallback} — normally the signal candle's open — is returned so the
     * strategy can still place its bracket.</p>
     *
     * @param ticker   the symbol
     * @param fallback price returned when the price can't be fetched; may be
     *                 {@code null} when the caller wants to detect an unresolved price
     * @return the resolved price, or {@code fallback} when it can't be resolved
     */
    public BigDecimal fetchFillPrice(String ticker, BigDecimal fallback) {
        return client.fetchFillPrice(ticker, fallback);
    }

    /**
     * Fetches the current bid/ask/last for {@code ticker} from the broker adapter.
     * Returns a {@link Quote} with null fields when unavailable (never null).
     */
    public Quote fetchQuote(String ticker) {
        return client.fetchQuote(ticker);
    }
}
