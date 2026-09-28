package com.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory, thread-safe record of every trade the bot triggers — capturing
 * <b>which strategy</b> fired each order, so you can see what happened and why.
 *
 * <p>Entries are also logged at INFO with a clear {@code *** TRADE ***} marker,
 * and exposed via {@code GET /api/trades}. The buffer keeps the most recent
 * {@link #MAX_ENTRIES} trades (older ones are evicted).</p>
 */
@Component
public class TradeLog {

    private static final Logger log = LoggerFactory.getLogger(TradeLog.class);

    /** Maximum retained trade entries (newest kept, oldest evicted). */
    private static final int MAX_ENTRIES = 500;

    /**
     * A single recorded trade event.
     *
     * @param timestamp     when the trade was recorded
     * @param strategy      the strategy that fired it (e.g. "600-EMA Momentum"),
     *                      or a system source like "RISK_MANAGER" / "MANUAL"
     * @param action        BUY / SELL / STOP_SELL / LIMIT_SELL / MARKET_SELL
     * @param ticker        equity symbol
     * @param quantity      shares
     * @param price         reference price (fill estimate or bracket price), may be null
     * @param orderType     MARKET / STOP / LIMIT
     * @param clientOrderId client order id
     * @param orderId       broker order id (null in paper mode)
     * @param mode          PAPER / LIVE
     * @param success       whether the order was accepted
     * @param message       failure reason when not successful, else empty
     */
    public record TradeEntry(
            Instant timestamp,
            String strategy,
            String action,
            String ticker,
            int quantity,
            BigDecimal price,
            String orderType,
            String clientOrderId,
            String orderId,
            String mode,
            boolean success,
            String message
    ) {}

    private final Deque<TradeEntry> entries = new ArrayDeque<>();

    /**
     * Records a trade event. Thread-safe. Also logs a human-readable line.
     */
    public synchronized void record(TradeEntry entry) {
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
        log.info("*** TRADE *** strategy='{}' {} {} x{} @ {} type={} mode={} success={} orderId={} {}",
                entry.strategy(), entry.action(), entry.ticker(), entry.quantity(),
                entry.price() != null ? entry.price().toPlainString() : "n/a",
                entry.orderType(), entry.mode(), entry.success(),
                entry.orderId(), entry.success() ? "" : "reason=" + entry.message());
    }

    /**
     * Returns the most recent trades, newest first, capped at {@code limit}.
     */
    public synchronized List<TradeEntry> recent(int limit) {
        List<TradeEntry> out = new ArrayList<>(Math.min(limit, entries.size()));
        int i = 0;
        for (TradeEntry e : entries) {
            if (i++ >= limit) break;
            out.add(e);
        }
        return out;
    }

    /** Total trades currently retained. */
    public synchronized int size() {
        return entries.size();
    }
}
