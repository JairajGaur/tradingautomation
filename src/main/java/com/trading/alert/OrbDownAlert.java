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
 * Opening-Range-Breakout — DOWN. Alerts when a completed 1-minute candle CLOSES below the
 * low of the day's 09:30–09:35 ET 5-minute opening-range candle. Once per ticker per day.
 * Reads M5 (opening range) and M1 (break) from the shared {@link BarDataManager}.
 */
@Component
public class OrbDownAlert implements Alert {

    private static final Logger log = LoggerFactory.getLogger(OrbDownAlert.class);

    private final WebullProperties props;
    private final BarDataManager barData;

    public OrbDownAlert(WebullProperties props, BarDataManager barData) {
        this.props = props;
        this.barData = barData;
    }

    @Override
    public String id() { return "orb-5m-down"; }

    @Override
    public boolean isEnabled() {
        return props.alerts() != null && props.alerts().orbDown() != null
                && props.alerts().orbDown().enabled();
    }

    @Override
    public String messagePrefix() {
        String p = props.alerts().orbDown().messagePrefix();
        return p == null ? "" : p;
    }

    @Override
    public int scanIntervalMinutes() {
        return Math.max(1, props.alerts().orbDown().scanMinutes());
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

                if (OrbSupport.closesBelow(m1, range.low())) {
                    String state = "BREAKOUT_DOWN_" + day;   // per-day → re-arms each session
                    hits.add(new AlertHit(id(), symbol, state,
                            "⬇️ ORB DOWN " + symbol + " @ " + m1.close().toPlainString()
                                    + "\n1m close broke below the " + rangeMin
                                    + "-min opening range low " + range.low().toPlainString()));
                }
            } catch (Exception e) {
                log.warn("[OrbDownAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        return hits;
    }
}
