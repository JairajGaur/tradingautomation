package com.trading.alert;

import java.math.BigDecimal;

/**
 * A single alert match for a ticker.
 *
 * @param alertId   stable alert id (from {@link Alert#id()})
 * @param ticker    the symbol that matched
 * @param state     the matched state (e.g. "BULLISH" / "BEARISH" / "BREAKOUT_UP_<date>") — also
 *                  used for dedupe, so the engine re-notifies only when a ticker's state changes
 * @param message   human-readable body for the notification (uniform {@link AlertMessageFormat} line)
 * @param direction the uniform signal direction (UP/BULLISH, DOWN/BEARISH), or {@code null} when
 *                  the alert has no directional meaning. Used for the structured ALERT_FIRED log.
 * @param price     the price at the alert (breakout/close), or {@code null} when unavailable.
 * @param timeframe the alert's timeframe (e.g. "M5"), or {@code null} when not applicable.
 *                  Used in the structured ALERT_FIRED log and the desktop notification.
 */
public record AlertHit(String alertId, String ticker, String state, String message,
                       AlertMessageFormat.Dir direction, BigDecimal price, String timeframe) {

    /** Backward-compatible constructor for alerts that supply no direction/price/timeframe. */
    public AlertHit(String alertId, String ticker, String state, String message) {
        this(alertId, ticker, state, message, null, null, null);
    }

    /** Constructor without a timeframe (direction + price only). */
    public AlertHit(String alertId, String ticker, String state, String message,
                    AlertMessageFormat.Dir direction, BigDecimal price) {
        this(alertId, ticker, state, message, direction, price, null);
    }
}
