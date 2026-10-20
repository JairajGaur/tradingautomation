package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.SupertrendCalculator;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.BarQuality;
import com.trading.service.TimeframeSupport;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Completed underlying-stock trend confirmation shared by live, inspection and replay. */
@Component
public class OptionStockTrendFilter {
    private final AppProperties props;
    private final BarDataManager bars;

    public OptionStockTrendFilter(AppProperties props, BarDataManager bars) {
        this.props = props;
        this.bars = bars;
    }

    public record Frame(List<Candle> candles, int sourceMinutes, int targetMinutes,
                        int minimum, BarQuality.Policy policy) {
        public List<Candle> completedAt(Instant asOf) {
            var quality = BarQuality.assess(candles, sourceMinutes, asOf,
                    sourceMinutes == targetMinutes ? minimum : 1, policy);
            if (!quality.valid()) return List.of();
            if (sourceMinutes == targetMinutes) return quality.completed();
            var aggregated = BarQuality.completeAggregates(quality.completed(), sourceMinutes, targetMinutes, asOf);
            quality = BarQuality.assess(aggregated, targetMinutes, asOf, minimum,
                    new BarQuality.Policy(policy.maxAgeBars(), policy.graceSeconds(), policy.gapWindow(), 0));
            return quality.valid() ? quality.completed() : List.of();
        }
    }

    public record Series(List<Frame> frames, boolean ema, int fast, int slow) {
        /** Null means unavailable; false is valid data opposing the requested side. */
        public Boolean matches(OptionContract.Type side, Instant asOf) {
            if (frames.isEmpty()) return null;
            boolean matches = true;
            for (var frame : frames) {
                var completed = frame.completedAt(asOf);
                if (completed.isEmpty()) return null;
                int direction;
                if (ema) {
                    var closes = completed.stream().map(Candle::close).toList();
                    direction = EmaCalculator.calculate(closes, fast).compareTo(EmaCalculator.calculate(closes, slow));
                } else {
                    // Preserve the previous higher-ST calculation parameters when moving to stocks.
                    var point = SupertrendCalculator.latest(completed, 3, BigDecimal.valueOf(3));
                    if (point == null || point.supertrend() == null || point.direction() == null) return null;
                    direction = point.direction() == SupertrendCalculator.Direction.UP ? 1 : -1;
                }
                matches &= side == OptionContract.Type.CALL ? direction > 0 : direction < 0;
            }
            return matches;
        }
    }

    /** Replay loads history once; every evaluation validates only the historical completed prefix. */
    public Series load(String underlying, boolean ema) {
        return load(underlying, ema, false);
    }

    private Series load(String underlying, boolean ema, boolean refresh) {
        return load(underlying, ema, refresh, false);
    }

    private Series load(String underlying, boolean ema, boolean refresh, boolean lowestOnly) {
        var cfg = props.alerts().optionVolSt();
        int fast = Math.max(1, cfg.stockEmaStackFast()), slow = Math.max(1, cfg.stockEmaStackSlow());
        try {
            var frames = new ArrayList<Frame>();
            var timeframes = TimeframeSupport.minuteTimeframes(
                    ema ? cfg.stockEmaStackTimeframe() : cfg.stockHigherStTimeframe(), ema ? cfg.timeframe() : "M3");
            if (lowestOnly) timeframes = List.of(lowestEmaTimeframe());
            for (String tf : timeframes) {
                int minutes = TimeframeSupport.minutesOf(tf);
                String source = TimeframeSupport.isSynthetic(tf) ? "M1" : tf;
                int sourceMinutes = TimeframeSupport.minutesOf(source);
                int minimum = ema ? Math.max(fast, slow) : 3;
                long needed = (long) minimum * (minutes / sourceMinutes) + minutes / sourceMinutes;
                int count = (int) Math.min(1650L, Math.max(props.engine().warmupBars(), needed));
                var candles = refresh ? bars.refreshBars(underlying, source, 1650)
                        : bars.getBars(underlying, source, count);
                frames.add(new Frame(candles, sourceMinutes, minutes, minimum,
                        BarQuality.Policy.from(cfg.barQuality())));
            }
            return new Series(List.copyOf(frames), ema, fast, slow);
        } catch (Exception ex) {
            LoggerFactory.getLogger(OptionStockTrendFilter.class).warn(
                    "Stock {} {} unavailable: {}", underlying, ema ? "EMA" : "higher ST", ex.getMessage());
            return new Series(List.of(), ema, fast, slow);
        }
    }

    /** Load only the lowest stock EMA interval for repeat-alert state, even if confirmation is off. */
    public Series latchSeries(String underlying, Instant asOf) {
        var series = loadLowest(underlying, false);
        if (series.frames().isEmpty() || series.frames().get(0).completedAt(asOf).isEmpty())
            series = loadLowest(underlying, true);
        return series;
    }

    public Series loadLowest(String underlying, boolean refresh) {
        return load(underlying, true, refresh, true);
    }

    public String lowestEmaTimeframe() {
        var c = props.alerts().optionVolSt();
        return TimeframeSupport.minuteTimeframes(c.stockEmaStackTimeframe(), c.timeframe()).stream()
                .min(java.util.Comparator.comparingInt(TimeframeSupport::minutesOf)).orElseThrow();
    }

    public boolean current(String underlying, OptionContract.Type side, Instant asOf, boolean ema) {
        Boolean result = load(underlying, ema).matches(side, asOf);
        if (result == null) {
            // Shared stock cache bounds refresh to one extra fetch per symbol/timeframe/minute.
            result = load(underlying, ema, true).matches(side, asOf);
        }
        return Boolean.TRUE.equals(result);
    }
}
