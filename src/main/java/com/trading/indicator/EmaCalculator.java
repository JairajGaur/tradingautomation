package com.trading.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Stateless utility for computing an Exponential Moving Average (EMA)
 * over a list of price values.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Seed the EMA with the Simple Moving Average (SMA) of the first
 *       {@code period} values.</li>
 *   <li>For each subsequent value apply:
 *       {@code EMA = close * k + previousEma * (1 - k)}
 *       where {@code k = 2 / (period + 1)}</li>
 * </ol>
 *
 * All arithmetic uses {@link BigDecimal} with {@link MathContext#DECIMAL128}
 * precision (34 significant digits) so rounding errors do not accumulate over
 * 600 periods.
 */
public final class EmaCalculator {

    /** Precision context: 34 significant digits, HALF_UP rounding. */
    private static final MathContext MC = MathContext.DECIMAL128;

    /** Final price scale for result values (8 decimal places). */
    private static final int PRICE_SCALE = 8;

    private EmaCalculator() {
        // utility class — do not instantiate
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Computes the EMA value at the last position in {@code prices}.
     *
     * <p>This is the "full series" overload — useful for warm-up on startup
     * where all historical bars are available at once.</p>
     *
     * @param prices list of closing prices in chronological order (oldest first)
     * @param period EMA period (e.g. 600)
     * @return the EMA at the final data point
     * @throws IllegalArgumentException if the list has fewer than {@code period} elements
     */
    public static BigDecimal calculate(List<BigDecimal> prices, int period) {
        validateInputs(prices, period);

        BigDecimal multiplier = computeMultiplier(period);

        // Seed with the SMA of the first `period` values
        BigDecimal ema = sma(prices.subList(0, period));

        // Apply EMA formula for every value after the seed window
        for (int i = period; i < prices.size(); i++) {
            ema = applyEmaFormula(prices.get(i), ema, multiplier);
        }

        return ema.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Incrementally updates an existing EMA with one new price value.
     *
     * <p>Use this during the live candle loop after warm-up is complete.
     * Keep the returned value as {@code previousEma} for the next call.</p>
     *
     * @param newPrice    the latest closing price
     * @param previousEma the EMA value from the previous bar
     * @param period      EMA period (must match the period used during warm-up)
     * @return the updated EMA value
     */
    public static BigDecimal update(BigDecimal newPrice, BigDecimal previousEma, int period) {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("newPrice must be a positive value");
        }
        if (previousEma == null || previousEma.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("previousEma must be a positive value");
        }
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1");
        }
        BigDecimal multiplier = computeMultiplier(period);
        return applyEmaFormula(newPrice, previousEma, multiplier)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Smoothing multiplier: k = 2 / (period + 1)
     */
    static BigDecimal computeMultiplier(int period) {
        BigDecimal two = BigDecimal.valueOf(2);
        BigDecimal periodPlusOne = BigDecimal.valueOf((long) period + 1);
        return two.divide(periodPlusOne, MC);
    }

    /**
     * EMA = close * k + prevEma * (1 - k)
     */
    private static BigDecimal applyEmaFormula(BigDecimal close,
                                               BigDecimal prevEma,
                                               BigDecimal multiplier) {
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(multiplier, MC);
        return close.multiply(multiplier, MC)
                    .add(prevEma.multiply(oneMinusK, MC), MC);
    }

    /**
     * Simple arithmetic mean of a sub-list.
     */
    static BigDecimal sma(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute SMA of an empty list");
        }
        BigDecimal sum = values.stream()
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        return sum.divide(BigDecimal.valueOf(values.size()), MC);
    }

    private static void validateInputs(List<BigDecimal> prices, int period) {
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("prices must not be null or empty");
        }
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1");
        }
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                    "Not enough data: need at least " + period + " prices but got " + prices.size());
        }
    }
}
