package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.indicator.HeikinAshiCalculator;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import com.trading.service.TimeframeSupport;
import com.trading.service.BarQuality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Underlying-stock HA direction, shared by live evaluation, inspection and replay. */
@Component
public class OptionHaDirectionFilter {
    private static final Logger log = LoggerFactory.getLogger(OptionHaDirectionFilter.class);
    private final AppProperties props;
    private final BarDataManager bars;

    public OptionHaDirectionFilter(AppProperties props, BarDataManager bars) {
        this.props = props;
        this.bars = bars;
    }

    public record Direction(String color, Instant candleStart, OptionContract.Type allowedSide) {
        public boolean allows(OptionContract.Type side) { return side == allowedSide; }
    }

    public record Series(List<Candle> candles, int minutes, int minimum, BarQuality.Policy policy, int sourceMinutes) {
        public Series(List<Candle> candles, int minutes) {
            this(candles, minutes, 1, BarQuality.Policy.defaults(), minutes);
        }
        /** Select only candles fully closed at the evaluation instant; never use future color. */
        public Direction at(Instant asOf) {
            var quality = BarQuality.assess(candles, sourceMinutes, asOf,
                    sourceMinutes == minutes ? minimum : 1, policy);
            if (!quality.valid()) return new Direction("UNAVAILABLE", null, null);
            List<Candle> completed = quality.completed();
            if (sourceMinutes != minutes) {
                completed = BarQuality.completeAggregates(completed, sourceMinutes, minutes, asOf);
                quality = BarQuality.assess(completed, minutes, asOf, minimum,
                        new BarQuality.Policy(policy.maxAgeBars(), policy.graceSeconds(), policy.gapWindow(), 0));
                if (!quality.valid()) return new Direction("UNAVAILABLE", null, null);
                completed = quality.completed();
            }
            // Transform the validated historical prefix only: future bars cannot alter its seed.
            var ha = HeikinAshiCalculator.calculate(completed);
            Candle latest = ha.get(ha.size() - 1);
            int compare = latest.close().compareTo(latest.open());
            return new Direction(compare > 0 ? "GREEN" : compare < 0 ? "RED" : "DOJI",
                    latest.timestamp(), compare > 0 ? OptionContract.Type.CALL
                            : compare < 0 ? OptionContract.Type.PUT : null);
        }
    }

    /** Load once for replay; per-bar validation uses historical evaluation times. */
    public Series load(String underlying) {
        try {
            String timeframe = timeframe();
            List<Candle> source = bars.getBars(underlying, sourceTimeframe(timeframe), count(timeframe));
            return series(source, timeframe);
        } catch (Exception e) {
            log.warn("[OptionHaDirectionFilter] {} HA unavailable: {}", underlying, e.getMessage());
            return new Series(List.of(), 1);
        }
    }

    /** Live recovery is bounded by the shared cache's one-retry-per-minute budget. */
    public Direction current(String underlying, Instant asOf) {
        Direction direction = load(underlying).at(asOf);
        if (!"UNAVAILABLE".equals(direction.color())) return direction;
        try {
            String timeframe = timeframe();
            log.info("[OptionHaDirectionFilter] Recovering {} {} HA history", underlying, timeframe);
            return series(bars.refreshBars(underlying, sourceTimeframe(timeframe), 1650), timeframe).at(asOf);
        } catch (Exception e) {
            log.warn("[OptionHaDirectionFilter] {} recovery unavailable: {}", underlying, e.getMessage());
            return direction;
        }
    }

    private String timeframe() {
        String tf = MarketDataService.normaliseTimespan(props.alerts().optionVolSt().haTimeframe());
        if (TimeframeSupport.minutesOf(tf) <= 0) throw new IllegalArgumentException("HA timeframe must be a minute timeframe");
        return tf;
    }

    private int minimum() {
        var quality = props.alerts().optionVolSt().barQuality();
        return quality == null ? 20 : Math.max(1, quality.haWarmupBars());
    }

    private String sourceTimeframe(String tf) { return TimeframeSupport.isSynthetic(tf) ? "M1" : tf; }

    private int count(String tf) {
        int count = Math.max(props.engine().warmupBars(), minimum() + 1);
        return (int) Math.min(1650L, TimeframeSupport.isSynthetic(tf)
                ? (long) count * TimeframeSupport.minutesOf(tf) : count);
    }

    private Series series(List<Candle> source, String tf) {
        return new Series(source, TimeframeSupport.minutesOf(tf), minimum(),
                BarQuality.Policy.from(props.alerts().optionVolSt().barQuality()),
                TimeframeSupport.minutesOf(sourceTimeframe(tf)));
    }
}
