package com.trading.indicator;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless utility that converts regular OHLC {@link Candle}s into
 * <b>Heikin-Ashi</b> candles.
 *
 * <h2>Formulas</h2>
 * <ul>
 *   <li>{@code HA_close = (open + high + low + close) / 4}</li>
 *   <li>{@code HA_open  = (prevHA_open + prevHA_close) / 2}
 *       — the FIRST bar seeds with {@code (open + close) / 2} since there is no prior HA bar.</li>
 *   <li>{@code HA_high  = max(high, HA_open, HA_close)}</li>
 *   <li>{@code HA_low   = min(low,  HA_open, HA_close)}</li>
 * </ul>
 *
 * <p>Heikin-Ashi (“average bar”) smooths price by folding each real bar into a running
 * average, so trends read as a run of same-colour candles with little/no opposite wick.
 * The transform is causal (each HA bar depends only on the current real bar and the prior
 * HA bar), so it never looks ahead.</p>
 *
 * <h2>Conventions (match the rest of the codebase)</h2>
 * <ul>
 *   <li>Input candles are <b>oldest-first</b>; output is one HA bar per input bar, same order.</li>
 *   <li>Prices use {@link BigDecimal} with {@link MathContext#DECIMAL128}; results are scaled to
 *       8 dp (same {@code PRICE_SCALE} as the other calculators).</li>
 *   <li>The HA bar carries the SOURCE candle's {@code ticker}, {@code timestamp} and
 *       {@code volume} unchanged — only OHLC is transformed — so an HA bar is itself a valid
 *       {@link Candle} that downstream indicators can consume if desired.</li>
 *   <li>Early warm-up has no special case: HA begins on the first bar via the seed above. The
 *       first few HA bars drift until the running average settles, exactly as charting platforms
 *       render them.</li>
 * </ul>
 *
 * <p>This works for ANY timeframe: the caller resamples/fetches native (M1/M5/…) or synthetic
 * (M2/M3) bars via the market-data layer and passes them in; the HA transform is timeframe-
 * agnostic.</p>
 */
public final class HeikinAshiCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int PRICE_SCALE = 8;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);

    private HeikinAshiCalculator() {
        // utility class — do not instantiate
    }

    /**
     * Transforms {@code candles} (oldest-first) into Heikin-Ashi candles.
     *
     * @param candles OHLC candles in chronological order (oldest first)
     * @return one HA {@link Candle} per input candle, in the same order; empty when input is
     *         null/empty
     */
    public static List<Candle> calculate(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return List.of();

        List<Candle> out = new ArrayList<>(candles.size());
        BigDecimal prevHaOpen = null;
        BigDecimal prevHaClose = null;

        for (Candle c : candles) {
            BigDecimal o = c.open(), h = c.high(), l = c.low(), cl = c.close();

            // HA_close = average of the real OHLC.
            BigDecimal haClose = o.add(h, MC).add(l, MC).add(cl, MC).divide(FOUR, MC);

            // HA_open = average of the prior HA open/close; seed on the first bar.
            BigDecimal haOpen = (prevHaOpen == null || prevHaClose == null)
                    ? o.add(cl, MC).divide(TWO, MC)
                    : prevHaOpen.add(prevHaClose, MC).divide(TWO, MC);

            // HA_high / HA_low extend to include the HA open/close.
            BigDecimal haHigh = h.max(haOpen).max(haClose);
            BigDecimal haLow = l.min(haOpen).min(haClose);

            out.add(new Candle(
                    c.ticker(),
                    c.timestamp(),
                    haOpen.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    haHigh.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    haLow.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    haClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    c.volume()));

            prevHaOpen = haOpen;
            prevHaClose = haClose;
        }
        return out;
    }
}
