package com.trading.alert;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Shared Opening-Range-Breakout helpers used by the up/down ORB alerts.
 *
 * <p>The opening range is the first 5-minute candle of the regular session
 * (09:30–09:35 ET) for the current day. A breakout is detected on the latest
 * completed <b>1-minute</b> candle's CLOSE relative to that range.</p>
 */
final class OrbSupport {

    static final ZoneId ET = ZoneId.of("America/New_York");
    static final LocalTime OPEN = LocalTime.of(9, 30);

    private OrbSupport() {}

    /**
     * The opening-range candle: today's regular-session opening bar. Prefers the exact
     * 09:30 ET bar; if that's missing from the data (e.g. the feed's first RTH bar is
     * stamped 09:35), falls back to the EARLIEST today's bar at/after 09:30 ET — never a
     * pre-market bar. Returns null if no such bar exists yet today.
     */
    static Candle openingRangeCandle(List<Candle> bars) {
        if (bars == null || bars.isEmpty()) return null;
        LocalDate today = LocalDate.now(ET);

        // Bars are oldest-first, so the FIRST today's bar at/after 09:30 ET is the
        // earliest — use it as the fallback if no exact 09:30 bar exists.
        Candle earliestAfterOpen = null;
        for (Candle c : bars) {
            var zt = c.timestamp().atZone(ET);
            if (!zt.toLocalDate().equals(today)) continue;
            LocalTime t = zt.toLocalTime();
            if (t.equals(OPEN)) {
                return c;                       // exact 09:30 — best match
            }
            if (earliestAfterOpen == null && !t.isBefore(OPEN)) {
                earliestAfterOpen = c;          // first bar at/after 09:30 (never pre-market)
            }
        }
        return earliestAfterOpen;
    }

    /** Latest COMPLETED 1-minute candle (drops the last, possibly in-progress, bar). */
    static Candle latestCompletedM1(List<Candle> m1Bars) {
        if (m1Bars == null || m1Bars.size() < 2) return null;
        return m1Bars.get(m1Bars.size() - 2);
    }

    /** Today's date key in ET (for per-day dedupe / re-arm). */
    static String todayKey() {
        return LocalDate.now(ET).toString();
    }

    static boolean closesAbove(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) > 0;
    }

    static boolean closesBelow(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) < 0;
    }
}
