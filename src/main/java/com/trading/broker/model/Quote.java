package com.trading.broker.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Broker-neutral live quote: bid, ask, last. Any field may be {@code null} when
 * unavailable.
 *
 * <p>This is the domain shape the application reasons about (spread guard, fill
 * pricing, pre-market limit pricing). Each broker adapter is responsible for
 * producing a {@code Quote} from its own snapshot/quote payload — the parsing
 * never leaks past the adapter.</p>
 */
public record Quote(BigDecimal bid, BigDecimal ask, BigDecimal last) {

    /** An all-null quote, used when no data is available. */
    public static final Quote EMPTY = new Quote(null, null, null);

    public boolean hasBidAsk() {
        return bid != null && ask != null
                && bid.compareTo(BigDecimal.ZERO) > 0 && ask.compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal spread() {
        return hasBidAsk() ? ask.subtract(bid) : null;
    }

    /** Spread as a fraction of the mid price: (ask − bid) / ((ask + bid) / 2). Null when no quote. */
    public BigDecimal spreadPct() {
        if (!hasBidAsk()) return null;
        BigDecimal mid = ask.add(bid).divide(BigDecimal.valueOf(2), MathContext.DECIMAL128);
        if (mid.signum() <= 0) return null;
        return ask.subtract(bid).divide(mid, 8, RoundingMode.HALF_UP);
    }
}
