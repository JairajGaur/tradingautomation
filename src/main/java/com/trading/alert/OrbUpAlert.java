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

    private String orbTf() {
        // Validate against supported timespans; fall back to M5 on a bad value (e.g. M10).
        try {
            return com.trading.service.MarketDataService.normaliseTimespan(props.alerts().orbTimeframe());
        } catch (Exception e) {
            log.warn("[OrbUpAlert] invalid orb-timeframe '{}' — using M5", props.alerts().orbTimeframe());
            return "M5";
        }
    }

    @Override
    public List<String> timeframesNeeded() {
        return List.of(orbTf(), "M1");
    }

    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        int warmup = props.trading().warmupBars();
        String day = OrbSupport.todayKey();
        String orTf = orbTf();
        List<AlertHit> hits = new ArrayList<>();

        for (String symbol : universe) {
            try {
                Candle or = OrbSupport.openingRangeCandle(barData.getBars(symbol, orTf, warmup));
                if (or == null) continue;   // no 09:30 candle yet today
                BigDecimal orHigh = or.high();

                Candle m1 = OrbSupport.latestCompletedM1(barData.getBars(symbol, "M1", warmup));
                if (m1 == null) continue;

                if (OrbSupport.closesAbove(m1, orHigh)) {
                    // State includes the day so it re-arms each new session.
                    String state = "BREAKOUT_UP_" + day;
                    hits.add(new AlertHit(id(), symbol, state,
                            "⬆️ ORB UP " + symbol + " @ " + m1.close().toPlainString()
                                    + "\n1m close broke above the " + orTf
                                    + " opening range high " + orHigh.toPlainString()));
                }
            } catch (Exception e) {
                log.warn("[OrbUpAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        return hits;
    }
}
