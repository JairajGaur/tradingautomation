package com.trading.indicator;

import com.trading.config.WebullProperties;
import com.trading.indicator.AdxCalculator.Direction;
import com.trading.indicator.AdxCalculator.Pattern;
import com.trading.indicator.AdxCalculator.Point;
import com.trading.model.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Injectable ADX / Directional-Movement calculator that carries the configured
 * {@code period}, {@code threshold} and {@code rising} lookback
 * ({@code webull.adx.*}, defaults 14 / 25 / 3), so callers don't pass those
 * parameters around.
 *
 * <p>Delegates the math to the stateless {@link AdxCalculator}. Use this bean for
 * the app's canonical ADX; use the static calculator directly only when you need
 * arbitrary, caller-supplied parameters (e.g. the inspection endpoint).</p>
 */
@Component
public class AdxService {

    private final int period;
    private final BigDecimal threshold;
    private final int rising;

    public AdxService(WebullProperties props) {
        this.period = props.adx().period();
        this.threshold = props.adx().threshold();
        this.rising = props.adx().rising();
    }

    /** Configured DI/ADX lookback. */
    public int period() { return period; }

    /** Configured ADX trend threshold (weak/ranging below, trending at/above). */
    public BigDecimal threshold() { return threshold; }

    /** Configured "rising" lookback (bars back ADX is compared against). */
    public int rising() { return rising; }

    /** Full +DI/−DI/ADX series over {@code candles} using the configured params. */
    public List<Point> calculate(List<Candle> candles) {
        return AdxCalculator.calculate(candles, period, threshold, rising);
    }

    /** Most recent computed point (has an ADX value) with configured params, or null. */
    public Point latest(List<Candle> candles) {
        return AdxCalculator.latest(candles, period);
    }

    /**
     * ADX (trend strength) of the latest <em>completed</em> bar (drops the last,
     * possibly in-progress candle), or {@code null} when it can't be computed.
     *
     * @param candles OHLC candles oldest-first (should include the current bar)
     */
    public BigDecimal latestCompletedAdx(List<Candle> candles) {
        Point p = latestCompleted(candles);
        return p == null ? null : p.adx();
    }

    /**
     * Dominant direction (+DI vs −DI) of the latest <em>completed</em> bar, or
     * {@code null} when it can't be computed.
     */
    public Direction latestCompletedDirection(List<Candle> candles) {
        Point p = latestCompleted(candles);
        return p == null ? null : p.direction();
    }

    /**
     * Trend pattern flagged on the latest <em>completed</em> bar
     * ({@link Pattern#LOW_ADX_RISING} / {@link Pattern#HIGH_ADX_REVERSAL}),
     * or {@code null} when none applies or it can't be computed.
     */
    public Pattern latestCompletedPattern(List<Candle> candles) {
        Point p = latestCompleted(candles);
        return p == null ? null : p.pattern();
    }

    /**
     * The latest computed point (has an ADX value) after dropping the last,
     * possibly in-progress candle, or {@code null} when it can't be computed.
     * ADX needs ~{@code 2*period} bars, so include ample history.
     */
    public Point latestCompleted(List<Candle> candles) {
        if (candles == null || candles.size() < 2 * period + 1) {
            return null;
        }
        List<Candle> completed = candles.subList(0, candles.size() - 1);
        return AdxCalculator.latest(completed, period);
    }
}
