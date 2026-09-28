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
 * Opening-Range-Breakout — UP. Alerts when a completed 1-minute candle CLOSES above the
 * high of the day's 09:30–09:35 ET 5-minute opening-range candle. Once per ticker per day.
 * Reads M5 (opening range) and M1 (break) from the shared {@link BarDataManager}.
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
    public String id() { return "orb-5m-up"; }

    @Override
    public boolean isEnabled() {
        return props.alerts() != null && props.alerts().orbUp() != null
                && props.alerts().orbUp().enabled();
    }

    @Override
    public String messagePrefix() {
        String p = props.alerts().orbUp().messagePrefix();
        return p == null ? "" : p;
    }

    @Override
    public int scanIntervalMinutes() {
        return Math.max(1, props.alerts().orbUp().scanMinutes());
    }

    @Override
    public List<String> timeframesNeeded() {
        return List.of("M1");   // opening range + break are both derived from M1
    }

    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        int warmup = props.trading().warmupBars();
        int rangeMin = props.alerts().orbRangeMinutes();
        String day = OrbSupport.todayKey();
        List<AlertHit> hits = new ArrayList<>();

        for (String symbol : universe) {
            try {
                List<Candle> m1Bars = barData.getBars(symbol, "M1", warmup);
                OrbSupport.Range range = OrbSupport.openingRange(symbol, m1Bars, rangeMin);
                if (range == null) continue;   // opening range not complete yet today

                Candle m1 = OrbSupport.latestCompletedM1(m1Bars);
                if (m1 == null) continue;

                if (OrbSupport.closesAbove(m1, range.high())) {
                    String state = "BREAKOUT_UP_" + day;   // per-day → re-arms each session
                    hits.add(new AlertHit(id(), symbol, state,
                            "⬆️ ORB UP " + symbol + " @ " + m1.close().toPlainString()
                                    + "\n1m close broke above the " + rangeMin
                                    + "-min opening range high " + range.high().toPlainString()));
                }
            } catch (Exception e) {
                log.warn("[OrbUpAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        return hits;
    }
}
