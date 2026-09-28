package com.trading.alert;

import java.util.List;

/**
 * A pluggable market alert. Each alert is a self-contained rule that the
 * {@link AlertEngine} runs on a schedule over the ticker universe.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #id()} — stable identifier, used for dedupe keys and logs.</li>
 *   <li>{@link #isEnabled()} — driven by config, checked before each run.</li>
 *   <li>{@link #timeframesNeeded()} — timeframes the engine should batch-prefetch
 *       (via the shared BarDataManager) before calling {@link #evaluate}.</li>
 *   <li>{@link #evaluate(List)} — read bars from the shared BarDataManager ONLY (never
 *       fetch separately) and return the current hits. The engine handles dedupe and
 *       delivery, so an alert simply returns the current state each run.</li>
 * </ul>
 */
public interface Alert {

    /** Stable id (e.g. "st-adx"). */
    String id();

    /** Whether this alert is enabled (config-driven). */
    boolean isEnabled();

    /** This alert's own message prefix, prepended to its notifications (may be empty). */
    String messagePrefix();

    /** Timeframes this alert reads, so the engine can batch-prefetch them once. */
    List<String> timeframesNeeded();

    /**
     * Evaluates the alert across {@code universe} and returns the current hits.
     * Must read bars only from the shared BarDataManager (no separate fetching).
     */
    List<AlertHit> evaluate(List<String> universe);
}
