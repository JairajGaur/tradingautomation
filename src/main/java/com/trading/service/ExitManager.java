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
 * <p>A position is sold at market (full quantity) when ANY of these hits first,
 * checked on the exit timeframe's latest completed bar:</p>
 * <ol>
 *   <li><b>Stop-loss</b> — current price ≤ entry × (1 − {@code exit.stop-loss-pct}), default −10%.</li>
 *   <li><b>EMA bearish state</b> — {@code emaFast} EMA is below {@code emaSlow} EMA
 *       (a state check, not just the crossing bar — so it still exits when the cross
 *       happened earlier and remains bearish).</li>
 *   <li><b>Below previous low</b> — current price is below the previous completed
 *       candle's low.</li>
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
    private final AccountService accountService;
    private final TradeCooldown tradeCooldown;

    // Per-ticker breakeven stop level, armed once price reaches the trigger profit.
    // Present = armed; value = the stop price (entry + buffer).
    private final java.util.Map<String, java.math.BigDecimal> breakevenStop =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ExitManager(WebullProperties props,
                       BarDataManager barData,
                       PositionTracker positionTracker,
                       @Lazy OrderService orderService,
                       @Lazy AccountService accountService,
                       TradeCooldown tradeCooldown) {
        this.props = props;
        this.barData = barData;
        this.positionTracker = positionTracker;
        this.orderService = orderService;
        this.accountService = accountService;
        this.tradeCooldown = tradeCooldown;
    }

    /** Evaluates and, if warranted, exits a single ticker's open position. */
    public void evaluate(String ticker) {
        Position pos = positionTracker.getPosition(ticker).orElse(null);
        if (pos == null || pos.quantity() <= 0) return;

        // Reconcile against the real account first: if the position was closed
        // OUTSIDE the bot (e.g. sold manually in the Webull app), Webull will report
        // it as no longer held → drop the stale tracker entry. Only act on a
        // CONFIRMED zero; a failed positions call (HELD_UNKNOWN) is NOT treated as
        // closed, so a transient API error can't wrongly drop a real position.
        int liveHeld = accountService.getHeldQuantity(ticker);
        if (liveHeld == AccountService.HELD_UNKNOWN) {
            log.warn("[ExitManager] ticker={} — live holdings unknown (positions call failed); "
                    + "skipping this tick, keeping position tracked", ticker);
            return;
        }
        if (liveHeld == 0) {
            log.info("[ExitManager] ticker={} no longer held on Webull (tracked={}) — "
                    + "clearing stale tracker entry (closed externally)", ticker, pos.quantity());
            positionTracker.closePosition(ticker);
            breakevenStop.remove(ticker);
            tradeCooldown.record(ticker);   // externally closed also starts the cooldown
            return;
        }

        if (!props.exit().enabled()) return;

        String reason = exitReason(ticker, pos);
        if (reason == null) return;

        log.warn("[ExitManager] EXIT ticker={} qty={} entry={} — reason={}",
                ticker, pos.quantity(), pos.entryPrice(), reason);

        OrderService.OrderResult result =
                orderService.placeExitSell(ticker, pos.quantity(), "EXIT_" + reason);
        if (result.success()) {
            positionTracker.closePosition(ticker);
            breakevenStop.remove(ticker);
            tradeCooldown.record(ticker);   // start the re-trade cooldown on exit
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

        BigDecimal price = currentPrice(ticker);
        BigDecimal entry = pos.entryPrice();

        if (price != null && entry != null && entry.signum() > 0) {
            // 1. Hard stop-loss vs entry.
            BigDecimal floor = entry.multiply(BigDecimal.ONE.subtract(props.exit().stopLossPct()));
            if (price.compareTo(floor) <= 0) {
                return "STOP_LOSS(price=" + price + "<=floor=" + floor + ")";
            }

            // 2. Breakeven stop. Arm it once price reaches the trigger profit, at
            //    entry + buffer (buffer = current spread, min configured). Once armed,
            //    exit if price drops to/below that level — locks a tiny profit so a
            //    pullback can't turn the winner into a loss.
            BigDecimal beStop = breakevenStop.get(ticker);
            if (beStop == null) {
                BigDecimal trigger = entry.multiply(BigDecimal.ONE.add(props.exit().breakevenTriggerPct()));
                if (price.compareTo(trigger) >= 0) {
                    BigDecimal buffer = currentSpread(ticker).max(props.exit().breakevenMinBufferUsd());
                    beStop = entry.add(buffer);
                    breakevenStop.put(ticker, beStop);
                    log.info("[ExitManager] {} — breakeven stop ARMED at {} (entry={} +buffer={}); price={}",
                            ticker, beStop, entry, buffer, price);
                }
            } else if (price.compareTo(beStop) <= 0) {
                return "BREAKEVEN_STOP(price=" + price + "<=" + beStop + ")";
            }
        }

        int fast = props.exit().emaFast();
        int slow = props.exit().emaSlow();
        List<Candle> bars = barData.getBars(ticker, tf, slow + 100);
        int n = bars.size();
        if (n < slow + 2) return null;
        // Completed bars only (drop the last, possibly in-progress).
        List<Candle> completed = bars.subList(0, n - 1);
        Candle prevBar = completed.get(completed.size() - 1);   // latest COMPLETED bar

        // 2. EMA STATE: fast below slow (not just the crossing bar) → bearish → exit.
        //    Catches the case where the cross happened earlier and stays bearish.
        List<BigDecimal> closes = closes(completed);
        if (closes.size() >= slow) {
            BigDecimal fastEma = EmaCalculator.calculate(closes, fast);
            BigDecimal slowEma = EmaCalculator.calculate(closes, slow);
            if (fastEma.compareTo(slowEma) < 0) {
                return "EMA_BEARISH(ema" + fast + "<ema" + slow + " on " + tf + ")";
            }
        }

        // 3. Price broke below the previous completed candle's low → exit.
        if (price != null && price.compareTo(prevBar.low()) < 0) {
            return "BELOW_PREV_LOW(price=" + price + "<prevLow=" + prevBar.low() + " on " + tf + ")";
        }

        return null;
    }

    private BigDecimal currentPrice(String ticker) {
        // Reuse OrderService's snapshot-based price resolver (null when unavailable).
        return orderService.fetchFillPrice(ticker, null);
    }

    /** Current bid/ask spread ($) for the breakeven buffer; 0 when unavailable. */
    private BigDecimal currentSpread(String ticker) {
        OrderService.Quote q = orderService.fetchQuote(ticker);
        BigDecimal s = q.spread();
        return s != null ? s : BigDecimal.ZERO;
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        java.util.List<BigDecimal> out = new java.util.ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }
}
