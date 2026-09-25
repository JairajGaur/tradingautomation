package com.trading.service;

/**
 * Contract for a position exit manager. Exactly ONE implementation is active at a
 * time, selected by {@code webull.exit.manager} (STAGED | ST_CROSS). The
 * {@link TradingOrchestrator} invokes the configured manager for every open
 * position each minute.
 *
 * <p>Implementations own the full sell decision for a position: evaluating the
 * exit rule, reconciling against live holdings, placing the exit order, and
 * recording the re-trade cooldown on close.</p>
 */
public interface PositionExitManager {

    /** Config key under {@code webull.exit.manager} that selects this manager. */
    enum Kind { STAGED, ST_CROSS }

    /** The kind this manager implements (used by the orchestrator to select it). */
    Kind kind();

    /** Evaluates and, if warranted, exits a single ticker's open position. */
    void evaluate(String ticker);
}
