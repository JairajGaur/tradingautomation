package com.trading.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of open positions.
 *
 * The 600-EMA strategy uses this to prevent duplicate BUY orders while a
 * position for the same ticker is already active.
 *
 * A position is tracked from the moment a BUY fill is confirmed until
 * either the stop-loss or take-profit exit order is acknowledged as filled.
 */
@Component
public class PositionTracker {

    private static final Logger log = LoggerFactory.getLogger(PositionTracker.class);

    /**
     * Snapshot of an open position for a single ticker.
     *
     * @param ticker      the equity symbol (e.g. "AAPL")
     * @param entryPrice  the actual fill price of the BUY order
     * @param quantity    number of shares held
     * @param stopPrice   the stop-loss trigger price (2 % below entry)
     * @param targetPrice the take-profit limit price (5 % above entry)
     * @param clientOrderId the client order ID of the BUY that opened this position
     */
    public record Position(
            String ticker,
            BigDecimal entryPrice,
            int quantity,
            BigDecimal stopPrice,
            BigDecimal targetPrice,
            String clientOrderId
    ) {}

    /** ticker → open position */
    private final Map<String, Position> openPositions = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Mutating operations
    // -----------------------------------------------------------------------

    /**
     * Records a newly filled BUY position.
     *
     * @return {@code true} if the position was added (i.e. no existing position
     *         for this ticker); {@code false} if one was already tracked (no-op).
     */
    public boolean openPosition(Position position) {
        Position previous = openPositions.putIfAbsent(position.ticker(), position);
        if (previous == null) {
            log.info("[PositionTracker] Opened position: ticker={} entry={} stop={} target={} qty={}",
                    position.ticker(), position.entryPrice(),
                    position.stopPrice(), position.targetPrice(), position.quantity());
            return true;
        }
        log.warn("[PositionTracker] Attempted to open duplicate position for ticker={} — ignored", position.ticker());
        return false;
    }

    /**
     * Removes a closed position (stop-loss or take-profit hit).
     *
     * @param ticker the equity symbol
     * @return the removed {@link Position}, or empty if none existed
     */
    public Optional<Position> closePosition(String ticker) {
        Position removed = openPositions.remove(ticker);
        if (removed != null) {
            log.info("[PositionTracker] Closed position for ticker={}", ticker);
            return Optional.of(removed);
        }
        log.debug("[PositionTracker] closePosition called for {} but no open position found", ticker);
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Query operations
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when a BUY position for {@code ticker} is currently open.
     *
     * This is the primary guard checked by the strategy before placing any new order.
     */
    public boolean hasOpenPosition(String ticker) {
        return openPositions.containsKey(ticker);
    }

    /**
     * Returns the open position for a ticker, if one exists.
     */
    public Optional<Position> getPosition(String ticker) {
        return Optional.ofNullable(openPositions.get(ticker));
    }

    /**
     * Returns an unmodifiable snapshot of all currently tracked positions.
     */
    public Map<String, Position> allPositions() {
        return Collections.unmodifiableMap(openPositions);
    }
}
