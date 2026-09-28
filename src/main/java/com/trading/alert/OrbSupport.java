package com.trading.alert;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Opening-Range-Breakout helpers used by the up/down ORB alerts.
 *
 * <p>The opening range is the high/low of the first {@code rangeMinutes} <b>one-minute</b>
 * bars from 09:30 ET (so the range is final at 09:30 + rangeMinutes). It's built from M1
 * bars — supporting ANY range length, not just Webull's fixed timeframes — and is
 * <b>computed once per ticker per day and cached</b>; later scans just read the cached
 * range and check for a break on the latest completed 1-minute close.</p>
 */
final class OrbSupport {

    static final ZoneId ET = ZoneId.of("America/New_York");
    static final LocalTime OPEN = LocalTime.of(9, 30);

    private OrbSupport() {}

    /** Cached opening range for a ticker on a given day. */
    record Range(BigDecimal high, BigDecimal low) {}

    // Cache key = "ticker|yyyy-MM-dd" → computed range for that day. Cleared naturally by
    // key rotation (a new day = new key); old keys are harmless and few.
    private static final Map<String, Range> RANGE_CACHE = new ConcurrentHashMap<>();

    /** Today's date key in ET (for per-day cache / dedupe / re-arm). */
    static String todayKey() {
        return LocalDate.now(ET).toString();
    }

    /**
     * Returns the cached opening range for {@code ticker} today, computing it once from the
     * first {@code rangeMinutes} one-minute bars starting 09:30 ET. Returns null until the
     * range is complete (i.e. before 09:30 + rangeMinutes, or if the opening M1 bars aren't
     * available yet).
     *
     * @param ticker       symbol (for the cache key)
     * @param m1Bars       the ticker's M1 series (oldest-first)
     * @param rangeMinutes opening-range length in minutes (N)
     */
    static Range openingRange(String ticker, List<Candle> m1Bars, int rangeMinutes) {
        int n = Math.max(1, rangeMinutes);
        String day = todayKey();
        String key = ticker + "|" + day;

        Range cached = RANGE_CACHE.get(key);
        if (cached != null) return cached;

        if (m1Bars == null || m1Bars.isEmpty()) return null;

        LocalDate today = LocalDate.now(ET);
        LocalTime rangeEnd = OPEN.plusMinutes(n);   // exclusive upper bound (e.g. 09:35 for N=5)

        // Collect today's 1-min bars in [09:30, 09:30+N). Only compute/cache once we have
        // the FULL window (all N bars) so the range is final — matches "compute at 09:30+N".
        BigDecimal high = null, low = null;
        int count = 0;
        for (Candle c : m1Bars) {
            ZonedDateTime zt = c.timestamp().atZone(ET);
            if (!zt.toLocalDate().equals(today)) continue;
            LocalTime t = zt.toLocalTime();
            if (t.isBefore(OPEN) || !t.isBefore(rangeEnd)) continue;   // outside the opening window
            high = (high == null) ? c.high() : high.max(c.high());
            low  = (low == null)  ? c.low()  : low.min(c.low());
            count++;
        }

        // Not complete yet (need all N one-minute bars of the opening window).
        if (count < n || high == null) return null;

        Range r = new Range(high, low);
        RANGE_CACHE.put(key, r);
        return r;
    }

    /** Latest COMPLETED 1-minute candle (drops the last, possibly in-progress, bar). */
    static Candle latestCompletedM1(List<Candle> m1Bars) {
        if (m1Bars == null || m1Bars.size() < 2) return null;
        return m1Bars.get(m1Bars.size() - 2);
    }

    static boolean closesAbove(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) > 0;
    }

    static boolean closesBelow(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) < 0;
    }
}
