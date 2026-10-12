package com.trading.llm;

import com.trading.config.AppProperties;
import com.trading.indicator.AdxCalculator;
import com.trading.indicator.AdxService;
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
 * Assembles the DAY-TRADING, DISTILLED context the parallel LLM alert review reasons over.
 *
 * <p>For the alert's symbol it reads bars at the intraday timeframes <b>M15 / M5 / M1</b>
 * (via the shared, cached {@link BarDataManager}) and distils each into a single
 * information-dense line carrying the signals a day trader actually weighs:</p>
 * <ul>
 *   <li><b>Supertrend</b> — direction, line value, price-to-line distance in ATR, recent flips</li>
 *   <li><b>ADX</b> — trend strength + the dominant DI side (+DI/−DI)</li>
 *   <li><b>Volume</b> — recent-half vs older-half trend (rising / falling / flat)</li>
 *   <li><b>EMA stack</b> — a fast day-trade set (default 9/20/50), each price-above/below</li>
 *   <li><b>Range position</b> — where the last close sits within the window's high–low</li>
 * </ul>
 *
 * <p>A compact <b>daily-bias</b> line (prior close vs today's open, gap %) opens the block to
 * give higher-timeframe context without a near-useless 2-bar daily indicator block. Bar counts
 * (from {@code app.alert-review.*}) are sized so Supertrend and ADX actually compute on every
 * timeframe (ADX needs ~2×period+1 bars). Nothing is ever faked: an indicator without enough
 * bars is reported as such. No raw OHLCV rows are emitted — the whole briefing stays small.</p>
 */
@Component
public class AlertReviewContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AlertReviewContextBuilder.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final AppProperties props;
    private final BarDataManager barData;
    private final SupertrendService supertrend;
    private final AdxService adx;

    public AlertReviewContextBuilder(AppProperties props, BarDataManager barData,
                                     SupertrendService supertrend, AdxService adx) {
        this.props = props;
        this.barData = barData;
        this.supertrend = supertrend;
        this.adx = adx;
    }

    /**
     * Builds the distilled day-trading context block for {@code ticker}. Never throws — a
     * timeframe that fails to fetch or compute is reported inline, so the reviewer still runs
     * on whatever is available.
     */
    public String build(String ticker) {
        AppProperties.AlertReview cfg = props.alertReview();
        List<Integer> emaPeriods = (cfg.emaPeriods() != null && !cfg.emaPeriods().isEmpty())
                ? cfg.emaPeriods() : List.of(9, 20, 50);

        StringBuilder sb = new StringBuilder();

        // Daily-bias one-liner (higher-TF context without a full daily indicator block).
        sb.append(dailyBiasLine(ticker));

        // Intraday timeframes, swing → primary → entry.
        appendTimeframe(sb, ticker, "M15", cfg.m15Bars(), emaPeriods);
        appendTimeframe(sb, ticker, "M5", cfg.m5Bars(), emaPeriods);
        appendTimeframe(sb, ticker, "M1", cfg.m1Bars(), emaPeriods);

        return sb.toString();
    }

    /** A compact daily-bias line: prior close, today's open, and the gap. */
    private String dailyBiasLine(String ticker) {
        try {
            List<Candle> daily = barData.getBars(ticker, "D", 3);
            if (daily != null && daily.size() >= 2) {
                Candle today = daily.get(daily.size() - 1);
                Candle prior = daily.get(daily.size() - 2);
                BigDecimal priorClose = prior.close();
                BigDecimal todayOpen = today.open();
                BigDecimal gapPct = priorClose.signum() == 0 ? BigDecimal.ZERO
                        : todayOpen.subtract(priorClose, MC).divide(priorClose, MC)
                            .multiply(BigDecimal.valueOf(100), MC);
                String gapDir = gapPct.signum() > 0 ? "gap up" : gapPct.signum() < 0 ? "gap down" : "flat";
                return "[Daily bias] prior close %s, today open %s (%s %s%%), today high %s / low %s.\n"
                        .formatted(plain(priorClose), plain(todayOpen), gapDir,
                                plain2(gapPct.abs()), plain(today.high()), plain(today.low()));
            }
        } catch (RuntimeException e) {
            log.debug("[AlertReviewContextBuilder] daily-bias unavailable for {}: {}", ticker, e.getMessage());
        }
        return "[Daily bias] unavailable.\n";
    }

    /** Fetches one timeframe and appends a one-line distilled day-trade summary. */
    private void appendTimeframe(StringBuilder sb, String ticker, String tf, int bars,
                                 List<Integer> emaPeriods) {
        sb.append("[").append(tf).append("] ");

        List<Candle> candles;
        try {
            candles = barData.getBars(ticker, tf, bars);
        } catch (RuntimeException e) {
            sb.append("data unavailable (").append(e.getMessage()).append(").\n");
            return;
        }
        if (candles == null || candles.isEmpty()) {
            sb.append("no bars available.\n");
            return;
        }
        if (candles.size() > bars) {
            candles = candles.subList(candles.size() - bars, candles.size());
        }

        Candle last = candles.get(candles.size() - 1);
        sb.append("close ").append(plain(last.close())).append(". ");

        appendSupertrend(sb, candles, last);
        appendAdx(sb, candles);
        appendVolume(sb, candles);
        appendEmas(sb, candles, last, emaPeriods);
        appendRangePosition(sb, candles, last);

        sb.append('\n');
    }

    private void appendSupertrend(StringBuilder sb, List<Candle> candles, Candle last) {
        if (candles.size() < supertrend.length() + 1) {
            sb.append("ST n/a. ");
            return;
        }
        try {
            List<Point> pts = supertrend.calculate(candles);
            Point latest = null;
            for (int i = pts.size() - 1; i >= 0; i--) {
                if (pts.get(i).supertrend() != null) { latest = pts.get(i); break; }
            }
            if (latest == null || latest.direction() == null) { sb.append("ST warming up. "); return; }

            sb.append("ST ").append(latest.direction().name());
            sb.append(" line ").append(plain(latest.supertrend()));
            BigDecimal atr = latest.atr();
            if (atr != null && atr.signum() > 0) {
                BigDecimal sepAtr = last.close().subtract(latest.supertrend(), MC).abs().divide(atr, MC);
                String side = latest.direction() == Direction.UP ? "above" : "below";
                sb.append(" (").append(plain2(sepAtr)).append(" ATR ").append(side).append(")");
            }
            int flips = 0;
            for (Point p : pts) if (p.flip()) flips++;
            sb.append(", ").append(flips).append(" flips. ");
        } catch (RuntimeException e) {
            sb.append("ST error. ");
        }
    }

    private void appendAdx(StringBuilder sb, List<Candle> candles) {
        AdxCalculator.Point p = adx.latestCompleted(candles);
        if (p == null || p.adx() == null) { sb.append("ADX n/a. "); return; }
        String strength = AdxCalculator.strengthOf(p.adx()).name();
        String diSide = p.direction() == null ? "FLAT" : p.direction().name();
        sb.append("ADX ").append(plain2(p.adx()))
          .append(" (").append(strength).append(", ").append(diSide).append("). ");
    }

    private void appendVolume(StringBuilder sb, List<Candle> candles) {
        int n = candles.size();
        if (n < 4) { sb.append("Vol n/a. "); return; }
        int mid = n / 2;
        double older = avgVolume(candles, 0, mid);
        double recent = avgVolume(candles, mid, n);
        String dir = recent > older * 1.1 ? "rising" : recent < older * 0.9 ? "falling" : "flat";
        sb.append("Vol ").append(dir).append(". ");
    }

    private void appendEmas(StringBuilder sb, List<Candle> candles, Candle last, List<Integer> emaPeriods) {
        List<BigDecimal> closes = new ArrayList<>(candles.size());
        for (Candle c : candles) closes.add(c.close());
        List<String> bits = new ArrayList<>();
        for (int period : emaPeriods) {
            if (closes.size() < period) continue;
            try {
                BigDecimal ema = EmaCalculator.calculate(closes, period);
                bits.add("EMA" + period + (last.close().compareTo(ema) >= 0 ? "↑" : "↓"));
            } catch (RuntimeException ignored) { }
        }
        if (!bits.isEmpty()) sb.append("Price vs ").append(String.join("/", bits)).append(". ");
    }

    private void appendRangePosition(StringBuilder sb, List<Candle> candles, Candle last) {
        BigDecimal hi = last.high(), lo = last.low();
        for (Candle c : candles) {
            if (c.high().compareTo(hi) > 0) hi = c.high();
            if (c.low().compareTo(lo) < 0) lo = c.low();
        }
        BigDecimal span = hi.subtract(lo, MC);
        if (span.signum() <= 0) return;
        BigDecimal posPct = last.close().subtract(lo, MC).divide(span, MC).multiply(BigDecimal.valueOf(100), MC);
        sb.append("At ").append(plain2(posPct)).append("% of range.");
    }

    private static double avgVolume(List<Candle> candles, int fromInc, int toExc) {
        long sum = 0; int c = 0;
        for (int i = fromInc; i < toExc; i++) { sum += candles.get(i).volume(); c++; }
        return c == 0 ? 0 : (double) sum / c;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private static String plain2(BigDecimal v) {
        return v == null ? "?" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
