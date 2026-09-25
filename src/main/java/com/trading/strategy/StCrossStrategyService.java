package com.trading.strategy;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.OrderService;
import com.trading.service.OrderService.OrderResult;
import com.trading.service.TradeCooldown;
import com.trading.state.PositionTracker;
import com.trading.state.PositionTracker.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * "1m Supertrend + 20/50 Cross" Strategy — multi-ticker. <b>Entry-only.</b>
 *
 * <h2>Entry (all on {@code strategies.st-cross-timeframe}, default M1, latest COMPLETED bar)</h2>
 * <ol>
 *   <li>Supertrend is <b>UP</b> (state — using {@code webull.supertrend} params);</li>
 *   <li>the <b>20/50 EMA is in an up-state</b> (EMA20 &gt; EMA50) — or, when
 *       {@code st-cross-require-fresh-cross} is true, only the exact crossover bar;</li>
 *   <li>price (last close) is <b>above the 20 and 200 EMAs</b>;</li>
 *   <li>price is <b>near the 600-EMA</b> — within {@code st-cross-ema600-band} on either
 *       side — so the move is starting at the 600, not extended far above it.</li>
 * </ol>
 *
 * <p>The universal entry guard applies uniformly to all strategies, governed solely by
 * {@code webull.entry-guard.enabled} (there is no per-strategy bypass). Exits are owned
 * by the ST_CROSS exit manager. The re-trade cooldown and one-position-per-ticker rule
 * still apply. Enable via {@code webull.strategies.st-cross-enabled}.</p>
 */
@Service
public class StCrossStrategyService implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(StCrossStrategyService.class);

    private static final int PRICE_SCALE = 8;
    private static final int EMA_20 = 20;
    private static final int EMA_200 = 200;
    private static final int EMA_600 = 600;

    private final WebullProperties props;
    private final BarDataManager barData;
    private final SupertrendService supertrend;
    private final OrderService orderService;
    private final PositionTracker positionTracker;
    private final TradeCooldown tradeCooldown;
    private final com.trading.service.QuantityManager quantityManager;
    private final com.trading.service.AccountService accountService;

    public StCrossStrategyService(WebullProperties props,
                                  BarDataManager barData,
                                  SupertrendService supertrend,
                                  OrderService orderService,
                                  PositionTracker positionTracker,
                                  TradeCooldown tradeCooldown,
                                  com.trading.service.QuantityManager quantityManager,
                                  com.trading.service.AccountService accountService) {
        this.props = props;
        this.barData = barData;
        this.supertrend = supertrend;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
        this.tradeCooldown = tradeCooldown;
        this.quantityManager = quantityManager;
        this.accountService = accountService;
    }

    @Override
    public String name() { return "1m ST + 20/50 Cross"; }

    @Override
    public void onCandle(Candle candle) {
        String ticker = candle.ticker();
        String tf = props.strategies().stCrossTimeframe();
        int fast = props.strategies().stCrossEmaCrossFast();   // 20
        int slow = props.strategies().stCrossEmaCrossSlow();   // 50

        // One position per ticker; respect the re-trade cooldown.
        if (positionTracker.hasOpenPosition(ticker)) {
            log.debug("[{}] ticker={} — position already open, skipping", name(), ticker);
            return;
        }
        if (!tradeCooldown.canTrade(ticker)) {
            log.debug("[{}] ticker={} — in re-trade cooldown, skipping", name(), ticker);
            return;
        }

        // Need enough history for the 600-EMA plus a buffer; the same series feeds ST.
        int need = EMA_600 + 100;
        List<Candle> bars = barData.getBars(ticker, tf, need);
        if (bars.size() < EMA_600 + 2) {
            log.debug("[{}] ticker={} not enough {} bars ({}) for ema600", name(), ticker, tf, bars.size());
            return;
        }

        // 1) Supertrend UP (state) on the latest completed bar.
        Direction stDir = supertrend.latestCompletedDirection(bars);
        if (stDir != Direction.UP) {
            log.debug("[{}] ticker={} — ST not UP ({}) on {}", name(), ticker, stDir, tf);
            return;
        }

        // Completed bars only for EMA math (drop the last, possibly in-progress).
        List<Candle> completed = bars.subList(0, bars.size() - 1);
        List<BigDecimal> closesNow  = closes(completed);
        List<BigDecimal> closesPrev = closesNow.subList(0, closesNow.size() - 1);
        if (closesPrev.size() < slow) return;

        // 2) 20/50 relationship. Default is STATE (fast > slow = trend up); optionally a
        //    FRESH cross (only the exact crossover bar) via st-cross-require-fresh-cross.
        BigDecimal fastNow  = EmaCalculator.calculate(closesNow, fast);
        BigDecimal slowNow  = EmaCalculator.calculate(closesNow, slow);
        boolean upState = fastNow.compareTo(slowNow) > 0;
        if (props.strategies().stCrossRequireFreshCross()) {
            BigDecimal fastPrev = EmaCalculator.calculate(closesPrev, fast);
            BigDecimal slowPrev = EmaCalculator.calculate(closesPrev, slow);
            boolean freshCross = fastPrev.compareTo(slowPrev) <= 0 && upState;
            if (!freshCross) {
                log.debug("[{}] ticker={} {} — no fresh {}/{} cross (fast={} slow={})",
                        name(), ticker, tf, fast, slow, fastNow, slowNow);
                return;
            }
        } else if (!upState) {
            log.debug("[{}] ticker={} {} — {}/{} not in up-state (fast={} <= slow={})",
                    name(), ticker, tf, fast, slow, fastNow, slowNow);
            return;
        }

        // 3) Price above the 20 and 200 EMAs.
        BigDecimal ema20  = EmaCalculator.calculate(closesNow, EMA_20);
        BigDecimal ema200 = EmaCalculator.calculate(closesNow, EMA_200);
        BigDecimal ema600 = EmaCalculator.calculate(closesNow, EMA_600);
        BigDecimal price  = closesNow.get(closesNow.size() - 1);   // last completed close

        boolean aboveStack = price.compareTo(ema20) > 0
                          && price.compareTo(ema200) > 0;
        if (!aboveStack) {
            log.debug("[{}] ticker={} — price {} not above 20/200 ({}/{})",
                    name(), ticker, price, ema20, ema200);
            return;
        }

        // 4) Move starting NEAR the 600-EMA (either side, within the band).
        BigDecimal band = props.strategies().stCrossEma600Band();
        BigDecimal lower = ema600.multiply(BigDecimal.ONE.subtract(band));
        BigDecimal upper = ema600.multiply(BigDecimal.ONE.add(band));
        boolean near600 = price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0;
        if (!near600) {
            log.debug("[{}] ticker={} — price {} not within {}% of ema600={} [{}, {}]",
                    name(), ticker, price, band.multiply(BigDecimal.valueOf(100)), ema600, lower, upper);
            return;
        }

        // 5) Strong GREEN body on the signal bar (shared, reusable check).
        Candle signalBar = completed.get(completed.size() - 1);
        if (!com.trading.indicator.CandleStrength.isStrongGreenBody(signalBar, props.strategies().bodyMinRatio())) {
            log.debug("[{}] ticker={} — signal bar not a strong green candle (O={} H={} L={} C={}, minRatio={})",
                    name(), ticker, signalBar.open(), signalBar.high(), signalBar.low(), signalBar.close(),
                    props.strategies().bodyMinRatio());
            return;
        }

        log.info("[{}] *** BUY SIGNAL *** ticker={} {} ST=UP, {}/{} cross, price={} above 20/100/200, near ema600={}, strong green body",
                name(), ticker, tf, fast, slow, price, ema600);

        // Size the order via the QuantityManager (SHARES or PERCENT-of-buying-power).
        // Use the marketable ask as the price basis; fall back to the last completed close.
        OrderService.Quote quote = orderService.fetchQuote(ticker);
        BigDecimal sizingPrice = quote.ask() != null ? quote.ask() : price;
        BigDecimal buyingPower = accountService.getBuyingPowerLive();
        int qty = quantityManager.quantityFor(ticker, sizingPrice, buyingPower);
        if (qty < 1) {
            log.info("[{}] ticker={} — sizing resolved to 0 shares; skipping", name(), ticker);
            return;
        }

        // BUY — the universal entry guard is applied uniformly to ALL strategies,
        // governed solely by webull.entry-guard.enabled (no per-strategy bypass).
        // Session routing, spread guard, affordability and no-short checks apply inside.
        OrderResult buyResult = orderService.placeMarketBuy(ticker, qty, name(), false);
        if (!buyResult.success()) {
            logBuyNotPlaced(ticker, buyResult.message());
            return;
        }

        // Record the actual fill basis: resolve a FRESH price after acceptance, using the
        // marketable ask (what a market BUY pays) as the fallback — NOT the stale
        // signal-bar close. This keeps the -2% hard stop anchored to the true cost.
        BigDecimal fill = orderService.fetchFillPrice(ticker, sizingPrice)
                               .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        positionTracker.openPosition(new Position(ticker, fill, qty, null, null, buyResult.clientOrderId()));
        tradeCooldown.record(ticker);
        log.info("[{}] ENTERED ticker={} fill={} qty={} (exit handled by ST_CROSS exit manager)",
                name(), ticker, fill, qty);
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> out = new ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }

    private void logBuyNotPlaced(String ticker, String message) {
        String m = message == null ? "" : message;
        if (m.startsWith("ENTRY_GUARD_NOT_ARMED") || m.startsWith("SPREAD_GUARD")
                || m.equals("INSUFFICIENT_BUYING_POWER") || m.equals("TRADING_HALTED")) {
            log.info("[{}] BUY not placed for ticker={}: {}", name(), ticker, m);
        } else {
            log.error("[{}] BUY failed for ticker={}: {}", name(), ticker, m);
        }
    }
}
