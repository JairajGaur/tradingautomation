package com.trading.llm;

import com.trading.config.AppProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the multi-timeframe, DISTILLED context used by the parallel LLM alert review.
 *
 * <p>For the alert's symbol it gathers bars across several timeframes (via the shared,
 * cached {@link BarDataManager} — so this adds no uncached Webull load), computes the
 * Supertrend and the EMA stack per timeframe, and distils each into a compact block of
 * plain-language facts. It never emits raw OHLCV rows: the whole multi-timeframe briefing is
 * a few dozen facts (~2 KB of text), which keeps it well inside the model's context window
 * while still reflecting every bar that was fetched.</p>
 *
 * <p>Scope (from {@code app.alert-review.*}): M1 (intraday), N×M30, N×M60, N daily bars.
 * All timeframes are read through {@link BarDataManager#getBars}, which fetches with the
 * application's configured trading sessions ({@code app.engine.trading-sessions}) and shares
 * one cached series per (ticker, timeframe). EMA periods come from
 * {@code app.alert-review.ema-periods} or fall back to {@code app.engine.ema-periods}
 * (20/100/200/600). An EMA that lacks enough bars on a short timeframe (e.g. the 600-EMA on
 * 5 M30 bars) is simply omitted — never faked.</p>
 */
@Component
public class AlertReviewContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AlertReviewContextBuilder.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final AppProperties props;
    private final BarDataManager barData;
    private final SupertrendService supertrend;

    public AlertReviewContextBuilder(AppProperties props, BarDataManager barData,
                                     SupertrendService supertrend) {
        this.props = props;
        this.barData = barData;
        this.supertrend = supertrend;
    }

    /**
     * Builds the distilled multi-timeframe context block for {@code ticker}. Never throws —
     * a timeframe that fails to fetch or compute is reported inline as unavailable, so the
     * reviewer still runs on whatever context is available.
     *
     * @return a text block of per-timeframe facts, ready to drop into the review prompt
     */
    public String build(String ticker) {
        AppProperties.AlertReview cfg = props.alertReview();
        List<Integer> emaPeriods = (cfg.emaPeriods() != null && !cfg.emaPeriods().isEmpty())
                ? cfg.emaPeriods() : props.engine().emaPeriods();

        StringBuilder sb = new StringBuilder();

        // All timeframes read through the shared BarDataManager cache, which fetches with the
        // application's configured trading sessions. Daily first (macro), then swing, then M1.
        appendTimeframe(sb, ticker, "D", cfg.dailyBars(), emaPeriods);
        appendTimeframe(sb, ticker, "M60", cfg.m60Bars(), emaPeriods);
        appendTimeframe(sb, ticker, "M30", cfg.m30Bars(), emaPeriods);
        appendTimeframe(sb, ticker, "M1", cfg.m1Bars(), emaPeriods);

        return sb.toString();
    }

    /** Fetches one timeframe, computes ST + EMAs, and appends a distilled block. */
    private void appendTimeframe(StringBuilder sb, String ticker, String tf, int bars,
                                 List<Integer> emaPeriods) {
        sb.append("[").append(tf).append("] ");

        List<Candle> candles;
        try {
            // Reads through the shared cache; fetches with the configured trading sessions.
            candles = barData.getBars(ticker, tf, bars);
        } catch (RuntimeException e) {
            sb.append("data unavailable (").append(e.getMessage()).append(").\n");
            return;
        }
        if (candles == null || candles.isEmpty()) {
            sb.append("no bars available.\n");
            return;
        }

        // Trim to the requested count (getBars may return more from cache).
        if (candles.size() > bars) {
            candles = candles.subList(candles.size() - bars, candles.size());
        }

        Candle last = candles.get(candles.size() - 1);
        sb.append(candles.size()).append(" bars; last close ").append(plain(last.close())).append(". ");

        // Supertrend (needs length+1 bars).
        if (candles.size() >= supertrend.length() + 1) {
            try {
                List<Point> pts = supertrend.calculate(candles);
                Point latest = null;
                for (int i = pts.size() - 1; i >= 0; i--) {
                    if (pts.get(i).supertrend() != null) { latest = pts.get(i); break; }
                }
                if (latest != null && latest.direction() != null) {
                    sb.append("ST ").append(latest.direction().name())
                      .append(" @ ").append(plain(latest.supertrend()));
                    BigDecimal atr = latest.atr();
                    if (atr != null && atr.signum() > 0) {
                        BigDecimal sepAtr = last.close().subtract(latest.supertrend(), MC).abs()
                                .divide(atr, MC);
                        String side = latest.direction() == Direction.UP ? "above" : "below";
                        sb.append(" (price ").append(plain2(sepAtr)).append(" ATR ").append(side).append(")");
                    }
                    int flips = 0;
                    for (Point p : pts) if (p.flip()) flips++;
                    sb.append("; ").append(flips).append(" flip(s) in window");
                    sb.append(". ");
                } else {
                    sb.append("ST warming up. ");
                }
            } catch (RuntimeException e) {
                sb.append("ST error. ");
            }
        } else {
            sb.append("ST n/a (only ").append(candles.size()).append(" bars). ");
        }

        // EMA stack — only periods with enough bars; report price-vs-EMA.
        List<BigDecimal> closes = new ArrayList<>(candles.size());
        for (Candle c : candles) closes.add(c.close());
        List<String> emaBits = new ArrayList<>();
        for (int period : emaPeriods) {
            if (closes.size() < period) continue;   // not enough bars → omit, never fake
            try {
                BigDecimal ema = EmaCalculator.calculate(closes, period);
                String rel = last.close().compareTo(ema) >= 0 ? "above" : "below";
                emaBits.add("EMA" + period + "=" + plain(ema) + " (price " + rel + ")");
            } catch (RuntimeException ignored) {
                // skip this period
            }
        }
        if (!emaBits.isEmpty()) {
            sb.append("EMAs: ").append(String.join(", ", emaBits)).append(".");
        } else {
            sb.append("EMAs: none (insufficient bars).");
        }
        sb.append('\n');
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private static String plain2(BigDecimal v) {
        return v == null ? "?" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
