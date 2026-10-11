package com.trading.service;

import com.trading.model.Candle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds higher-timeframe candles (e.g. <b>M2, M3</b>) by aggregating native <b>M1</b> bars
 * — pure, stateless, and reusable. The broker only serves certain native timeframes
 * (M1/M5/M15/…); minute multiples that are not offered natively (M2, M3, M4, …) are
 * synthesised here from M1 with no broker support.
 *
 * <h2>Aggregation rule</h2>
 * M1 bars are grouped into fixed N-minute buckets <b>aligned to the top of the hour</b> in
 * {@code America/New_York} (so an M3 bucket covers 09:30–09:33, 09:33–09:36, …, matching how
 * brokers align intraday bars to the session). Within a bucket:
 * <ul>
 *   <li>open  = open of the FIRST (earliest) M1 bar</li>
 *   <li>high  = max high across the bucket</li>
 *   <li>low   = min low across the bucket</li>
 *   <li>close = close of the LAST (latest) M1 bar</li>
 *   <li>volume = sum of volumes</li>
 *   <li>timestamp = the bucket's start minute (same convention as native bars)</li>
 * </ul>
 *
 * <h2>Completeness &amp; gaps</h2>
 * Only bars that are present are aggregated — the resampler never fabricates missing minutes,
 * so a bucket with a mid-bar data gap simply aggregates the minutes that exist (same behaviour
 * a broker shows for a thin symbol). A trailing bucket that is not yet full is still emitted
 * from whatever M1 bars fell in it; callers that need "completed only" should rely on the
 * indicator's own "drop the last, in-progress bar" step, exactly as with native bars.
 */
public final class BarResampler {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private BarResampler() {}

    /**
     * Aggregates {@code m1Bars} (oldest-first, 1-minute) into {@code minutes}-minute candles.
     *
     * @param m1Bars  oldest-first native M1 candles for ONE symbol
     * @param minutes target bar size in minutes (e.g. 2 or 3). Must be &ge; 1.
     *                {@code minutes == 1} returns the input unchanged (defensive copy).
     * @return oldest-first aggregated candles; empty when input is null/empty
     * @throws IllegalArgumentException if {@code minutes < 1}
     */
    public static List<Candle> resample(List<Candle> m1Bars, int minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException("minutes must be >= 1, got " + minutes);
        }
        if (m1Bars == null || m1Bars.isEmpty()) return List.of();
        if (minutes == 1) return new ArrayList<>(m1Bars);

        List<Candle> out = new ArrayList<>(m1Bars.size() / minutes + 1);

        long currentBucketKey = Long.MIN_VALUE;
        Candle bucketFirst = null;
        java.math.BigDecimal hi = null, lo = null;
        java.math.BigDecimal close = null;
        long vol = 0L;
        Instant bucketStart = null;
        String ticker = null;

        for (Candle c : m1Bars) {
            long key = bucketKey(c.timestamp(), minutes);
            if (key != currentBucketKey) {
                if (bucketFirst != null) {
                    out.add(new Candle(ticker, bucketStart, bucketFirst.open(), hi, lo, close, vol));
                }
                currentBucketKey = key;
                bucketFirst = c;
                hi = c.high();
                lo = c.low();
                close = c.close();
                vol = c.volume();
                bucketStart = bucketStartInstant(c.timestamp(), minutes);
                ticker = c.ticker();
            } else {
                if (c.high() != null && (hi == null || c.high().compareTo(hi) > 0)) hi = c.high();
                if (c.low() != null && (lo == null || c.low().compareTo(lo) < 0)) lo = c.low();
                close = c.close();
                vol += c.volume();
            }
        }
        if (bucketFirst != null) {
            out.add(new Candle(ticker, bucketStart, bucketFirst.open(), hi, lo, close, vol));
        }
        return out;
    }

    /**
     * A stable bucket identifier: two M1 bars share a key iff they fall in the same N-minute
     * window, measured against ET wall-minute boundaries aligned to the top of the hour.
     */
    private static long bucketKey(Instant ts, int minutes) {
        ZonedDateTime z = ts.atZone(ET);
        long epochMinute = z.toEpochSecond() / 60L;
        long minuteOfHour = z.getMinute();
        long hourStartEpochMinute = epochMinute - minuteOfHour;
        long bucketWithinHour = minuteOfHour / minutes;
        return hourStartEpochMinute + bucketWithinHour;
    }

    /** The Instant at the start minute of the bucket that {@code ts} belongs to. */
    private static Instant bucketStartInstant(Instant ts, int minutes) {
        ZonedDateTime z = ts.atZone(ET);
        int flooredMinute = (z.getMinute() / minutes) * minutes;
        return z.withMinute(flooredMinute).withSecond(0).withNano(0).toInstant();
    }
}
