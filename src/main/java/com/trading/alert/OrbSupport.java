package com.trading.alert;

import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Opening-Range-Breakout helpers used by the up/down ORB alerts.
 *
 * <p>The opening range is the high/low of today's <b>09:30 ET candle on the
 * {@code orb-range-minutes} timeframe</b> (15 → M15 = 09:30–09:45, 30 → M30 = 09:30–10:00).
 * It's fetched from historical bars — so it's correct even if the app starts late — and
 * cached once per ticker per day. Breakouts are then checked on the {@code scan-minutes}
 * timeframe close, with an EMA-slope confirmation.</p>
 */
final class OrbSupport {

    static final ZoneId ET = ZoneId.of("America/New_York");
    static final LocalTime OPEN = LocalTime.of(9, 30);

    private OrbSupport() {}

    /** Cached opening range for a ticker on a given day. */
    record Range(BigDecimal high, BigDecimal low) {}

    // Cache key = "ticker|yyyy-MM-dd" → the day's opening range.
    private static final Map<String, Range> RANGE_CACHE = new ConcurrentHashMap<>();

    /** Today's date key in ET (for per-day cache / dedupe / re-arm). */
    static String todayKey() {
        return LocalDate.now(ET).toString();
    }

    /**
     * Maps an interval in minutes to the matching Webull timeframe token
     * (1→M1, 5→M5, 15→M15, 30→M30, 60→M60). Any other value throws — callers should only
     * pass Webull-supported timeframes.
     */
    static String timeframeForMinutes(int minutes) {
        return switch (minutes) {
            case 1 -> "M1";
            case 5 -> "M5";
            case 15 -> "M15";
            case 30 -> "M30";
            case 60 -> "M60";
            default -> throw new IllegalArgumentException(
                    "Unsupported ORB timeframe minutes=" + minutes + " (use 1/5/15/30/60)");
        };
    }

    /**
     * Returns today's opening range for {@code ticker} from the range-timeframe bars —
     * the 09:30 ET candle's high/low — computed once and cached. Works on a late start
     * because it reads the (completed) 09:30 candle from history.
     *
     * @param ticker       symbol (cache key)
     * @param rangeTfBars  the ticker's series on the orb-range-minutes timeframe
     */
    static Range openingRange(String ticker, List<Candle> rangeTfBars) {
        String key = ticker + "|" + todayKey();
        Range cached = RANGE_CACHE.get(key);
        if (cached != null) return cached;

        if (rangeTfBars == null || rangeTfBars.isEmpty()) return null;
        LocalDate today = LocalDate.now(ET);

        // Only treat the 09:30 candle as final once the current time is past its close
        // (09:30 + rangeMinutes), so we never cache an in-progress opening bar.
        int rangeMinutes = minutesOf(rangeTfBars);
        boolean rangeComplete = !ZonedDateTime.now(ET).toLocalTime().isBefore(OPEN.plusMinutes(rangeMinutes));
        if (!rangeComplete) return null;

        for (Candle c : rangeTfBars) {
            ZonedDateTime zt = c.timestamp().atZone(ET);
            if (zt.toLocalDate().equals(today) && zt.toLocalTime().equals(OPEN)) {
                Range r = new Range(c.high(), c.low());
                RANGE_CACHE.put(key, r);
                return r;
            }
        }
        return null;   // 09:30 candle not present yet
    }

    /** Infers the bar length (minutes) of a series from the gap between its last two bars. */
    private static int minutesOf(List<Candle> bars) {
        if (bars.size() < 2) return 1;
        var a = bars.get(bars.size() - 2).timestamp();
        var b = bars.get(bars.size() - 1).timestamp();
        long m = java.time.Duration.between(a, b).toMinutes();
        return m > 0 ? (int) m : 1;
    }

    /** Latest COMPLETED candle in a series (drops the last, possibly in-progress, bar). */
    static Candle latestCompleted(List<Candle> bars) {
        if (bars == null || bars.size() < 2) return null;
        return bars.get(bars.size() - 2);
    }

    /**
     * EMA slope over {@code lookback} completed candles: returns {@code EMA(now) - EMA(lookback ago)}
     * on {@code bars}, or null if not enough data. Positive = rising, negative = falling.
     */
    static BigDecimal emaSlope(List<Candle> bars, int period, int lookback) {
        if (bars == null || bars.size() < period + lookback + 2) return null;
        // Completed bars only.
        List<Candle> completed = bars.subList(0, bars.size() - 1);
        int n = completed.size();
        if (n < period + lookback) return null;

        List<BigDecimal> closesNow = closes(completed);
        List<BigDecimal> closesPrev = closesNow.subList(0, closesNow.size() - lookback);
        BigDecimal emaNow = EmaCalculator.calculate(closesNow, period);
        BigDecimal emaPrev = EmaCalculator.calculate(closesPrev, period);
        return emaNow.subtract(emaPrev);
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> out = new ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }

    static boolean closesAbove(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) > 0;
    }

    static boolean closesBelow(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) < 0;
    }
}
