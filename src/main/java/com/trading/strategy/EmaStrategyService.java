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

    public EmaStrategyService(WebullProperties props,
                               BarDataManager barData,
                               OrderService orderService,
                               PositionTracker positionTracker,
                               com.trading.service.VolumeFilter volumeFilter,
                               com.trading.service.PendingSignals pendingSignals,
                               com.trading.service.EntryGuard entryGuard) {
        this.props = props;
        this.barData = barData;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
        this.volumeFilter = volumeFilter;
        this.pendingSignals = pendingSignals;
        this.entryGuard = entryGuard;
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

        // Entry: completed bar's open strictly above the 600-EMA.
        if (signalBar.open().compareTo(ema) <= 0) {
            log.debug("[{}] ticker={} open={} not above ema{}={} ({}) — no entry",
                    name(), ticker, signalBar.open(), period, ema, tf);
            return;
        }

        log.info("[{}] *** BUY SIGNAL *** ticker={} {} open={} > ema{}={}",
                name(), ticker, tf, signalBar.open(), period, ema);

        // Entry guard FIRST — if it fails (e.g. 30m ST red), drop the signal now;
        // do NOT hold it on volume. Order of checks: guard → signal → volume.
        com.trading.service.EntryGuard.Decision guard = entryGuard.evaluate(ticker);
        if (!guard.allowed()) {
            log.info("[{}] ticker={} — signal fired but entry guard not satisfied ({}); dropping",
                    name(), ticker, guard.reason());
            return;
        }

        int qty = props.trading().orderQuantity();

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
