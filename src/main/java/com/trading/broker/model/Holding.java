package com.trading.broker.model;

import java.math.BigDecimal;

/**
 * Broker-neutral single live holding (position) in an account.
 *
 * @param symbol   equity symbol (uppercased)
 * @param quantity shares held (> 0)
 * @param unitCost average cost per share (may be {@link BigDecimal#ZERO} when unknown)
 */
public record Holding(String symbol, int quantity, BigDecimal unitCost) {
}
