package com.trading.strategy;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.OrderService;
import com.trading.service.OrderService.OrderResult;
import com.trading.state.PositionTracker;
import com.trading.state.PositionTracker.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 600-EMA Momentum Strategy — multi-ticker. <b>Entry-only.</b>
 *
 * <h2>Signal (per ticker, on the configured timeframe)</h2>
 * The strategy evaluates the latest <em>completed</em> bar on its configured
 * timeframe ({@code webull.strategies.ema600-timeframe}, default M1): if that
 * bar's open is strictly above the 600-period EMA → market BUY.
 *
 * <p>There is no exit logic here — the universal {@code ExitManager} handles the
 * sell (−10% stop or 8/20 bearish cross). Enable/disable via
 * {@code webull.strategies.ema600-enabled}.</p>
 */
@Service
public class EmaStrategyService implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmaStrategyService.class);

    private static final int PRICE_SCALE = 8;

    private final WebullProperties props;
    private final BarDataManager barData;
    private final OrderService orderService;
    private final PositionTracker positionTracker;
    private final com.trading.service.VolumeFilter volumeFilter;
    private final com.trading.service.PendingSignals pendingSignals;
    private final com.trading.service.EntryGuard entryGuard;
    private final com.trading.service.TradeCooldown tradeCooldown;
    private final com.trading.service.QuantityManager quantityManager;
    private final com.trading.service.AccountService accountService;

    public EmaStrategyService(WebullProperties props,
                               BarDataManager barData,
                               OrderService orderService,
                               PositionTracker positionTracker,
                               com.trading.service.VolumeFilter volumeFilter,
                               com.trading.service.PendingSignals pendingSignals,
                               com.trading.service.EntryGuard entryGuard,
                               com.trading.service.TradeCooldown tradeCooldown,
                               com.trading.service.QuantityManager quantityManager,
                               com.trading.service.AccountService accountService) {
        this.props = props;
        this.barData = barData;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
        this.volumeFilter = volumeFilter;
        this.pendingSignals = pendingSignals;
        this.entryGuard = entryGuard;
        this.tradeCooldown = tradeCooldown;
        this.quantityManager = quantityManager;
        this.accountService = accountService;
    }

    @Override
    public String name() { return "600-EMA Momentum"; }

    @Override
    public void onCandle(Candle candle) {
        String ticker = candle.ticker();
        int period = props.trading().emaPeriod();
        String tf = props.strategies().ema600Timeframe();

        // Position guard — no duplicate buys.
        if (positionTracker.hasOpenPosition(ticker)) {
            log.debug("[{}] ticker={} — position already open, skipping", name(), ticker);
            return;
        }
        // A signal is already being held for this ticker (waiting on volume) — one per ticker.
        if (pendingSignals.isPending(ticker)) {
            log.debug("[{}] ticker={} — signal already pending on volume, skipping", name(), ticker);
            return;
        }
        // Cooldown — don't re-trade a ticker within the window of its last buy/exit.
        if (!tradeCooldown.canTrade(ticker)) {
            log.debug("[{}] ticker={} — in re-trade cooldown, skipping", name(), ticker);
            return;
        }

        // ENTRY GUARD FIRST — evaluate before computing the signal. If the guard fails
        // (e.g. 30m ST red), skip entirely: no signal computation, no BUY SIGNAL log.
        com.trading.service.EntryGuard.Decision guard = entryGuard.evaluate(ticker);
        if (!guard.allowed()) {
            log.debug("[{}] ticker={} — entry guard not satisfied ({}); skipping", name(), ticker, guard.reason());
            return;
        }

        // Fetch the strategy's own timeframe bars and evaluate the latest COMPLETED bar.
        List<Candle> bars = barData.getBars(ticker, tf, period + 100);
        if (bars.size() < period + 1) {
            log.debug("[{}] ticker={} not enough {} bars ({}) for ema{}", name(), ticker, tf, bars.size(), period);
            return;
        }
        // Drop the last (possibly in-progress) bar; evaluate the last completed one.
        List<Candle> completed = bars.subList(0, bars.size() - 1);
        Candle signalBar = completed.get(completed.size() - 1);

        List<BigDecimal> closes = closes(completed);
        BigDecimal ema = EmaCalculator.calculate(closes, period);

        // Entry: a bullish candle NEAR the 600-EMA (either side), closing above it, on
        // the latest completed bar — captures a bounce OR an open-on-EMA momentum bar,
        // while rejecting bars floating far above the EMA (chasing highs). Require:
        // green, close above the EMA, low within the proximity band of the EMA,
        // optional wick rejection (off by default), and a strong body.
        BigDecimal open  = signalBar.open();
        BigDecimal close = signalBar.close();
        BigDecimal high  = signalBar.high();
        BigDecimal low   = signalBar.low();

        // Strong GREEN body via the shared, reusable check (green + body >= bodyMinRatio × range).
        boolean strongBody  = com.trading.indicator.CandleStrength.isStrongGreenBody(
                signalBar, props.strategies().bodyMinRatio());
        boolean heldAbove   = close.compareTo(ema) > 0;
        BigDecimal band     = props.strategies().ema600ProximityBand();
        BigDecimal nearMax  = ema.multiply(BigDecimal.ONE.add(band));      // low must be <= this
        boolean nearEma     = low.compareTo(nearMax) <= 0;                 // dipped near the EMA
        boolean wickOk      = !props.strategies().ema600RequireWickRejection()
                              || low.compareTo(ema) < 0;                   // probed below the EMA

        if (!(strongBody && heldAbove && nearEma && wickOk)) {
            log.debug("[{}] ticker={} {} no bounce entry (ema{}={} O={} H={} L={} C={} | "
                            + "strongBody={} heldAbove={} nearEma={} wickOk={})",
                    name(), ticker, tf, period, ema, open, high, low, close,
                    strongBody, heldAbove, nearEma, wickOk);
            return;
        }

        // Shared "don't buy the high" filter (config-gated, common to all strategies).
        if (props.strategies().noBuyHighEnabled()
                && com.trading.indicator.EntryPriceFilter.isTooHigh(
                        closes, close,
                        props.strategies().noBuyHighRefEma(),
                        props.strategies().noBuyHighMaxExtension())) {
            log.debug("[{}] ticker={} — price {} too extended above ema{} (max {}%); skipping",
                    name(), ticker, close, props.strategies().noBuyHighRefEma(),
                    props.strategies().noBuyHighMaxExtension().multiply(BigDecimal.valueOf(100)));
            return;
        }

        log.info("[{}] *** BUY SIGNAL *** ticker={} {} bullish near 600-EMA: C={} > ema600={}, low={} (guard passed)",
                name(), ticker, tf, close, ema, low);

        // Common sizing via QuantityManager (SHARES or PERCENT-of-buying-power).
        // Price basis = marketable ask; fall back to the signal bar's close.
        OrderService.Quote quote = orderService.fetchQuote(ticker);
        BigDecimal sizingPrice = quote.ask() != null ? quote.ask() : close;
        int qty = quantityManager.quantityFor(ticker, sizingPrice, accountService.getBuyingPowerLive());
        if (qty < 1) {
            log.info("[{}] ticker={} — sizing resolved to 0 shares; skipping", name(), ticker);
            return;
        }

        // Volume filter: only enter when average 1-min volume is increasing. If not,
        // HOLD the signal (re-checked for the configured window) instead of dropping.
        if (!volumeFilter.isVolumeIncreasing(ticker)) {
            if (pendingSignals.hold(ticker, name(), qty)) {
                log.info("[{}] ticker={} — signal fired but volume not increasing; holding", name(), ticker);
            } else {
                log.info("[{}] ticker={} — signal fired but volume not increasing; skipping (hold disabled)", name(), ticker);
            }
            return;
        }

        OrderResult buyResult = orderService.placeMarketBuy(ticker, qty, name());
        if (!buyResult.success()) {
            logBuyNotPlaced(ticker, buyResult.message());
            return;
        }

        // Entry-only: register the position at the actual fill; ExitManager handles the sell.
        BigDecimal fill = orderService.fetchFillPrice(ticker, signalBar.open())
                               .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        positionTracker.openPosition(new Position(ticker, fill, qty, null, null, buyResult.clientOrderId()));
        tradeCooldown.record(ticker);   // start the cooldown on entry
        log.info("[{}] ENTERED ticker={} fill={} qty={} (exit handled by ExitManager)",
                name(), ticker, fill, qty);
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> out = new java.util.ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }

    /**
     * Logs why a BUY wasn't placed. Guard/spread/affordability gating is EXPECTED
     * (INFO, not an error); only genuine failures are logged at ERROR.
     */
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
