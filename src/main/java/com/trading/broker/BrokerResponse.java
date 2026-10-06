package com.trading.broker;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Broker-neutral result of a low-level broker API call.
 *
 * <p>This is the transport-level response returned by every {@link BrokerClient}
 * method. It intentionally mirrors the shape of a raw HTTP response so that any
 * broker adapter (Webull today, another broker tomorrow) can populate it without
 * leaking broker-specific types into the calling services.</p>
 *
 * <p>Phase 1 note: the {@code body} is still a Jackson {@link JsonNode}. Later
 * phases push all JSON parsing into the adapter and replace this with fully
 * neutral domain records ({@code Candle}, {@code Quote}, {@code AccountSnapshot},
 * etc.). Keeping the JsonNode here for now lets the abstraction be introduced with
 * zero behavior change.</p>
 *
 * @param statusCode HTTP status ({@code -1} when the call never completed, e.g. transport failure)
 * @param success    {@code true} when {@code statusCode} is 2xx
 * @param body       parsed JSON tree (may be {@code null} when the body is empty/unparseable)
 * @param rawBody    raw response body string (for diagnostics / error surfacing)
 */
public record BrokerResponse(int statusCode, boolean success, JsonNode body, String rawBody) {
}
