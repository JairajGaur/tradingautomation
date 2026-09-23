package com.trading.service;

import com.trading.config.WebullProperties;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Entry volume filter shared by all strategies: only allow a BUY when the
 * average volume is <b>increasing</b>.
 *
 * <p>Measured on 1-minute bars (B2 — recent-half vs older-half): over the last
 * {@code lookback} completed bars, the average volume of the most recent half must
 * exceed the average of the older half. This is smoother than a bar-over-bar
 * comparison. Uses the shared {@link BarDataManager} cache — no extra Webull calls.</p>
 */
@Service
public class VolumeFilter {

    private static final Logger log = LoggerFactory.getLogger(VolumeFilter.class);

    private static final String TF = "M1";

    private final WebullProperties props;
    private final BarDataManager barData;

    public VolumeFilter(WebullProperties props, BarDataManager barData) {
        this.props = props;
        this.barData = barData;
    }

    /**
     * Returns true when a BUY is allowed for {@code ticker} by the volume filter.
     * When the filter is disabled, always true.
     */
    public boolean isVolumeIncreasing(String ticker) {
        if (!props.strategies().volumeFilterEnabled()) {
            return true;
        }
        int lookback = props.strategies().volumeLookback();
        int half = lookback / 2;
        if (half < 1) return true;   // lookback too small to split — no-op

        // Need `lookback` completed bars; fetch lookback + 1 (drop the in-progress bar).
        List<Candle> bars = barData.getBars(ticker, TF, lookback + 1);
        if (bars.size() < lookback + 1) {
            log.info("[VolumeFilter] {} — not enough M1 bars ({}) for {}-bar volume check; blocking BUY",
                    ticker, bars.size(), lookback);
            return false;
        }

        // Drop the last (possibly in-progress) bar, then take the last `lookback` completed bars.
        List<Candle> completed = bars.subList(0, bars.size() - 1);
        List<Candle> window = completed.subList(completed.size() - lookback, completed.size());

        // Older half = first `half` bars; recent half = last `half` bars.
        double olderAvg  = avgVolume(window, 0, half);
        double recentAvg = avgVolume(window, window.size() - half, window.size());

        boolean increasing = recentAvg > olderAvg;
        if (!increasing) {
            log.info("[VolumeFilter] {} — volume NOT increasing (recent {}-bar avg={} <= older avg={}); blocking BUY",
                    ticker, half, recentAvg, olderAvg);
        } else {
            log.debug("[VolumeFilter] {} — volume increasing (recent avg={} > older avg={})",
                    ticker, recentAvg, olderAvg);
        }
        return increasing;
    }

    /** Mean volume of {@code bars[fromInclusive, toExclusive)}. */
    private static double avgVolume(List<Candle> bars, int fromInclusive, int toExclusive) {
        long sum = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            sum += bars.get(i).volume();
        }
        int count = toExclusive - fromInclusive;
        return count == 0 ? 0 : (double) sum / count;
    }
}
