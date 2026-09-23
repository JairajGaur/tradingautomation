package com.trading.service;

import com.trading.config.WebullProperties;
import com.trading.state.PositionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds strategy BUY signals that fired but were blocked by the volume filter, and
 * re-checks the volume filter each tick until it turns rising (→ buy) or the hold
 * window expires (→ drop).
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>One pending signal per ticker — if a second strategy signals while one is
 *       pending, the second is ignored (the first holds).</li>
 *   <li>The hold window is {@code webull.strategies.volume-hold-minutes} (default 5).</li>
 *   <li>Only the <b>volume filter</b> is re-checked during the hold; the guard,
 *       spread and affordability checks still run when the buy is finally placed
 *       (via {@link OrderService#placeMarketBuy}).</li>
 * </ul>
 */
@Service
public class PendingSignals {

    private static final Logger log = LoggerFactory.getLogger(PendingSignals.class);

    private final WebullProperties props;
    private final VolumeFilter volumeFilter;
    private final OrderService orderService;
    private final PositionTracker positionTracker;

    public PendingSignals(WebullProperties props,
                          VolumeFilter volumeFilter,
                          @Lazy OrderService orderService,
                          PositionTracker positionTracker) {
        this.props = props;
        this.volumeFilter = volumeFilter;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
    }

    private record Pending(String strategy, int qty, Instant expiresAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Records a signal whose volume filter is not yet satisfied, to be re-checked for
     * up to the hold window. No-op when holding is disabled ({@code volume-hold-minutes<=0})
     * or a pending signal already exists for the ticker (first one wins).
     *
     * @return true if the signal was latched (caller should not retry this tick)
     */
    public boolean hold(String ticker, String strategy, int qty) {
        int minutes = props.strategies().volumeHoldMinutes();
        if (minutes <= 0) return false;
        Pending existing = pending.get(ticker);
        if (existing != null) {
            log.debug("[PendingSignals] {} already has a pending signal ({}), ignoring new {} signal",
                    ticker, existing.strategy(), strategy);
            return true;
        }
        pending.put(ticker, new Pending(strategy, qty, Instant.now().plusSeconds(minutes * 60L)));
        log.info("[PendingSignals] HELD {} ({}) — volume not rising yet; will re-check for {} min",
                ticker, strategy, minutes);
        return true;
    }

    /** True when a ticker already has a pending signal (so strategies skip re-signaling it). */
    public boolean isPending(String ticker) {
        return pending.containsKey(ticker);
    }

    /**
     * Re-checks all pending signals: fires the buy if volume is now rising, drops the
     * entry if it has expired or a position already exists. Call once per tick.
     */
    public void sweep() {
        Instant now = Instant.now();
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            String ticker = e.getKey();
            Pending p = e.getValue();

            // Position already open (bot or otherwise) → nothing to do.
            if (positionTracker.hasOpenPosition(ticker)) {
                pending.remove(ticker);
                continue;
            }
            // Expired → drop; the strategy will re-evaluate fresh next time.
            if (now.isAfter(p.expiresAt())) {
                log.info("[PendingSignals] EXPIRED {} ({}) — volume never rose within the hold window", ticker, p.strategy());
                pending.remove(ticker);
                continue;
            }
            // Volume still not rising → keep holding.
            if (!volumeFilter.isVolumeIncreasing(ticker)) {
                continue;
            }
            // Volume rising → place the buy (guard/spread/affordability still apply).
            log.info("[PendingSignals] VOLUME ROSE for {} ({}) — placing held BUY", ticker, p.strategy());
            OrderService.OrderResult r = orderService.placeMarketBuy(ticker, p.qty(), p.strategy());
            if (r.success()) {
                positionTracker.openPosition(new PositionTracker.Position(
                        ticker, orderService.fetchFillPrice(ticker, null), p.qty(),
                        null, null, r.clientOrderId()));
            } else {
                log.info("[PendingSignals] Held BUY for {} not placed: {}", ticker, r.message());
            }
            // Whether it filled or was gated, consume the pending — the strategy
            // re-signals next tick if conditions still hold.
            pending.remove(ticker);
        }
    }

    /** Clears all pending signals (daily reset). */
    public void clearAll() {
        int n = pending.size();
        pending.clear();
        if (n > 0) log.info("[PendingSignals] Cleared {} pending signal(s)", n);
    }
}
