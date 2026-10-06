package com.trading.broker;

/**
 * Broker-neutral runtime exception thrown by a {@link BrokerClient} implementation
 * when a broker call fails (transport error, non-2xx status, or an unparseable
 * response). Carries the broker's HTTP status when available.
 *
 * <p>Services that expose their own domain exceptions (e.g.
 * {@code MarketDataException}) catch this and rethrow in their own type so their
 * public contract is unchanged.</p>
 */
public class BrokerException extends RuntimeException {

    /** HTTP status from the broker ({@code -1} when not an HTTP failure). */
    private final int statusCode;

    public BrokerException(String message) {
        this(message, -1);
    }

    public BrokerException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public BrokerException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
