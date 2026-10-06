package com.trading.broker.model;

/**
 * Broker-neutral result of a connectivity + authentication probe against the
 * broker API, for the health endpoint.
 *
 * @param reachable     whether the broker endpoint was reachable at all
 * @param authenticated whether the authenticated probe call succeeded
 * @param accountsFound number of accounts returned by the probe (0 when none/failed)
 * @param httpStatus    broker HTTP status ({@code -1} on transport failure)
 * @param detail        short diagnostic detail (raw body on failure, or null)
 */
public record ConnectivityStatus(
        boolean reachable,
        boolean authenticated,
        int accountsFound,
        int httpStatus,
        String detail
) {
}
