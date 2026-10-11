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
 *                  Used for the structured ALERT_FIRED log.
 */
public record AlertHit(String alertId, String ticker, String state, String message,
                       AlertMessageFormat.Dir direction, BigDecimal price) {

    /**
     * Backward-compatible constructor for alerts that don't supply a direction/price.
     * Prefer the full constructor so the structured ALERT_FIRED log carries direction + price.
     */
    public AlertHit(String alertId, String ticker, String state, String message) {
        this(alertId, ticker, state, message, null, null);
    }
}
