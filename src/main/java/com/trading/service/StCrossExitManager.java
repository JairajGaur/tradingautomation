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
import java.util.ArrayList;
import java.util.List;

/**
 * ST_CROSS exit manager — a single trailing rule for the "1m Supertrend + 20/50 Cross"
 * strategy: <b>sell when price falls to/below {@code ema50 × (1 − exit.ema50-trail-pct)}</b>
 * on {@code exit.ema50-trail-timeframe} (default 50-EMA − 0.05% on M1).
 *
 * <p>The trail ratchets UP only: as the 50-EMA rises, the stop rises with it; it never
 * moves down. In a real trend price stays above the 50-EMA and the position rides; the
 * moment price dips to just under the 50-EMA, it exits. There is deliberately NO hard
 * stop, breakeven, or high-water trail here — this manager implements the pure 50-EMA
 * ride the strategy asks for.</p>
 *
 * <p>Reconciliation against live holdings, cooldown-on-close, and the no-short guard
 * (in {@link OrderService}) behave exactly as in {@link StagedTrailExitManager}.</p>
 */
@Service
public class StCrossExitManager implements PositionExitManager {

    private static final Logger log = LoggerFactory.getLogger(StCrossExitManager.class);

    private final WebullProperties props;
    private final BarDataManager barData;
    private final PositionTracker positionTracker;
    private final OrderService orderService;
    private final AccountService accountService;
    private final TradeCooldown tradeCooldown;

    // Per-ticker trail stop level (ema50 − pct). Only ever ratchets UP.
    private final java.util.Map<String, BigDecimal> stopLevel =
            new java.util.concurrent.ConcurrentHashMap<>();

    public StCrossExitManager(WebullProperties props,
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

    @Override
    public Kind kind() { return Kind.ST_CROSS; }

    @Override
    public void evaluate(String ticker) {
        Position pos = positionTracker.getPosition(ticker).orElse(null);
        if (pos == null || pos.quantity() <= 0) return;

        // Reconcile against the real account first (same safety as the staged manager):
        // a CONFIRMED zero means it was closed externally → drop the stale tracker entry;
        // a failed positions call (HELD_UNKNOWN) is NOT treated as closed.
        int liveHeld = accountService.getHeldQuantity(ticker);
        if (liveHeld == AccountService.HELD_UNKNOWN) {
            log.warn("[StCrossExit] ticker={} — live holdings unknown (positions call failed); "
                    + "skipping this tick, keeping position tracked", ticker);
            return;
        }
        if (liveHeld == 0) {
            log.info("[StCrossExit] ticker={} no longer held on Webull (tracked={}) — "
                    + "clearing stale tracker entry (closed externally)", ticker, pos.quantity());
            positionTracker.closePosition(ticker);
            stopLevel.remove(ticker);
            tradeCooldown.record(ticker);
            return;
        }

        if (!props.exit().enabled()) return;

        String reason = exitReason(ticker);
        if (reason == null) return;

        log.warn("[StCrossExit] EXIT ticker={} qty={} entry={} — reason={}",
                ticker, pos.quantity(), pos.entryPrice(), reason);

        OrderService.OrderResult result =
                orderService.placeExitSell(ticker, pos.quantity(), "EXIT_" + reason);
        if (result.success()) {
            positionTracker.closePosition(ticker);
            stopLevel.remove(ticker);
            tradeCooldown.record(ticker);
            log.info("[StCrossExit] Closed ticker={} ({})", ticker, reason);
        } else {
            log.error("[StCrossExit] Exit SELL failed for ticker={}: {}", ticker, result.message());
        }
    }

    /**
     * Returns an exit reason when price is at/below the ratcheted 50-EMA trail, else null.
     * The stop = highest-reached {@code ema50 × (1 − ema50TrailPct)}.
     */
    private String exitReason(String ticker) {
        String tf = props.exit().ema50TrailTimeframe();
        int period = props.strategies().stCrossEmaCrossSlow();   // the "50" of the 20/50 strategy
        BigDecimal trailPct = props.exit().ema50TrailPct();

        BigDecimal price = currentPrice(ticker);
        if (price == null) return null;   // no quote this tick — can't evaluate

        List<Candle> bars = barData.getBars(ticker, tf, period + 100);
        int n = bars.size();
        if (n < period + 2) return null;
        // Completed bars only (drop the last, possibly in-progress).
        List<Candle> completed = bars.subList(0, n - 1);
        List<BigDecimal> closes = closes(completed);
        if (closes.size() < period) return null;

        BigDecimal ema50 = EmaCalculator.calculate(closes, period);
        BigDecimal candidate = ema50.multiply(BigDecimal.ONE.subtract(trailPct));

        // Ratchet UP only.
        BigDecimal current = stopLevel.get(ticker);
        if (current == null || candidate.compareTo(current) > 0) {
            stopLevel.put(ticker, candidate);
            log.info("[StCrossExit] {} — trail ratcheted to {} (ema50={} − {}% on {}); price={}",
                    ticker, candidate, ema50,
                    trailPct.multiply(BigDecimal.valueOf(100)).setScale(3, RoundingMode.HALF_UP), tf, price);
            current = candidate;
        }

        if (price.compareTo(current) <= 0) {
            return "EMA50_TRAIL(price=" + price + "<=" + current + " [ema50=" + ema50 + "] on " + tf + ")";
        }
        return null;
    }

    private BigDecimal currentPrice(String ticker) {
        return orderService.fetchFillPrice(ticker, null);   // snapshot mid; null when unavailable
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> out = new ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }
}
