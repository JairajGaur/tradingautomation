package com.trading.service;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;
import com.trading.state.PositionTracker;
import com.trading.state.PositionTracker.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Universal EXIT rule for every bot-opened position, independent of the strategy
 * that entered it. Evaluated every minute (on the configured exit timeframe).
 *
 * <p>A position is sold at market (full quantity) when EITHER:</p>
 * <ol>
 *   <li><b>Stop-loss</b> — current price ≤ entry × (1 − {@code exit.stop-loss-pct})
 *       (default −10%); or</li>
 *   <li><b>Bearish cross</b> — a fresh downward cross on the exit timeframe:
 *       EMA{@code emaFast} was ≥ EMA{@code emaSlow} on the previous completed bar and
 *       is now below it (default 8-EMA crossing under 20-EMA).</li>
 * </ol>
 */
@Service
public class ExitManager {

    private static final Logger log = LoggerFactory.getLogger(ExitManager.class);

    private static final String CATEGORY_US_STOCK = "US_STOCK";

    private final WebullProperties props;
    private final BarDataManager barData;
    private final PositionTracker positionTracker;
    private final OrderService orderService;

    public ExitManager(WebullProperties props,
                       BarDataManager barData,
                       PositionTracker positionTracker,
                       @Lazy OrderService orderService) {
        this.props = props;
        this.barData = barData;
        this.positionTracker = positionTracker;
        this.orderService = orderService;
    }

    /** Evaluates and, if warranted, exits a single ticker's open position. */
    public void evaluate(String ticker) {
        if (!props.exit().enabled()) return;

        Position pos = positionTracker.getPosition(ticker).orElse(null);
        if (pos == null || pos.quantity() <= 0) return;

        String reason = exitReason(ticker, pos);
        if (reason == null) return;

        log.warn("[ExitManager] EXIT ticker={} qty={} entry={} — reason={}",
                ticker, pos.quantity(), pos.entryPrice(), reason);

        OrderService.OrderResult result =
                orderService.placeMarketSell(ticker, pos.quantity(), "EXIT_" + reason);
        if (result.success()) {
            positionTracker.closePosition(ticker);
            log.info("[ExitManager] Closed ticker={} ({})", ticker, reason);
        } else {
            log.error("[ExitManager] Exit SELL failed for ticker={}: {}", ticker, result.message());
        }
    }

    /**
     * Returns a short reason string when the position should be exited, else null.
     * Checks the stop-loss first (cheap, uses current price), then the EMA cross.
     */
    private String exitReason(String ticker, Position pos) {
        String tf = props.exit().timeframe();

        // 1. Stop-loss vs current price.
        BigDecimal price = currentPrice(ticker);
        if (price != null && pos.entryPrice() != null && pos.entryPrice().signum() > 0) {
            BigDecimal floor = pos.entryPrice()
                    .multiply(BigDecimal.ONE.subtract(props.exit().stopLossPct()));
            if (price.compareTo(floor) <= 0) {
                return "STOP_LOSS(price=" + price + "<=floor=" + floor + ")";
            }
        }

        // 2. Fresh bearish EMA cross on the exit timeframe (fast below slow).
        int fast = props.exit().emaFast();
        int slow = props.exit().emaSlow();
        List<Candle> bars = barData.getBars(ticker, tf, slow + 100);
        // Use completed bars only (drop the last, possibly in-progress).
        int n = bars.size();
        if (n < slow + 2) return null;
        List<Candle> completed = bars.subList(0, n - 1);
        int c = completed.size();

        // Closes for "current completed bar" and "previous completed bar".
        List<BigDecimal> closesNow = closes(completed);
        List<BigDecimal> closesPrev = closesNow.subList(0, closesNow.size() - 1);
        if (closesPrev.size() < slow) return null;

        BigDecimal fastNow  = EmaCalculator.calculate(closesNow, fast);
        BigDecimal slowNow  = EmaCalculator.calculate(closesNow, slow);
        BigDecimal fastPrev = EmaCalculator.calculate(closesPrev, fast);
        BigDecimal slowPrev = EmaCalculator.calculate(closesPrev, slow);

        boolean wasAtOrAbove = fastPrev.compareTo(slowPrev) >= 0;
        boolean nowBelow     = fastNow.compareTo(slowNow) < 0;
        if (wasAtOrAbove && nowBelow) {
            return "BEARISH_CROSS(ema" + fast + "<ema" + slow + " on " + tf + ")";
        }
        return null;
    }

    private BigDecimal currentPrice(String ticker) {
        // Reuse OrderService's snapshot-based price resolver (null when unavailable).
        return orderService.fetchFillPrice(ticker, null);
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        java.util.List<BigDecimal> out = new java.util.ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }
}
