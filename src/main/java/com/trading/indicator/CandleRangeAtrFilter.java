package com.trading.indicator;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Shared Swift Trend candle-range cap; ATR is the evaluated completed bar's Wilder ATR. */
public final class CandleRangeAtrFilter {
    private CandleRangeAtrFilter() {}

    public record Result(boolean passes, BigDecimal rangeAtr, String reason) {}

    public static Result evaluate(Candle candle, BigDecimal atr, BigDecimal maximum) {
        if (maximum == null || maximum.signum() <= 0) return new Result(true, null, null);
        if (candle == null || candle.high() == null || candle.low() == null || atr == null || atr.signum() <= 0) {
            return new Result(false, null, "atr unavailable");
        }
        BigDecimal ratio = candle.high().subtract(candle.low(), MathContext.DECIMAL128)
                .divide(atr, MathContext.DECIMAL128).setScale(4, RoundingMode.HALF_UP);
        return ratio.compareTo(maximum) > 0
                ? new Result(false, ratio, "climax bar: range " + ratio + "×ATR > " + maximum)
                : new Result(true, ratio, null);
    }
}
