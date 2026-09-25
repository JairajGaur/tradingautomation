package com.trading.service;

import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decides the share quantity for a BUY, per {@code webull.trading.sizing-mode}:
 *
 * <ul>
 *   <li><b>SHARES</b> — returns {@code order-quantity} (the downstream
 *       {@code canAfford} check still applies).</li>
 *   <li><b>PERCENT</b> — returns {@code floor(order-buying-power-pct × buyingPower / price)},
 *       i.e. deploy that fraction of live buying power. {@code order-quantity} is ignored.</li>
 * </ul>
 *
 * <p>In all modes: if live buying power is below {@code risk.min-buying-power-usd}
 * (the $100 floor), or the computed quantity is less than one share, this returns
 * {@code 0} — the caller must skip the trade.</p>
 */
@Service
public class QuantityManager {

    private static final Logger log = LoggerFactory.getLogger(QuantityManager.class);

    private final WebullProperties props;

    public QuantityManager(WebullProperties props) {
        this.props = props;
    }

    /**
     * @param ticker      symbol (for logging)
     * @param price       marketable per-share price (ask, fallback last/mid); may be null
     * @param buyingPower live buying power
     * @return share quantity to buy, or {@code 0} to skip
     */
    public int quantityFor(String ticker, BigDecimal price, BigDecimal buyingPower) {
        // Hard floor: never trade when buying power is below the configured minimum.
        BigDecimal floor = props.risk().minBuyingPowerUsd();
        if (buyingPower == null || buyingPower.compareTo(floor) < 0) {
            log.info("[QuantityManager] {} — buying power {} below floor {} → skip",
                    ticker, buyingPower, floor);
            return 0;
        }

        String mode = props.trading().sizingMode() == null ? "SHARES"
                : props.trading().sizingMode().trim().toUpperCase();

        if ("PERCENT".equals(mode)) {
            if (price == null || price.signum() <= 0) {
                log.info("[QuantityManager] {} — no price for PERCENT sizing → skip", ticker);
                return 0;
            }
            BigDecimal pct = props.trading().orderBuyingPowerPct();
            BigDecimal budget = buyingPower.multiply(pct);
            int qty = budget.divide(price, 0, RoundingMode.DOWN).intValue();   // floor
            if (qty < 1) {
                log.info("[QuantityManager] {} — PERCENT budget {} ({}% of {}) < 1 share @ {} → skip",
                        ticker, budget, pct.multiply(BigDecimal.valueOf(100)), buyingPower, price);
                return 0;
            }
            log.info("[QuantityManager] {} — PERCENT qty={} (budget={} = {}% of {}, price={})",
                    ticker, qty, budget, pct.multiply(BigDecimal.valueOf(100)), buyingPower, price);
            return qty;
        }

        // SHARES (default): fixed quantity; affordability enforced downstream by canAfford.
        int qty = props.trading().orderQuantity();
        log.debug("[QuantityManager] {} — SHARES qty={}", ticker, qty);
        return Math.max(0, qty);
    }
}
