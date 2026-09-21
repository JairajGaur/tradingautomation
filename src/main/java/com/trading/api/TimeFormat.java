package com.trading.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Small helper for rendering timestamps in US Eastern time (America/New_York),
 * which handles EST/EDT daylight-saving transitions automatically.
 *
 * <p>Webull returns bar times in UTC; {@link Instant#toString()} always renders
 * UTC (a trailing {@code Z}). This converts an {@link Instant} to a readable ET
 * string so bar times line up with US market hours (09:30–16:00 ET).</p>
 */
public final class TimeFormat {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter ET_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss z");

    private TimeFormat() { }

    /**
     * Formats an instant as {@code yyyy-MM-dd'T'HH:mm:ss z} in America/New_York,
     * e.g. {@code 2024-01-15T09:30:00 EST}. Returns {@code null} for null input.
     */
    public static String toEt(Instant instant) {
        if (instant == null) return null;
        return instant.atZone(ET).format(ET_FORMAT);
    }

    /**
     * Current time formatted in America/New_York (ET). Used for API response
     * {@code timestamp} fields so they read in Eastern time rather than UTC.
     */
    public static String nowEt() {
        return toEt(Instant.now());
    }
}
