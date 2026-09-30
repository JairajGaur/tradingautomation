package com.trading.alert;

import com.trading.config.WebullProperties;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Opening-Range-Breakout — UP. Fires when, on the {@code scan-minutes} timeframe, a
 * completed candle CLOSES above the opening range HIGH (the 09:30 candle on the
 * {@code orb-range-minutes} timeframe) AND the EMA is rising (EMA(now) ≥ EMA(lookback ago))
 * on the scan-minutes timeframe. Once per ticker per day. Reads both timeframes from the
 * shared {@link BarDataManager}.
 */
@Component
public class OrbUpAlert implements Alert {

    private static final Logger log = LoggerFactory.getLogger(OrbUpAlert.class);

    private final WebullProperties props;
    private final BarDataManager barData;

    public OrbUpAlert(WebullProperties props, BarDataManager barData) {
        this.props = props;
        this.barData = barData;
    }

    @Override
    public String id() { return "orb-up"; }

    @Override
    public boolean isEnabled() {
        return props.alerts() != null && props.alerts().orb() != null
                && props.alerts().orb().up() != null && props.alerts().orb().up().enabled();
    }

    @Override
    public String messagePrefix() {
        String p = props.alerts().orb().up().messagePrefix();
        return p == null ? "" : p;
    }

    @Override
    public int scanIntervalMinutes() {
        return Math.max(1, props.alerts().orb().up().scanMinutes());
    }

    // Transitional (not latched): when price returns INSIDE the range the engine clears
    // dedupe, so a fresh break re-alerts. Re-alerts on re-break are intended.

    private String rangeTf() { return OrbSupport.timeframeForMinutes(props.alerts().orb().rangeMinutes()); }
    private String scanTf()  { return OrbSupport.timeframeForMinutes(props.alerts().orb().up().scanMinutes()); }

    @Override
    public List<String> timeframesNeeded() {
        try { return List.of(rangeTf(), scanTf()); }
        catch (Exception e) { return List.of(); }   // invalid tf config — nothing to prefetch
    }

    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        final String rangeTf, scanTf;
        try { rangeTf = rangeTf(); scanTf = scanTf(); }
        catch (IllegalArgumentException e) {
            log.warn("[OrbUpAlert] invalid timeframe config: {}", e.getMessage());
            return List.of();
        }
        int warmup = props.trading().warmupBars();
        int emaPeriod = props.alerts().orb().emaPeriod();
        int lookback = Math.max(1, props.alerts().orb().emaSlopeLookback());
        String day = OrbSupport.todayKey();
        List<AlertHit> hits = new ArrayList<>();

        for (String symbol : universe) {
            try {
                OrbSupport.Range range = OrbSupport.openingRange(symbol, barData.getBars(symbol, rangeTf, warmup));
                if (range == null) continue;   // opening range not final yet today

                List<Candle> scanBars = barData.getBars(symbol, scanTf, warmup);
                Candle bar = OrbSupport.latestCompleted(scanBars);
                if (bar == null) continue;

                if (!OrbSupport.closesAbove(bar, range.high())) continue;   // no upside break

                // EMA slope confirmation (optional): must be rising (>= over the lookback) for UP.
                if (props.alerts().orb().emaSlopeEnabled()) {
                    BigDecimal slope = OrbSupport.emaSlope(scanBars, emaPeriod, lookback);
                    if (slope == null || slope.signum() < 0) continue;     // falling/insufficient → skip
                }

                String state = "BREAKOUT_UP_" + day;   // per-day (latched by the engine)
                hits.add(new AlertHit(id(), symbol, state,
                        "⬆️ ORB UP " + symbol + " @ " + bar.close().toPlainString()
                                + "\n" + scanTf + " close broke above the " + rangeTf
                                + " opening-range high " + range.high().toPlainString()
                                + "\nema" + emaPeriod + " rising on " + scanTf));
            } catch (Exception e) {
                log.warn("[OrbUpAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        return hits;
    }
}
