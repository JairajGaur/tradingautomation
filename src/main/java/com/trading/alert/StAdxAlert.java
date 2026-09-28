package com.trading.alert;

import com.trading.config.WebullProperties;
import com.trading.indicator.AdxCalculator;
import com.trading.indicator.AdxCalculator.Advice;
import com.trading.indicator.AdxCalculator.Point;
import com.trading.indicator.AdxCalculator.Recommendation;
import com.trading.indicator.AdxService;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Alert: on the configured timeframe (default M5), flags a ticker
 * <ul>
 *   <li><b>BULLISH</b> when the Supertrend is UP <em>and</em> the ADX recommendation is BUY;</li>
 *   <li><b>BEARISH</b> when the Supertrend is DOWN <em>and</em> the ADX recommendation is SELL.</li>
 * </ul>
 * Reads all bars from the shared {@link BarDataManager} (no separate fetching).
 */
@Component
public class StAdxAlert implements Alert {

    private static final Logger log = LoggerFactory.getLogger(StAdxAlert.class);

    private final WebullProperties props;
    private final BarDataManager barData;
    private final SupertrendService supertrend;
    private final AdxService adx;

    public StAdxAlert(WebullProperties props, BarDataManager barData,
                      SupertrendService supertrend, AdxService adx) {
        this.props = props;
        this.barData = barData;
        this.supertrend = supertrend;
        this.adx = adx;
    }

    @Override
    public String id() { return "st-adx"; }

    @Override
    public boolean isEnabled() {
        return props.alerts() != null && props.alerts().stAdx() != null
                && props.alerts().stAdx().enabled();
    }

    @Override
    public String messagePrefix() {
        String p = props.alerts().stAdx().messagePrefix();
        return p == null ? "" : p;
    }

    @Override
    public List<String> timeframesNeeded() {
        return List.of(tf());
    }

    private String tf() {
        return props.alerts().stAdx().timeframe();
    }

    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        String tf = tf();
        int period = adx.period();
        BigDecimal threshold = adx.threshold();
        int rising = adx.rising();
        int warmup = props.trading().warmupBars();

        List<AlertHit> hits = new ArrayList<>();
        for (String symbol : universe) {
            try {
                List<Candle> bars = barData.getBars(symbol, tf, warmup);
                if (bars == null || bars.size() < 2 * period + 2) continue;

                // Supertrend direction (drops the in-progress bar internally).
                Direction stDir = supertrend.latestCompletedDirection(bars);
                if (stDir == null) continue;

                // ADX recommendation on the latest completed bar.
                List<Candle> completed = bars.subList(0, bars.size() - 1);
                List<Point> pts = AdxCalculator.calculate(completed, period, threshold, rising);
                int latestIdx = -1;
                for (int i = pts.size() - 1; i >= 0; i--) {
                    if (pts.get(i).adx() != null) { latestIdx = i; break; }
                }
                if (latestIdx < 0) continue;
                Point p = pts.get(latestIdx);
                BigDecimal prevAdx = null;
                int back = latestIdx - rising;
                if (back >= 0 && pts.get(back).adx() != null) prevAdx = pts.get(back).adx();
                Advice advice = AdxCalculator.recommend(p, prevAdx);

                String close = completed.get(completed.size() - 1).close().toPlainString();

                boolean bullish = stDir == Direction.UP && advice.action() == Recommendation.BUY;
                boolean bearish = stDir == Direction.DOWN && advice.action() == Recommendation.SELL;

                if (bullish) {
                    hits.add(new AlertHit(id(), symbol, "BULLISH",
                            "🟢 BULLISH " + symbol + " @ " + close
                                    + "\n" + tf + " Supertrend UP + ADX BUY"
                                    + "\n" + advice.reason()));
                } else if (bearish) {
                    hits.add(new AlertHit(id(), symbol, "BEARISH",
                            "🔴 BEARISH " + symbol + " @ " + close
                                    + "\n" + tf + " Supertrend DOWN + ADX SELL"
                                    + "\n" + advice.reason()));
                }
            } catch (Exception e) {
                log.warn("[StAdxAlert] {} {} failed (ignored): {}", symbol, tf, e.getMessage());
            }
        }
        return hits;
    }
}
