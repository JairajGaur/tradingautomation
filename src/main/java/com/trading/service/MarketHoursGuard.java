package com.trading.service;

import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Decides whether the bot is currently allowed to trade based on market hours.
 *
 * <h2>Sessions (all times in America/New_York)</h2>
 * <table border="1">
 *   <tr><th>Session</th><th>Default window</th><th>Controlled by</th></tr>
 *   <tr><td>Core (regular)</td><td>09:30 – 16:00</td><td>always active</td></tr>
 *   <tr><td>Pre-market</td><td>04:00 – 09:30</td><td>{@code extended-hours-enabled=true}</td></tr>
 *   <tr><td>After-hours</td><td>16:00 – 20:00</td><td>{@code extended-hours-enabled=true}</td></tr>
 * </table>
 *
 * <p>All window boundaries are configurable via the {@code webull.market-hours.*}
 * properties in {@code application.yml}.</p>
 *
 * <p>The guard also rejects weekends (Saturday / Sunday) regardless of time.</p>
 */
@Service
public class MarketHoursGuard {

    private static final Logger log = LoggerFactory.getLogger(MarketHoursGuard.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final WebullProperties props;

    // Parsed time boundaries — computed once from config strings
    private final LocalTime coreOpen;
    private final LocalTime coreClose;
    private final LocalTime preMarketOpen;
    private final LocalTime afterHoursClose;

    public MarketHoursGuard(WebullProperties props) {
        this.props = props;
        WebullProperties.MarketHours mh = props.marketHours();
        this.coreOpen        = LocalTime.parse(mh.coreOpen(),       HM);
        this.coreClose       = LocalTime.parse(mh.coreClose(),      HM);
        this.preMarketOpen   = LocalTime.parse(mh.preMarketOpen(),  HM);
        this.afterHoursClose = LocalTime.parse(mh.afterHoursClose(), HM);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the current wall-clock time falls within an
     * active trading session.
     *
     * <p>Uses {@code ZonedDateTime.now()} internally — suitable for production.
     * For tests, use {@link #isTradingAllowed(ZonedDateTime)} directly.</p>
     */
    public boolean isTradingAllowed() {
        return isTradingAllowed(ZonedDateTime.now(NEW_YORK));
    }

    /**
     * Testable overload that accepts an explicit date-time.
     *
     * @param now the current date-time in any zone (converted to New York internally)
     * @return {@code true} when trading is permitted at that moment
     */
    public boolean isTradingAllowed(ZonedDateTime now) {
        ZonedDateTime nyTime = now.withZoneSameInstant(NEW_YORK);
        LocalTime time = nyTime.toLocalTime();

        // Reject weekends
        switch (nyTime.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> {
                log.debug("[MarketHoursGuard] Weekend — trading not allowed");
                return false;
            }
            default -> { /* weekday — continue */ }
        }

        // Core session: always honoured
        if (!time.isBefore(coreOpen) && time.isBefore(coreClose)) {
            log.debug("[MarketHoursGuard] Core session active: {}", time);
            return true;
        }

        // Extended hours: only if enabled
        if (props.marketHours().extendedHoursEnabled()) {
            // Pre-market: preMarketOpen ≤ time < coreOpen
            if (!time.isBefore(preMarketOpen) && time.isBefore(coreOpen)) {
                log.debug("[MarketHoursGuard] Pre-market session active: {}", time);
                return true;
            }
            // After-hours: coreClose ≤ time < afterHoursClose
            if (!time.isBefore(coreClose) && time.isBefore(afterHoursClose)) {
                log.debug("[MarketHoursGuard] After-hours session active: {}", time);
                return true;
            }
        }

        log.debug("[MarketHoursGuard] Outside trading hours: {} (extendedHours={})",
                time, props.marketHours().extendedHoursEnabled());
        return false;
    }

    /**
     * Returns a human-readable description of the current session state.
     * Useful for log summaries and health endpoints.
     */
    public String currentSessionDescription() {
        ZonedDateTime now = ZonedDateTime.now(NEW_YORK);
        LocalTime time = now.toLocalTime();
        boolean extended = props.marketHours().extendedHoursEnabled();

        return switch (now.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> "WEEKEND";
            default -> {
                if (!time.isBefore(coreOpen) && time.isBefore(coreClose))
                    yield "CORE (" + coreOpen + "–" + coreClose + " ET)";
                if (extended && !time.isBefore(preMarketOpen) && time.isBefore(coreOpen))
                    yield "PRE-MARKET (" + preMarketOpen + "–" + coreOpen + " ET)";
                if (extended && !time.isBefore(coreClose) && time.isBefore(afterHoursClose))
                    yield "AFTER-HOURS (" + coreClose + "–" + afterHoursClose + " ET)";
                yield "CLOSED";
            }
        };
    }
}
