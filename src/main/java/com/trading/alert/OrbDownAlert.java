package com.trading.alert;

import com.trading.config.AppProperties;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Opening-Range-Breakout — DOWN. Fires when, on the {@code scan-minutes} timeframe, a
 * completed candle CLOSES below the opening range LOW (the 09:30 candle on the
 * {@code orb-range-minutes} timeframe). An optional EMA-slope confirmation (falling) applies
 * when {@code orb.ema-slope-enabled}.
 * <p>
 * An optional Supertrend-line-slope confluence applies when {@code orb.st-slope-confirm-enabled}:
 * the break fires only if the Supertrend LINE has been advancing DOWN on any of the last
 * {@code orb.st-slope-bars} bars (on {@code orb.st-slope-timeframe}, using the shared
 * {@code webull.supertrend} length/factor). Reads all timeframes from the shared
 * {@link BarDataManager}.
 */
@Component
public class OrbDownAlert implements Alert {

    private static final Logger log = LoggerFactory.getLogger(OrbDownAlert.class);

    private final AppProperties props;
    private final BarDataManager barData;
    private final SupertrendService supertrend;

    public OrbDownAlert(AppProperties props, BarDataManager barData, SupertrendService supertrend) {
        this.props = props;
        this.barData = barData;
        this.supertrend = supertrend;
    }

    @Override
    public String id() { return "orb-down"; }

    @Override
    public boolean isEnabled() {
        return props.alerts() != null && props.alerts().orb() != null
                && props.alerts().orb().down() != null && props.alerts().orb().down().enabled();
    }

    @Override
    public String messagePrefix() {
        String p = props.alerts().orb().down().messagePrefix();
        return p == null ? "" : p;
    }

    @Override
    public int scanIntervalMinutes() {
        return Math.max(1, props.alerts().orb().down().scanMinutes());
    }

    /** This direction's channel, falling back to the ORB-parent channel; null if neither set. */
    @Override
    public String channelId() {
        return OrbSupport.resolveChannel(
                props.alerts().orb().down().telegramChatId(),
                props.alerts().orb().telegramChatId());
    }

    // Transitional (not latched): when price returns INSIDE the range the engine clears
    // dedupe, so a fresh break re-alerts. Re-alerts on re-break are intended.

    private String rangeTf() { return OrbSupport.timeframeForMinutes(props.alerts().orb().rangeMinutes()); }
    private String scanTf()  { return OrbSupport.timeframeForMinutes(props.alerts().orb().down().scanMinutes()); }

    /** The Supertrend-slope gate's timeframe, or null when the gate is off/misconfigured. */
    private String stSlopeTf() {
        if (!props.alerts().orb().stSlopeConfirmEnabled()) return null;
        try { return MarketDataService.normaliseTimespan(props.alerts().orb().stSlopeTimeframe()); }
        catch (Exception e) { return null; }
    }

    @Override
    public List<String> timeframesNeeded() {
        try {
            String stTf = stSlopeTf();
            if (stTf == null) return List.of(rangeTf(), scanTf());
            return List.of(rangeTf(), scanTf(), stTf);
        }
        catch (Exception e) { return List.of(); }
    }

    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        final String rangeTf, scanTf;
        try { rangeTf = rangeTf(); scanTf = scanTf(); }
        catch (IllegalArgumentException e) {
            log.warn("[OrbDownAlert] invalid timeframe config: {}", e.getMessage());
            return List.of();
        }
        int warmup = props.engine().warmupBars();
        int emaPeriod = props.alerts().orb().emaPeriod();
        int lookback = Math.max(1, props.alerts().orb().emaSlopeLookback());
        boolean stSlopeGate = props.alerts().orb().stSlopeConfirmEnabled();
        String stTf = stSlopeTf();
        int stLookback = props.alerts().orb().stSlopeLookback();
        BigDecimal stMinAtr = props.alerts().orb().stSlopeMinAtr();
        int stBars = props.alerts().orb().stSlopeBars();
        String day = OrbSupport.todayKey();
        List<AlertHit> hits = new ArrayList<>();

        for (String symbol : universe) {
            try {
                OrbSupport.Range range = OrbSupport.openingRange(symbol, barData.getBars(symbol, rangeTf, warmup));
                if (range == null) continue;

                List<Candle> scanBars = barData.getBars(symbol, scanTf, warmup);
                Candle bar = OrbSupport.latestCompleted(scanBars);
                if (bar == null) continue;

                boolean brokeOut = OrbSupport.closesBelow(bar, range.low());
                boolean insideRange = !brokeOut;   // DOWN: inside = not below the low

                // Advance / resolve the breakout leg (see OrbUpAlert for the rationale): a leg
                // opens only on a fresh break after a re-entry; a stale extended break keeps the
                // same leg (deduped); a close back inside the range re-arms the next leg.
                OrbSupport.LegState legState = legStateFor(symbol, day);
                OrbSupport.LegResult lr = OrbSupport.advanceLeg(legState, brokeOut, insideRange);
                if (lr.leg() == 0) continue;   // inside the range → not a breakout; no hit

                if (lr.newLeg()) {
                    boolean pass = true;
                    if (props.alerts().orb().emaSlopeEnabled()) {
                        BigDecimal slope = OrbSupport.emaSlope(scanBars, emaPeriod, lookback);
                        if (slope == null || slope.signum() > 0) pass = false;   // not falling
                    }
                    if (pass && stSlopeGate && stTf != null) {
                        List<Candle> stBarsSeries = barData.getBars(symbol, stTf, warmup);
                        if (!supertrend.slopeConfirms(stBarsSeries, Direction.DOWN, stLookback, stMinAtr, stBars)) {
                            pass = false;   // Supertrend not advancing down
                        }
                    }
                    if (!pass) {
                        OrbSupport.rollbackLeg(legState);
                        continue;
                    }
                }

                String state = "BREAKOUT_DOWN_" + day + "_" + lr.leg();
                hits.add(new AlertHit(id(), symbol, state,
                        AlertMessageFormat.line(messagePrefix(), symbol,
                                AlertMessageFormat.Dir.DOWN, bar.close().toPlainString(), scanTf),
                        AlertMessageFormat.Dir.DOWN, bar.close(), scanTf));
            } catch (Exception e) {
                log.warn("[OrbDownAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        return hits;
    }

    /** Per-ticker breakout-leg state for the day (singleton-scoped). */
    private final java.util.Map<String, OrbSupport.LegState> legStates = new java.util.concurrent.ConcurrentHashMap<>();

    private OrbSupport.LegState legStateFor(String symbol, String day) {
        return legStates.computeIfAbsent(symbol + "|" + day, k -> new OrbSupport.LegState());
    }
}
