package com.trading.service;

import com.trading.config.WebullProperties;
import com.trading.state.PositionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central risk gate — enforces per-day drawdown limits and buying-power checks.
 *
 * <h2>Daily drawdown</h2>
 * On startup (or on the first call to {@link #seedStartOfDayEquity}), the current
 * net-liquidation value is recorded as the start-of-day baseline.  After every
 * order, {@link #evaluateDrawdown} compares the latest equity to the baseline.
 * If the loss exceeds {@code webull.risk.max-daily-drawdown-pct} (default 10 %):
 * <ol>
 *   <li>The {@code tradingHalted} flag is set to {@code true}.</li>
 *   <li>A flat-close is triggered for every position tracked in
 *       {@link PositionTracker} via market SELL orders.</li>
 *   <li>All subsequent candle ticks are rejected for the rest of the day.</li>
 * </ol>
 * The halt resets at midnight (or when {@link #resetForNewDay()} is called).
 *
 * <h2>Buying-power guard</h2>
 * {@link #hasSufficientBuyingPower} compares the account's available cash to
 * {@code webull.risk.min-buying-power-usd} (default $100). Strategies call this
 * before any BUY signal; {@link OrderService} also checks it as a safety net.
 */
@Service
public class RiskManager {

    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);
    private static final MathContext MC = MathContext.DECIMAL128;

    private final WebullProperties props;
    private final AccountService accountService;
    private final PositionTracker positionTracker;
    private final OrderService orderService;

    /** True when daily drawdown limit has been breached — no new orders allowed. */
    private final AtomicBoolean tradingHalted = new AtomicBoolean(false);

    /** Start-of-day net liquidation value used as the drawdown baseline. */
    private final AtomicReference<BigDecimal> startOfDayEquity =
            new AtomicReference<>(BigDecimal.ZERO);

    public RiskManager(WebullProperties props,
                       AccountService accountService,
                       PositionTracker positionTracker,
                       OrderService orderService) {
        this.props = props;
        this.accountService = accountService;
        this.positionTracker = positionTracker;
        this.orderService = orderService;
    }

    // -----------------------------------------------------------------------
    // Public gate checks
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when trading is currently halted due to a drawdown breach.
     * Callers must check this before dispatching any candle to strategies.
     */
    public boolean isTradingHalted() {
        return tradingHalted.get();
    }

    /**
     * Returns {@code true} when available buying power is above the configured minimum.
     *
     * @param buyingPower the current buying power from the account snapshot
     */
    public boolean hasSufficientBuyingPower(BigDecimal buyingPower) {
        BigDecimal min = props.risk().minBuyingPowerUsd();
        boolean sufficient = buyingPower.compareTo(min) >= 0;
        if (!sufficient) {
            log.warn("[RiskManager] Insufficient buying power: available={} required>={}",
                    buyingPower, min);
        }
        return sufficient;
    }

    // -----------------------------------------------------------------------
    // Drawdown evaluation
    // -----------------------------------------------------------------------

    /**
     * Seeds the start-of-day equity baseline.
     * Called once on bot startup after the account snapshot has been fetched.
     *
     * @param equity the net liquidation value at start of trading day
     */
    public void seedStartOfDayEquity(BigDecimal equity) {
        startOfDayEquity.set(equity);
        log.info("[RiskManager] Start-of-day equity set to {}", equity);
    }

    /**
     * Evaluates whether the current account equity represents a drawdown breach.
     * If the drawdown limit is exceeded, halts trading and closes all open positions.
     *
     * <p>Call this after every order or on each minute tick.</p>
     *
     * @param currentEquity the latest net liquidation value from the account snapshot
     */
    public void evaluateDrawdown(BigDecimal currentEquity) {
        if (tradingHalted.get()) return;  // already halted

        BigDecimal start = startOfDayEquity.get();
        if (start.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[RiskManager] Start-of-day equity not yet seeded — skipping drawdown check");
            return;
        }

        // drawdown = (start - current) / start
        BigDecimal loss     = start.subtract(currentEquity, MC);
        BigDecimal drawdown = loss.divide(start, MC).setScale(6, RoundingMode.HALF_UP);

        BigDecimal limit = props.risk().maxDailyDrawdownPct();

        log.debug("[RiskManager] Drawdown check: start={} current={} drawdown={}% limit={}%",
                start, currentEquity,
                drawdown.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                limit.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));

        if (drawdown.compareTo(limit) >= 0) {
            haltAndCloseAll(drawdown, limit);
        }
    }

    /**
     * Resets the halt flag and clears the start-of-day equity baseline.
     * Call this at the start of each new trading day.
     */
    public void resetForNewDay() {
        tradingHalted.set(false);
        startOfDayEquity.set(BigDecimal.ZERO);
        log.info("[RiskManager] Daily reset — trading re-enabled");
    }

    // -----------------------------------------------------------------------
    // Internal: halt and close all positions
    // -----------------------------------------------------------------------

    private void haltAndCloseAll(BigDecimal drawdown, BigDecimal limit) {
        tradingHalted.set(true);

        log.error("[RiskManager] *** DAILY DRAWDOWN LIMIT BREACHED ***  " +
                        "drawdown={}%  limit={}%  — HALTING ALL TRADING FOR THE DAY",
                drawdown.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                limit.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));

        // Close every tracked open position with a market SELL
        var openPositions = positionTracker.allPositions();
        if (openPositions.isEmpty()) {
            log.info("[RiskManager] No open positions to close.");
            return;
        }

        log.warn("[RiskManager] Closing {} open position(s): {}",
                openPositions.size(), openPositions.keySet());

        for (var entry : openPositions.entrySet()) {
            String ticker = entry.getKey();
            int qty       = entry.getValue().quantity();
            try {
                // Market SELL to flatten position immediately
                OrderService.OrderResult result =
                        orderService.placeMarketSell(ticker, qty);
                if (result.success()) {
                    positionTracker.closePosition(ticker);
                    log.info("[RiskManager] Emergency SELL accepted: ticker={} qty={}", ticker, qty);
                } else {
                    log.error("[RiskManager] Emergency SELL failed for ticker={}: {}",
                            ticker, result.message());
                }
            } catch (Exception e) {
                log.error("[RiskManager] Exception during emergency SELL for ticker={}", ticker, e);
            }
        }

        log.warn("[RiskManager] All positions processed. Bot will NOT trade again today.");
    }
}
