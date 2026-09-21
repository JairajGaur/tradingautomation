package com.trading.api;

import com.trading.webull.WebullV3Client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helper that builds a structured {@code webullError} detail map for
 * inclusion in API responses.
 *
 * <p>Two forms:
 * <ul>
 *   <li>{@link #fromResponse(WebullV3Client.V3Response)} — for a failed v3 HTTP call
 *       (carries http status + raw body).</li>
 *   <li>{@link #fromThrowable(Throwable)} — for an exception during a call.</li>
 * </ul>
 */
public final class WebullErrors {

    private WebullErrors() { }

    /** Detail map for a failed {@link WebullV3Client.V3Response}. */
    public static Map<String, Object> fromResponse(WebullV3Client.V3Response resp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "HTTP_ERROR");
        m.put("httpStatus", resp.statusCode());
        if (resp.rawBody() != null && !resp.rawBody().isBlank()) {
            m.put("rawResponse", resp.rawBody());
        }
        return m;
    }

    /** Detail map for a Throwable caught around a call. */
    public static Map<String, Object> fromThrowable(Throwable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) {
            m.put("kind", "UNKNOWN");
            m.put("message", "No exception captured");
            return m;
        }
        m.put("kind", "EXCEPTION");
        m.put("exception", t.getClass().getSimpleName());
        if (t.getMessage() != null) m.put("message", t.getMessage());
        return m;
    }
}
