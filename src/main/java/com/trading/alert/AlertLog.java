package com.trading.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for the structured "an alert fired" log line, emitted uniformly by
 * EVERY alert path (the {@link AlertEngine} for engine-delivered alerts, and self-delivering
 * alerts like the Swift Trend scanner). One consistent, machine-parseable format makes daily
 * accuracy reports trivial to generate — a report tool greps a single prefix and parses the
 * same {@code key=value} fields regardless of which alert fired.
 *
 * <p>Format (one line, space-separated {@code key=value}; reason is quoted last):</p>
 * <pre>{@code [ALERT_FIRED] id=<alertId> ticker=<SYM> dir=<UP|DOWN> price=<p> tf=<timeframe> state=<state> reason="<text>"}</pre>
 *
 * <p>Fields that are unknown for a given alert are omitted (never logged blank), so parsers
 * should treat each field as optional except {@code id} and {@code ticker}.</p>
 */
public final class AlertLog {

    private static final Logger log = LoggerFactory.getLogger("ALERT_FIRED");

    private AlertLog() {}

    /**
     * Emits the uniform structured fired-alert line.
     *
     * @param alertId   stable alert id (e.g. "orb-up", "swift", "st-adx") — required
     * @param ticker    symbol — required
     * @param dir       UP/DOWN direction, or null to omit
     * @param price     price at the alert, or null to omit (formatted to 2dp)
     * @param timeframe the alert's timeframe (e.g. "M5"), or null/blank to omit
     * @param state     the alert's state string, or null/blank to omit
     * @param reason    free-text reason/metrics, or null/blank to omit (logged quoted)
     */
    public static void fired(String alertId, String ticker, AlertMessageFormat.Dir dir,
                             BigDecimal price, String timeframe, String state, String reason) {
        StringBuilder sb = new StringBuilder("[ALERT_FIRED]");
        sb.append(" id=").append(alertId);
        sb.append(" ticker=").append(ticker);
        if (dir != null) sb.append(" dir=").append(dir);
        if (price != null) sb.append(" price=").append(price.setScale(2, RoundingMode.HALF_UP).toPlainString());
        if (timeframe != null && !timeframe.isBlank()) sb.append(" tf=").append(timeframe);
        if (state != null && !state.isBlank()) sb.append(" state=").append(state);
        if (reason != null && !reason.isBlank()) sb.append(" reason=\"").append(reason).append('"');
        log.info(sb.toString());
    }
}
