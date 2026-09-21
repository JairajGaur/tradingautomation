package com.trading.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable representation of a single OHLCV candlestick bar.
 *
 * All price fields use {@link BigDecimal} to prevent floating-point rounding
 * errors in indicator calculations.
 */
public record Candle(
        String ticker,
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {

    /**
     * Compact constructor — validates required fields.
     */
    public Candle {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Candle ticker must not be blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Candle timestamp must not be null");
        }
        if (close == null || close.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Candle close price must be positive");
        }
    }
}
