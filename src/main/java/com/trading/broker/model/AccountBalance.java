package com.trading.broker.model;

import java.math.BigDecimal;

/**
 * Broker-neutral account balance figures.
 *
 * @param netLiquidationValue total account equity (net liquidation value)
 * @param buyingPower         available buying power / cash power
 * @param valid               whether the underlying broker call succeeded; when
 *                            {@code false} the numeric fields are not meaningful
 *                            and the caller should retain its previous snapshot
 */
public record AccountBalance(BigDecimal netLiquidationValue, BigDecimal buyingPower, boolean valid) {

    /** A failed/empty balance (valid=false, zeros). */
    public static final AccountBalance INVALID =
            new AccountBalance(BigDecimal.ZERO, BigDecimal.ZERO, false);
}
