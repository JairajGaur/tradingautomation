package com.trading.service;

/**
 * Single source of truth for minute-timeframe classification shared by the market-data layer.
 *
 * <p>The broker serves only certain NATIVE minute timeframes (M1/M5/M15/M30/M60/M120/M240).
 * Other minute multiples (M2, M3, M4, …) are <b>synthetic</b>: the app fetches native M1 and
 * aggregates it via {@link BarResampler}. This helper centralises the parsing and the
 * native/synthetic decision so {@link MarketDataService} (fetch + resample) and
 * {@link BarDataManager} (cache boundary math) stay consistent.</p>
 */
public final class TimeframeSupport {

    private TimeframeSupport() {}

    /** Webull's hard per-request bar cap (the bars endpoint rejects count &gt; this with HTTP 417). */
    public static final int BROKER_MAX_BARS = 1650;

    /** Minute multiples the broker serves natively (no resampling needed). */
    private static final java.util.Set<Integer> NATIVE_MINUTES =
            java.util.Set.of(1, 5, 15, 30, 60, 120, 240);

    /**
     * Parses a minute timeframe like {@code "M2"} / {@code "M3"} / {@code "M5"} into its minute
     * count. Only {@code M<n>} minute forms are handled (D/W/M/Y are not minute timeframes).
     *
     * @return the minute count (&ge; 1), or {@code -1} if not an {@code M<n>} minute form
     */
    public static int minutesOf(String timeframe) {
        if (timeframe == null) return -1;
        String t = timeframe.trim().toUpperCase();
        if (t.length() < 2 || t.charAt(0) != 'M') return -1;
        try {
            int n = Integer.parseInt(t.substring(1));
            return n >= 1 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;   // "M", "MW", or a non-minute code
        }
    }

    /** Comma-separated minute intervals, preserving order and removing duplicate spellings. */
    public static java.util.List<String> minuteTimeframes(String configured, String fallback) {
        String value = configured == null || configured.isBlank() ? fallback : configured;
        if (value == null || value.isBlank()) throw new IllegalArgumentException("No minute timeframes configured");
        var result = new java.util.LinkedHashSet<String>();
        for (String token : value.split(",", -1)) {
            int minutes = minutesOf(token);
            if (minutes < 1) throw new IllegalArgumentException("Invalid minute timeframe: " + token);
            result.add("M" + minutes);
        }
        return java.util.List.copyOf(result);
    }

    /** Whether {@code timeframe} is a minute multiple the broker serves natively. */
    public static boolean isNativeMinute(String timeframe) {
        int m = minutesOf(timeframe);
        return m > 0 && NATIVE_MINUTES.contains(m);
    }

    /** Whether {@code timeframe} is a minute multiple NOT served natively (needs M1 resampling). */
    public static boolean isSynthetic(String timeframe) {
        int m = minutesOf(timeframe);
        return m > 0 && !NATIVE_MINUTES.contains(m);
    }
}
