package com.trading.indicator;

import com.trading.model.Candle;

import java.math.BigDecimal;

/**
 * Reusable candle body-strength checks, shared by all strategies so the definition of
 * a "strong green candle" is identical everywhere (single source of truth).
 */
public final class CandleStrength {

    private CandleStrength() {}

    /**
     * True when {@code bar} is a <b>strong green candle</b>: it closes above its open
     * (green) AND its body is at least {@code minBodyRatio} of the full high-low range
     * (filters out dojis / weak-bodied bars).
     *
     * <p>Degenerate range (high == low) is treated as passing the ratio test, so a
     * flat bar that is still green qualifies; a red or flat-close bar never does.</p>
     *
     * @param bar          the candle to test (never null)
     * @param minBodyRatio minimum body/range fraction (e.g. 0.5 = body ≥ 50% of range)
     */
    public static boolean isStrongGreenBody(Candle bar, BigDecimal minBodyRatio) {
        BigDecimal open  = bar.open();
        BigDecimal close = bar.close();
        boolean green = close.compareTo(open) > 0;
        if (!green) return false;

        BigDecimal range = bar.high().subtract(bar.low());
        if (range.signum() <= 0) return true;   // flat range but green → treat as strong
        BigDecimal body = close.subtract(open);
        return body.compareTo(range.multiply(minBodyRatio)) >= 0;
    }
}
