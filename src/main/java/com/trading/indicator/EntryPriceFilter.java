package com.trading.indicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reusable "don't buy the high" entry filter, shared by all strategies (single source
 * of truth). Blocks a BUY when price is extended too far above a reference EMA:
 * {@code price > emaRef × (1 + maxExtension)}.
 *
 * <p>Config-gated by the caller (a strategy only invokes this when
 * {@code strategies.no-buy-high-enabled} is true), so it imposes no restriction until
 * explicitly turned on.</p>
 */
public final class EntryPriceFilter {

    private EntryPriceFilter() {}

    /**
     * Returns {@code true} when the buy should be BLOCKED because price is extended too
     * far above the reference EMA.
     *
     * @param closes       completed-bar closes (oldest first) — enough for {@code refEmaPeriod}
     * @param price        the price being evaluated for entry (e.g. last completed close)
     * @param refEmaPeriod reference EMA period (e.g. 200)
     * @param maxExtension max allowed fraction above the reference EMA (e.g. 0.02 = 2%)
     * @return true = too high, block the buy; false = within range, allow
     */
    public static boolean isTooHigh(List<BigDecimal> closes, BigDecimal price,
                                    int refEmaPeriod, BigDecimal maxExtension) {
        if (closes == null || closes.size() < refEmaPeriod || price == null) {
            return false;   // not enough data to judge — don't block on that basis
        }
        BigDecimal refEma = EmaCalculator.calculate(closes, refEmaPeriod);
        BigDecimal ceiling = refEma.multiply(BigDecimal.ONE.add(maxExtension));
        return price.compareTo(ceiling) > 0;
    }
}
