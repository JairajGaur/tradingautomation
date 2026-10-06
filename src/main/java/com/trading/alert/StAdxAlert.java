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
 * Alert flagging a ticker
 * <ul>
 *   <li><b>BULLISH</b> when the Supertrend is UP on ALL configured
 *       {@code supertrend-timeframes} <em>and</em> the ADX recommendation (on the first,
 *       primary timeframe) is BUY;</li>
 *   <li><b>BEARISH</b> when the Supertrend is DOWN on all timeframes <em>and</em> ADX is SELL.</li>
 * </ul>
 * {@code supertrend-timeframes} is comma-separated ("M5" or "M5,M15"); "M5" is the
 * single-timeframe behaviour. Reads all bars from the shared {@link BarDataManager}.
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
    public int scanIntervalMinutes() {
        return Math.max(1, props.alerts().stAdx().scanMinutes());
    }

    /** Parsed, validated Supertrend timeframes; first entry is the primary (ADX + price). */
    private List<String> timeframes() {
        List<String> out = new ArrayList<>();
        for (String raw : props.alerts().stAdx().supertrendTimeframes().split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(com.trading.service.MarketDataService.normaliseTimespan(t));
            } catch (Exception e) {
                log.warn("[StAdxAlert] invalid Supertrend timeframe '{}' — ignoring", t);
            }
        }
        return out;
    }

    @Override
    public List<String> timeframesNeeded() {
        List<String> tfs = new ArrayList<>(timeframes());
        // Also prefetch the price-vs-EMA gate's timeframe when enabled.
        var cfg = props.alerts().stAdx();
        if (cfg.emaEnabled()) {
            try {
                String emaTf = com.trading.service.MarketDataService.normaliseTimespan(cfg.emaTimeframe());
                if (!tfs.contains(emaTf)) tfs.add(emaTf);
            } catch (Exception ignored) { }
        }
        return tfs;   // engine batch-prefetches all of them
    }

    /**
     * Price-vs-EMA gate. Returns +1 if the latest close on {@code emaTimeframe} is above
     * EMA(emaPeriod) (bullish-ok), -1 if below (bearish-ok), 0 if unavailable/insufficient.
     */
    private int priceVsEma(String symbol, int warmup) {
        var cfg = props.alerts().stAdx();
        try {
            String emaTf = com.trading.service.MarketDataService.normaliseTimespan(cfg.emaTimeframe());
            List<Candle> bars = barData.getBars(symbol, emaTf, warmup);
            if (bars == null || bars.size() < cfg.emaPeriod() + 2) return 0;
            List<Candle> completed = bars.subList(0, bars.size() - 1);
            List<BigDecimal> closes = new ArrayList<>(completed.size());
            for (Candle c : completed) closes.add(c.close());
            if (closes.size() < cfg.emaPeriod()) return 0;
            BigDecimal ema = com.trading.indicator.EmaCalculator.calculate(closes, cfg.emaPeriod());
            BigDecimal close = closes.get(closes.size() - 1);
            return close.compareTo(ema) > 0 ? 1 : (close.compareTo(ema) < 0 ? -1 : 0);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        List<String> tfs = timeframes();
        if (tfs.isEmpty()) return List.of();
        String primaryTf = tfs.get(0);   // ADX + message price come from the primary tf
        int period = adx.period();
        BigDecimal threshold = adx.threshold();
        int rising = adx.rising();
        int warmup = props.trading().warmupBars();

        List<AlertHit> hits = new ArrayList<>();
        for (String symbol : universe) {
            try {
                List<Candle> primaryBars = barData.getBars(symbol, primaryTf, warmup);
                if (primaryBars == null || primaryBars.size() < 2 * period + 2) continue;

                // ADX recommendation on the PRIMARY timeframe's latest completed bar.
                List<Candle> completed = primaryBars.subList(0, primaryBars.size() - 1);
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

                // Supertrend must AGREE on ALL listed timeframes.
                Direction agreed = supertrendAgreement(symbol, tfs, warmup);
                if (agreed == null) continue;   // unavailable or timeframes disagree

                boolean bullish = agreed == Direction.UP && advice.action() == Recommendation.BUY;
                boolean bearish = agreed == Direction.DOWN && advice.action() == Recommendation.SELL;

                // Optional price-vs-EMA gate: BULLISH needs price ABOVE the EMA, BEARISH below.
                if (props.alerts().stAdx().emaEnabled()) {
                    int side = priceVsEma(symbol, warmup);
                    if (bullish && side <= 0) bullish = false;   // not above EMA → drop
                    if (bearish && side >= 0) bearish = false;   // not below EMA → drop
                }

                if (bullish) {
                    hits.add(new AlertHit(id(), symbol, "BULLISH",
                            "🟢 BULLISH " + symbol + " @ " + close
                                    + "\nSupertrend UP on " + tfs + " + ADX BUY (" + primaryTf + ")"
                                    + "\n" + advice.reason()));
                } else if (bearish) {
                    hits.add(new AlertHit(id(), symbol, "BEARISH",
                            "🔴 BEARISH " + symbol + " @ " + close
                                    + "\nSupertrend DOWN on " + tfs + " + ADX SELL (" + primaryTf + ")"
                                    + "\n" + advice.reason()));
                }
            } catch (Exception e) {
                log.warn("[StAdxAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        return hits;
    }

    /**
     * Returns the single direction the Supertrend agrees on across ALL {@code tfs}, or
     * null if any timeframe is unavailable or they don't all match.
     */
    private Direction supertrendAgreement(String symbol, List<String> tfs, int warmup) {
        Direction agreed = null;
        for (String tf : tfs) {
            Direction d = supertrend.latestCompletedDirection(barData.getBars(symbol, tf, warmup));
            if (d == null) return null;              // can't confirm this tf
            if (agreed == null) agreed = d;
            else if (agreed != d) return null;       // timeframes disagree
        }
        return agreed;
    }
}
