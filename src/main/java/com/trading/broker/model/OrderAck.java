package com.trading.broker.model;

import java.util.Map;

/**
 * Broker-neutral acknowledgement of an order submission — the raw outcome of the
 * broker call, before the application layer wraps it in its own richer result.
 *
 * @param success      whether the broker accepted the order
 * @param orderId      broker-assigned order id (when returned; may be null)
 * @param message      short human-readable summary / rejection reason
 * @param httpStatus   broker HTTP status ({@code -1} when not an HTTP call)
 * @param rawResponse  raw broker response body (for diagnostics; may be null)
 * @param requestSent  the exact fields submitted to the broker (for debugging)
 */
public record OrderAck(
        boolean success,
        String orderId,
        String message,
        int httpStatus,
        String rawResponse,
        Map<String, Object> requestSent
) {
    public static OrderAck failure(String message, Map<String, Object> requestSent) {
        return new OrderAck(false, null, message, -1, null, requestSent);
    }
}
