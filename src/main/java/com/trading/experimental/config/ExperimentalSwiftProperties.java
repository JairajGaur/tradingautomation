package com.trading.experimental.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Config for the EXPERIMENTAL Swift Trend strategy, bound from the {@code experimental.swift}
 * prefix (see {@code application-experimental.yml}, loaded by
 * {@link ExperimentalPropertySourceConfig}).
 *
 * <p><b>Sandbox contract.</b> This lives entirely in {@code com.trading.experimental} and
 * mirrors only the knobs an experimental Swift strategy needs to vary — a faster Supertrend
 * ({@code length}/{@code factor}) and the ATR momentum thresholds. It intentionally does NOT
 * duplicate the whole {@code webull.*} tree: everything else (sessions, market-hours, broker
 * API, warm-up) is reused from the production {@link com.trading.config.WebullProperties}.
 * Production config and code are never modified; the experimental scanner reads these values
 * and otherwise inherits production behaviour.</p>
 *
 * <p>Each field defaults to the current PRODUCTION value, so an unset experimental file
 * reproduces live behaviour exactly — you only override the knob you are testing.</p>
 */
@ConfigurationProperties(prefix = "experimental.swift")
public record ExperimentalSwiftProperties(

        /** Master on/off for the experimental Swift strategy beans (default false). */
        @DefaultValue("false") boolean enabled,

        // ── Supertrend driver (production shares webull.supertrend 7 / 3) ──
        /** Supertrend ATR period. Lower = flips sooner (default 7, matching production). */
        @DefaultValue("7") int supertrendLength,
        /** Supertrend ATR multiplier. Lower = flips sooner (default 3, matching production). */
        @DefaultValue("3") BigDecimal supertrendFactor,

        // ── Swift detection (mirrors webull.alerts.swift-scan knobs) ──
        /** Candle/Supertrend timeframe (M1/M5/M15/M30/M60). Default M5 (production). */
        @DefaultValue("M5") String timeframe,
        /** Per symbol+direction cooldown in minutes (default 30, production). */
        @DefaultValue("30") int cooldownMinutes,
        /** Min price travel since the flip in ATR units. Lower = earlier (default 2). */
        @DefaultValue("2") BigDecimal minTravelAtr,
        @DefaultValue("1.5") BigDecimal minSeparationAtr,
        @DefaultValue("5") int strongCandleWindow,
        @DefaultValue("1") int minStrongCandles,
        @DefaultValue("0.5") BigDecimal bodyMinAtr,
        @DefaultValue("5") int flipWindowBars,
        @DefaultValue("2") int maxFlipsInWindow,
        @DefaultValue("3") int stSlopeLookback,
        @DefaultValue("0.1") BigDecimal minStSlopeAtr,
        @DefaultValue("3.0") BigDecimal maxCandleAtr,

        /** Message prefix for any experimental alert output (default distinct from prod). */
        @DefaultValue("EXP SWIFT - ") String messagePrefix
) {}
