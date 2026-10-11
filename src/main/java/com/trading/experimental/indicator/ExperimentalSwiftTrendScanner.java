package com.trading.experimental.indicator;

import com.trading.config.WebullProperties;
import com.trading.experimental.config.ExperimentalSwiftProperties;
import com.trading.indicator.SupertrendService;
import com.trading.indicator.SwiftTrendScanner;
import com.trading.service.MarketHoursGuard;

import java.math.BigDecimal;

/**
 * EXPERIMENTAL Swift Trend scanner. Subclasses the production {@link SwiftTrendScanner} and
 * overrides ONLY the three parameter seams — {@link #length()}, {@link #factor()}, and
 * {@link #config()} — so it reuses the entire production detection + firing pipeline
 * ({@code detect()}, {@code evaluate()}, the static {@code decide()} latch/cooldown rule, the
 * RTH gate) unchanged, but feeds it faster Supertrend params and tuned thresholds from
 * {@link ExperimentalSwiftProperties}.
 *
 * <p><b>Why this works without touching production.</b> {@code detect()} calls the overridable
 * {@code length()/factor()/thresholds()}, and {@code thresholds()} delegates to
 * {@code thresholds(config())}. By overriding {@code config()} to return a
 * {@link WebullProperties.Alerts.SwiftScanCfg} built from the experimental properties, every
 * downstream threshold is picked up automatically — no production class is modified and the
 * canonical firing rule stays the single source of truth.</p>
 *
 * <p><b>Bean registration.</b> This is NOT a {@code @Component}. It is declared as an
 * {@code autowireCandidate=false} bean in {@link ExperimentalSwiftConfig} so it never competes
 * with the production {@code SwiftTrendScanner} for type-based injection — existing injection
 * points (e.g. the live {@code SwiftTrendAlert}, the production backtest controller) keep
 * resolving to the single production bean. The experimental backtest controller fetches this
 * one explicitly by its bean name. Both coexist, enabling side-by-side comparison.</p>
 */
public class ExperimentalSwiftTrendScanner extends SwiftTrendScanner {

    private final ExperimentalSwiftProperties exp;

    public ExperimentalSwiftTrendScanner(WebullProperties props,
                                         SupertrendService supertrend,
                                         MarketHoursGuard marketHoursGuard,
                                         ExperimentalSwiftProperties exp) {
        // Reuse the production constructor so shared deps (market-hours, props) wire identically.
        super(props, supertrend, marketHoursGuard);
        this.exp = exp;
    }

    /** Faster Supertrend period from experimental config (production uses webull.supertrend). */
    @Override
    public int length() {
        return Math.max(1, exp.supertrendLength());
    }

    /** Faster Supertrend multiplier from experimental config. */
    @Override
    public BigDecimal factor() {
        return exp.supertrendFactor();
    }

    /**
     * Returns a {@link WebullProperties.Alerts.SwiftScanCfg} assembled from the experimental
     * thresholds, so {@code thresholds()} / {@code detect()} use the experimental knobs while
     * all the mapping/clamping logic in the production {@code thresholds(cfg)} is reused as-is.
     */
    @Override
    public WebullProperties.Alerts.SwiftScanCfg config() {
        return new WebullProperties.Alerts.SwiftScanCfg(
                exp.enabled(),
                exp.timeframe(),
                1,                          // scanMinutes — unused by the backtest path
                exp.messagePrefix(),
                exp.cooldownMinutes(),
                "",                         // telegramChatId — experimental never self-delivers
                exp.minTravelAtr(),
                exp.minSeparationAtr(),
                exp.strongCandleWindow(),
                exp.minStrongCandles(),
                exp.bodyMinAtr(),
                exp.flipWindowBars(),
                exp.maxFlipsInWindow(),
                exp.stSlopeLookback(),
                exp.minStSlopeAtr(),
                exp.maxCandleAtr());
    }
}
