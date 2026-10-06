package com.trading.broker.model;

import java.math.BigDecimal;

/**
 * Broker-neutral description of an order to place. Captures trading <em>intent</em>
 * in broker-agnostic terms; each adapter translates it into its broker's wire
 * format (field names, enum vocabulary, request envelope).
 *
 * <p>Only the fields relevant to a given {@link Type} are populated:
 * <ul>
 *   <li>LIMIT — {@code limitPrice}</li>
 *   <li>STOP — {@code stopPrice}</li>
 *   <li>TRAILING_STOP — {@code trailPct} (fraction, e.g. 0.01 = 1%)</li>
 *   <li>MARKET — none of the price fields</li>
 * </ul>
 *
 * @param clientOrderId caller-assigned idempotency id
 * @param symbol        equity symbol
 * @param side          BUY or SELL
 * @param type          order type
 * @param quantity      share quantity (> 0)
 * @param limitPrice    limit price (LIMIT only; else null)
 * @param stopPrice     stop trigger price (STOP only; else null)
 * @param trailPct      trailing-stop fraction (TRAILING_STOP only; else null)
 * @param timeInForce   time-in-force
 * @param extendedHours when true, the order should be eligible to work in
 *                      extended-hours/overnight sessions (adapter maps to its own
 *                      session flag); when false, regular-session routing applies
 */
public record OrderRequest(
        String clientOrderId,
        String symbol,
        Side side,
        Type type,
        int quantity,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        BigDecimal trailPct,
        TimeInForce timeInForce,
        boolean extendedHours
) {

    public enum Side { BUY, SELL }

    public enum Type { MARKET, LIMIT, STOP, STOP_LIMIT, TRAILING_STOP }

    public enum TimeInForce { DAY, GTC }

    /** Builder-style factory for a MARKET order. */
    public static OrderRequest market(String clientOrderId, String symbol, Side side, int qty) {
        return new OrderRequest(clientOrderId, symbol, side, Type.MARKET, qty,
                null, null, null, TimeInForce.DAY, false);
    }

    /** Returns a copy with {@code extendedHours} set. */
    public OrderRequest withExtendedHours(boolean ext) {
        return new OrderRequest(clientOrderId, symbol, side, type, quantity,
                limitPrice, stopPrice, trailPct, timeInForce, ext);
    }
}
