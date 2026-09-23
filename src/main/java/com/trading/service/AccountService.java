package com.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.config.WebullProperties;
import com.trading.webull.WebullV3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches and caches the latest account snapshot from the Webull <b>v3</b> API
 * via {@link WebullV3Client}.
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
    private final WebullV3Client client;

    private final AtomicReference<AccountSnapshot> snapshot =
            new AtomicReference<>(AccountSnapshot.EMPTY);

    public AccountService(WebullProperties props, WebullV3Client client) {
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
            BigDecimal netLiq  = BigDecimal.ZERO;
            BigDecimal cashPow = BigDecimal.ZERO;

            // ── Balance ───────────────────────────────────────────────────
            WebullV3Client.V3Response bal = client.accountBalance(accountId);
            if (bal.success() && bal.body() != null) {
                JsonNode b = bal.body();
                netLiq  = num(b, "net_liquidation_value", "total_asset", "netLiquidationValue");
                cashPow = num(b, "cash_power", "buying_power", "total_cash_balance", "cashPower");

                // Some responses nest per-currency assets under "account_currency_assets"
                JsonNode assets = firstNonNull(b.get("account_currency_assets"),
                                               b.get("accountCurrencyAssets"));
                if ((netLiq.signum() == 0 || cashPow.signum() == 0)
                        && assets != null && assets.isArray() && assets.size() > 0) {
                    JsonNode a = assets.get(0);
                    if (netLiq.signum() == 0)  netLiq  = num(a, "net_liquidation_value", "netLiquidationValue");
                    if (cashPow.signum() == 0) cashPow = num(a, "cash_power", "cashPower", "cash_balance");
                }
            } else {
                log.warn("[AccountService] balance call failed: status={} body={}",
                        bal.statusCode(), bal.rawBody());
            }

            // ── Positions ─────────────────────────────────────────────────
            int posCount = 0;
            BigDecimal costBasis = BigDecimal.ZERO;
            WebullV3Client.V3Response pos = client.positions(accountId, 100, null);
            if (pos.success() && pos.body() != null) {
                JsonNode holdings = firstNonNull(pos.body().get("holdings"), pos.body());
                if (holdings != null && holdings.isArray()) {
                    posCount = holdings.size();
                    for (JsonNode h : holdings) {
                        BigDecimal unitCost = num(h, "unit_cost", "unitCost");
                        BigDecimal qty      = num(h, "quantity", "qty");
                        costBasis = costBasis.add(unitCost.multiply(qty, MC), MC);
                    }
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

    // -----------------------------------------------------------------------
    // JSON helpers — tolerant of snake_case / camelCase field names
    // -----------------------------------------------------------------------

    private static BigDecimal num(JsonNode node, String... keys) {
        if (node == null) return BigDecimal.ZERO;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                try {
                    return new BigDecimal(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private static JsonNode firstNonNull(JsonNode a, JsonNode b) {
        return a != null ? a : b;
    }
}
