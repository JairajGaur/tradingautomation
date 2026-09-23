package com.trading.indicator;

import com.trading.config.WebullProperties;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.model.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Injectable Supertrend calculator that carries the configured {@code length} and
 * {@code factor} ({@code webull.supertrend.length} / {@code factor}, defaults 7 / 3),
 * so callers don't pass those parameters around.
 *
 * <p>Delegates the math to the stateless {@link SupertrendCalculator}. Use this bean
 * for the app's canonical Supertrend; use the static calculator directly only when
 * you need arbitrary, caller-supplied parameters (e.g. the inspection endpoint).</p>
 */
@Component
public class SupertrendService {

    private final int length;
    private final BigDecimal factor;

    public SupertrendService(WebullProperties props) {
        this.length = props.supertrend().length();
        this.factor = props.supertrend().factor();
    }

    /** Configured ATR period. */
    public int length() { return length; }

    /** Configured ATR multiplier. */
    public BigDecimal factor() { return factor; }

    /** Full Supertrend series over {@code candles} using the configured params. */
    public List<Point> calculate(List<Candle> candles) {
        return SupertrendCalculator.calculate(candles, length, factor);
    }

    /** Most recent computed Supertrend point (configured params), or null. */
    public Point latest(List<Candle> candles) {
        return SupertrendCalculator.latest(candles, length, factor);
    }

    /**
     * Direction of the latest <em>completed</em> bar (drops the last, possibly
     * in-progress candle), or {@code null} when it can't be computed.
     *
     * @param candles OHLC candles oldest-first (should include the current bar)
     */
    public Direction latestCompletedDirection(List<Candle> candles) {
        if (candles == null || candles.size() < length + 1) {
            return null;
        }
        List<Candle> completed = candles.subList(0, candles.size() - 1);
        Point p = SupertrendCalculator.latest(completed, length, factor);
        return p == null ? null : p.direction();
    }
}
