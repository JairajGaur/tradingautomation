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
import java.math.RoundingMode;
import java.util.List;

/**
 * Universal EXIT rule for every bot-opened position, independent of the strategy
 * that entered it. Evaluated every minute (on the configured exit timeframe).
 *
 * <p>Uses a staged TRAILING stop — small bounded risk on losers, room for winners to
 * run the trend. The effective stop only ever ratchets UP. A position is sold at
 * market (full quantity) when ANY of these hits first, checked on the exit timeframe's
 * latest completed bar:</p>
 * <ol>
 *   <li><b>Hard stop</b> — price ≤ entry × (1 − {@code exit.stop-loss-pct}), default −2%.</li>
 *   <li><b>Trailing stop</b> — the ratcheted stop: breakeven (entry + buffer) once price
 *       reaches +{@code breakeven-trigger-pct}, then the previous completed candle's low
 *       once price reaches +{@code trail-arm-pct}. Sells when price falls to/through it.</li>
 *   <li><b>EMA bearish state</b> — {@code emaFast} EMA is below {@code emaSlow} EMA
 *       (a state check, not just the crossing bar — so it still exits when the cross
 *       happened earlier and remains bearish).</li>
 * </ol>
 */
@Service
public class StagedTrailExitManager implements PositionExitManager {

    private static final Logger log = LoggerFactory.getLogger(StagedTrailExitManager.class);

    private static final String CATEGORY_US_STOCK = "US_STOCK";

    @Override
    public Kind kind() { return Kind.STAGED; }

    private final WebullProperties props;
    private final BarDataManager barData;
    private final PositionTracker positionTracker;
    private final OrderService orderService;
    private final AccountService accountService;
    private final TradeCooldown tradeCooldown;

    // Per-ticker effective stop level. Starts unset (only the -stop-loss-pct hard
    // stop applies); once breakeven arms it holds entry+buffer; once the structure
    // trail arms it ratchets up to each higher previous-candle low. Only ever moves UP.
    private final java.util.Map<String, java.math.BigDecimal> stopLevel =
            new java.util.concurrent.ConcurrentHashMap<>();

    public StagedTrailExitManager(WebullProperties props,
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
    @Override
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
            stopLevel.remove(ticker);
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
            stopLevel.remove(ticker);
            tradeCooldown.record(ticker);   // start the re-trade cooldown on exit
            log.info("[ExitManager] Closed ticker={} ({})", ticker, reason);
        } else {
            log.error("[ExitManager] Exit SELL failed for ticker={}: {}", ticker, result.message());
        }
    }

    /**
     * Returns a short reason string when the position should be exited, else null.
     *
     * <p>Staged trailing stop, evaluated on the exit timeframe's latest COMPLETED bar:</p>
     * <ol>
     *   <li><b>Hard stop</b> — price ≤ entry × (1 − stopLossPct). Bounded backstop.</li>
     *   <li><b>Ratchet</b> — raise the effective stop as the trade proves itself:
     *       breakeven (entry + buffer) at +breakevenTriggerPct, then the previous
     *       candle's low at +trailArmPct. The stop only ever moves UP.</li>
     *   <li><b>Trailing stop hit</b> — price ≤ the ratcheted stop level.</li>
     *   <li><b>EMA-bearish state</b> — emaFast below emaSlow (trend-failure catch).</li>
     * </ol>
     */
    private String exitReason(String ticker, Position pos) {
        String tf = props.exit().timeframe();

        BigDecimal price = currentPrice(ticker);
        BigDecimal entry = pos.entryPrice();

        // Need the latest completed bar for the structure trail.
        int fast = props.exit().emaFast();
        int slow = props.exit().emaSlow();
        List<Candle> bars = barData.getBars(ticker, tf, slow + 100);
        int n = bars.size();
        if (n < slow + 2) return null;
        // Completed bars only (drop the last, possibly in-progress).
        List<Candle> completed = bars.subList(0, n - 1);
        Candle prevBar = completed.get(completed.size() - 1);   // latest COMPLETED bar

        if (price != null && entry != null && entry.signum() > 0) {
            // 1. Hard stop-loss vs entry — the bounded worst-case backstop.
            BigDecimal floor = entry.multiply(BigDecimal.ONE.subtract(props.exit().stopLossPct()));
            if (price.compareTo(floor) <= 0) {
                return "STOP_LOSS(price=" + price + "<=floor=" + floor + ")";
            }

            // 2. Ratchet the effective stop UP as the trade earns it. Never lowers.
            BigDecimal gainFrac = price.subtract(entry)
                    .divide(entry, 8, RoundingMode.HALF_UP);      // (price-entry)/entry
            BigDecimal current = stopLevel.get(ticker);
            BigDecimal candidate = current;

            // 2a. Breakeven: at +breakevenTriggerPct, lift stop to entry + buffer.
            if (gainFrac.compareTo(props.exit().breakevenTriggerPct()) >= 0) {
                BigDecimal buffer = currentSpread(ticker).max(props.exit().breakevenMinBufferUsd());
                candidate = maxNullable(candidate, entry.add(buffer));
            }
            // 2b. Structure trail: at +trailArmPct, trail the previous candle's low.
            if (gainFrac.compareTo(props.exit().trailArmPct()) >= 0) {
                candidate = maxNullable(candidate, prevBar.low());
            }

            // Commit only upward moves (or the first arming).
            if (candidate != null && (current == null || candidate.compareTo(current) > 0)) {
                stopLevel.put(ticker, candidate);
                log.info("[ExitManager] {} — stop ratcheted to {} (entry={} price={} gain={}%)",
                        ticker, candidate, entry, price,
                        gainFrac.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                current = candidate;
            }

            // 3. Trailing / breakeven stop hit.
            if (current != null && price.compareTo(current) <= 0) {
                return "TRAIL_STOP(price=" + price + "<=" + current + " on " + tf + ")";
            }
        }

        // 4. EMA STATE: fast below slow (not just the crossing bar) → bearish → exit.
        //    Catches the case where the cross happened earlier and stays bearish.
        List<BigDecimal> closes = closes(completed);
        if (closes.size() >= slow) {
            BigDecimal fastEma = EmaCalculator.calculate(closes, fast);
            BigDecimal slowEma = EmaCalculator.calculate(closes, slow);
            if (fastEma.compareTo(slowEma) < 0) {
                return "EMA_BEARISH(ema" + fast + "<ema" + slow + " on " + tf + ")";
            }
        }

        return null;
    }

    /** Returns the larger of a (possibly null) current value and a candidate. */
    private static BigDecimal maxNullable(BigDecimal current, BigDecimal candidate) {
        if (current == null) return candidate;
        if (candidate == null) return current;
        return candidate.compareTo(current) > 0 ? candidate : current;
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
