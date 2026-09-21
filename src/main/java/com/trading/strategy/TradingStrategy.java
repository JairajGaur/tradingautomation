package com.trading.strategy;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pluggable strategy contract.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>On startup, {@link #warmUp(String, List)} is called once per ticker in
 *       the watchlist with the full history of closing prices. Strategies that
 *       need indicator seeding (e.g. EMA) override this method.</li>
 *   <li>Every minute, {@link #onCandle(Candle)} is called for each completed
 *       1-minute bar for each ticker in the watchlist.</li>
 * </ol>
 *
 * <h2>Adding a new strategy</h2>
 * Implement this interface and annotate the class {@code @Service}. The
 * {@link com.trading.service.TradingOrchestrator} discovers all strategy beans
 * automatically via Spring injection — no other wiring is needed.
 *
 * <h2>Enabling / disabling</h2>
 * Each strategy can be enabled or disabled via the
 * {@code webull.strategies.*} flags in {@code application.yml} without
 * touching any code. The orchestrator checks these flags before dispatching.
 */
public interface TradingStrategy {

    /**
     * Called once per ticker on startup with historical closing prices.
     *
     * <p>Strategies that need indicator warm-up (EMA, RSI, etc.) override this.
     * The default implementation is a no-op so simple strategies do not need to
     * implement it.</p>
     *
     * @param ticker          the equity symbol (e.g. {@code "AAPL"})
     * @param historicalCloses closing prices in chronological order (oldest first)
     */
    default void warmUp(String ticker, List<BigDecimal> historicalCloses) {
        // no-op by default
    }

    /**
     * Called once per completed 1-minute candlestick bar.
     *
     * @param candle the freshly closed bar, never {@code null}
     */
    void onCandle(Candle candle);

    /**
     * Human-readable strategy name used in logs.
     *
     * @return strategy name
     */
    String name();
}
