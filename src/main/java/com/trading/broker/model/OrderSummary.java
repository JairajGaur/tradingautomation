package com.trading.broker.model;

/**
 * Broker-neutral summary of an order as reported by the broker's order-listing
 * endpoints (open orders / order history). All fields are strings as the broker
 * reports them, or {@code null} when the broker omits a field. Each adapter maps
 * its own order payload into this shape; the parsing never leaks past the adapter.
 *
 * @param orderId        broker order id
 * @param clientOrderId  client-assigned id, when echoed back
 * @param symbol         equity symbol
 * @param side           BUY / SELL
 * @param orderType      MARKET / LIMIT / STOP / ...
 * @param status         broker order status (e.g. FILLED, WORKING, CANCELLED)
 * @param quantity       ordered quantity
 * @param filledQuantity filled quantity, when reported
 * @param limitPrice     limit price, when applicable
 * @param stopPrice      stop price, when applicable
 */
public record OrderSummary(
        String orderId,
        String clientOrderId,
        String symbol,
        String side,
        String orderType,
        String status,
        String quantity,
        String filledQuantity,
        String limitPrice,
        String stopPrice
) {
}
