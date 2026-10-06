package com.trading.service;

import com.trading.broker.BrokerClient;
import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches and caches the latest account snapshot from the broker API
 * via the {@link BrokerClient} abstraction (Webull today).
 *
 * <h2>v3 endpoints used</h2>
 * <ul>
 *   <li>{@code /trading/assets/balances/get?account_id=} — net liquidation + buying power</li>
 *   <li>{@code /trading/assets/positions/list?account_id=} — open holdings</li>
 * </ul>
 *
 * <h2>Paper mode</h2>
 * When credentials/account are unavailable the service returns safe defaults
 * (large buying power) so paper trading is never blocked by a failed balance call.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final MathContext MC = MathContext.DECIMAL128;

    static final BigDecimal PAPER_BUYING_POWER = new BigDecimal("999999999");

    private final WebullProperties props;
    private final BrokerClient client;

    private final AtomicReference<AccountSnapshot> snapshot =
            new AtomicReference<>(AccountSnapshot.EMPTY);

    // Short-lived cache of the FULL live holdings list, shared by all per-ticker
    // callers (exit reconcile, no-short guard) so they don't each hit the positions
    // endpoint. The list is identical for every ticker — fetch it once per window.
    private static final long HOLDINGS_TTL_MS = 5_000;
    private volatile java.util.List<Holding> cachedHoldings = null;
    private volatile boolean cachedHoldingsValid = false;   // false = last fetch failed / never fetched
    private volatile long cachedHoldingsAt = 0L;
    private final Object holdingsLock = new Object();

    public AccountService(WebullProperties props, BrokerClient client) {
        this.props = props;
        this.client = client;
    }

    /**
     * Immutable point-in-time account snapshot.
     */
    public record AccountSnapshot(
            BigDecimal netLiquidationValue,
            BigDecimal buyingPower,
            BigDecimal totalCostBasis,
            int positionCount
    ) {
        static final AccountSnapshot EMPTY = new AccountSnapshot(
                BigDecimal.ZERO, PAPER_BUYING_POWER, BigDecimal.ZERO, 0);
    }

    public AccountSnapshot getSnapshot() {
        return snapshot.get();
    }

    /**
     * Available buying power from the last cached snapshot. Prefer
     * {@link #getBuyingPowerLive()} for pre-trade checks.
     */
    public BigDecimal getBuyingPower() {
        return snapshot.get().buyingPower();
    }

    /**
     * Available buying power fetched <b>fresh</b> from Webull right now (via
     * {@link #refresh()}), so pre-trade balance checks use real-time data rather
     * than a stale cache. If the refresh fails, falls back to the last cached value.
     */
    public BigDecimal getBuyingPowerLive() {
        try {
            return refresh().buyingPower();
        } catch (Exception e) {
            log.warn("[AccountService] Live buying-power fetch failed — using cached value: {}", e.getMessage());
            return snapshot.get().buyingPower();
        }
    }

    /**
     * Fetches a fresh snapshot from the v3 API and updates the cache.
     * Exceptions/failures are logged; the previous snapshot is retained.
     */
    public AccountSnapshot refresh() {
        if (!props.shouldSubmitOrders()) {
            log.debug("[AccountService] Local simulation (submit-orders disabled) — skipping account refresh");
            return snapshot.get();
        }

        String accountId = client.resolveAccountId();
        if (accountId == null) {
            log.warn("[AccountService] Cannot refresh — account ID not resolved");
            return snapshot.get();
        }

        try {
            // ── Balance ───────────────────────────────────────────────────
            // Parsing lives in the broker adapter; we consume neutral figures.
            com.trading.broker.model.AccountBalance balance = client.fetchBalance(accountId);
            BigDecimal netLiq  = balance.netLiquidationValue();
            BigDecimal cashPow = balance.buyingPower();

            // ── Positions ─────────────────────────────────────────────────
            // Reuse the SHARED, short-cached holdings instead of a second direct
            // positions call. Around a BUY the balance snapshot, the exit-reconcile
            // and the no-short guard all want positions within the same few seconds;
            // routing them all through holdings() collapses that into ONE positions
            // request per TTL window, which is what was tripping 429s on the trading API.
            int posCount = 0;
            BigDecimal costBasis = BigDecimal.ZERO;
            Holdings h = holdings();
            if (h.valid()) {
                posCount = h.list().size();
                for (Holding hold : h.list()) {
                    costBasis = costBasis.add(
                            hold.unitCost().multiply(BigDecimal.valueOf(hold.quantity()), MC), MC);
                }
            }

            AccountSnapshot fresh = new AccountSnapshot(netLiq, cashPow, costBasis, posCount);
            snapshot.set(fresh);
            log.info("[AccountService] Snapshot — netLiq={} buyingPower={} positions={} costBasis={}",
                    netLiq, cashPow, posCount, costBasis);
            return fresh;

        } catch (Exception e) {
            log.error("[AccountService] Failed to refresh account snapshot", e);
            return snapshot.get();
        }
    }

    /**
     * A single live holding from the account (app-facing alias of the neutral
     * {@link com.trading.broker.model.Holding}).
     */
    public record Holding(String symbol, int quantity, java.math.BigDecimal unitCost) {}

    /**
     * Lists live equity holdings from Webull's positions endpoint. Empty on failure
     * (never null). Used at startup to seed the {@link PositionTracker} so the bot
     * knows what it already owns after a restart (and won't re-buy it).
     */
    public java.util.List<Holding> listHeldPositions() {
        Holdings h = holdings();
        return h.valid() ? h.list() : java.util.List.of();
    }

    /** Result of a holdings lookup: the list plus whether the underlying call succeeded. */
    private record Holdings(java.util.List<Holding> list, boolean valid) {}

    /**
     * Returns the full live holdings, cached for a short TTL and shared across all
     * per-ticker callers so the positions endpoint is hit at most once per window
     * (not once per ticker per tick). {@code valid=false} means the fetch failed
     * (callers must not treat an empty list as "flat").
     */
    private Holdings holdings() {
        long now = System.currentTimeMillis();
        if (cachedHoldings != null && (now - cachedHoldingsAt) < HOLDINGS_TTL_MS) {
            return new Holdings(cachedHoldings, cachedHoldingsValid);
        }
        synchronized (holdingsLock) {
            now = System.currentTimeMillis();
            if (cachedHoldings != null && (now - cachedHoldingsAt) < HOLDINGS_TTL_MS) {
                return new Holdings(cachedHoldings, cachedHoldingsValid);
            }
            java.util.List<Holding> out = new java.util.ArrayList<>();
            String accountId = client.resolveAccountId();
            // Fetch + parse lives in the broker adapter; we map neutral holdings
            // to the app-facing Holding and preserve the valid/invalid distinction.
            com.trading.broker.model.HoldingsResult res = accountId == null
                    ? com.trading.broker.model.HoldingsResult.INVALID
                    : client.fetchHoldings(accountId);
            boolean valid = res.valid();
            for (com.trading.broker.model.Holding h : res.list()) {
                out.add(new Holding(h.symbol(), h.quantity(), h.unitCost()));
            }
            cachedHoldings = out;
            cachedHoldingsValid = valid;
            cachedHoldingsAt = System.currentTimeMillis();
            return new Holdings(out, valid);
        }
    }

    /** Sentinel returned by {@link #getHeldQuantity} when the live quantity is UNKNOWN
     *  (the positions call failed) — distinct from a confirmed 0 (not held). */
    public static final int HELD_UNKNOWN = -1;

    /**
     * Live held quantity for a single {@code ticker} from Webull's positions endpoint.
     * <ul>
     *   <li>{@code >= 0} — confirmed quantity held (0 = confirmed not held);</li>
     *   <li>{@link #HELD_UNKNOWN} ({@code -1}) — the API call FAILED, so the true
     *       quantity is unknown. Callers must NOT treat this as "not held".</li>
     * </ul>
     *
     * <p>Distinguishing failure from a genuine 0 matters: a transient error must not
     * look like "position closed" (which would wrongly drop a real position) nor
     * relax the no-short guard.</p>
     */
    public int getHeldQuantity(String ticker) {
        if (ticker == null || ticker.isBlank()) return 0;
        Holdings h = holdings();               // shared, short-cached — no per-ticker call
        if (!h.valid()) {
            return HELD_UNKNOWN;               // fetch failed → unknown, not "flat"
        }
        String want = ticker.trim().toUpperCase();
        for (Holding held : h.list()) {
            if (held.symbol().equalsIgnoreCase(want)) {
                return held.quantity();
            }
        }
        return 0;                              // confirmed not held
    }
}
