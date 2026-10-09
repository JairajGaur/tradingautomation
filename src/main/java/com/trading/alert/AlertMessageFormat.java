package com.trading.alert;

/**
 * Single source of truth for the alert message format shared across ALL alerts
 * (Swift Trend, ST+ADX, ORB). Produces one uniform line:
 *
 * <pre>{@code <prefix><emoji> <SYMBOL> <DIRECTION> @ <price> [<timeframe>]}</pre>
 *
 * e.g. {@code SWIFT TREND - 🔴 AMD DOWN @ 632.72 [M5]}
 *
 * <p>Direction is always the uniform {@code UP} / {@code DOWN} (bullish → UP, bearish →
 * DOWN), with 🟢 for UP and 🔴 for DOWN.</p>
 */
public final class AlertMessageFormat {

    private AlertMessageFormat() {
        // utility class
    }

    /** Uniform direction used across all alerts. */
    public enum Dir {
        UP("🟢"), DOWN("🔴");
        private final String emoji;
        Dir(String emoji) { this.emoji = emoji; }
        public String emoji() { return emoji; }
    }

    /**
     * Builds the uniform single-line alert text.
     *
     * @param prefix    per-alert message prefix (may be null/empty)
     * @param symbol    ticker
     * @param dir       UP / DOWN
     * @param price     price string (already formatted by the caller)
     * @param timeframe the alert's configured timeframe (e.g. "M5")
     */
    public static String line(String prefix, String symbol, Dir dir, String price, String timeframe) {
        String p = prefix == null ? "" : prefix;
        return p + dir.emoji() + " " + symbol + " " + dir + " @ " + price + " [" + timeframe + "]";
    }
}
