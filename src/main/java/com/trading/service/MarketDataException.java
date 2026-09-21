package com.trading.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when a Webull v3 market-data call fails.
 *
 * <p>Carries the failure message so the API layer can surface it in a
 * {@code webullError} block instead of a generic "no bars" message.</p>
 */
public class MarketDataException extends RuntimeException {

    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the error as an ordered map for embedding in a JSON API response.
     */
    public Map<String, Object> toDetailMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "MARKET_DATA_ERROR");
        m.put("message", getMessage());
        return m;
    }
}
