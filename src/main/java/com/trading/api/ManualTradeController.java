package com.trading.api;

import com.trading.config.WebullProperties;
import com.trading.service.OrderService;
import com.trading.state.PositionTracker;
import com.trading.state.PositionTracker.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — Manual buy/sell for testing the trading pipeline by hand.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/trade/buy/{ticker}?quantity=N</td>
 *       <td>Place a manual market BUY</td></tr>
 *   <tr><td>POST</td><td>/api/trade/sell/{ticker}?quantity=N</td>
 *       <td>Place a manual market SELL (subject to the no-short guardrail)</td></tr>
 * </table>
 *
 * <p>These go through the same {@link OrderService} the strategies use, so all
 * guardrails apply: BUYs respect the trading-halt and buying-power checks; SELLs
 * respect the no-short guardrail (cannot sell more than the tracked held quantity).
 * Trades are recorded in the trade log under strategy {@code "MANUAL"}.</p>
 *
 * <p>In PAPER mode orders are simulated. In LIVE mode these place real orders.</p>
 */
@RestController
@RequestMapping("/api/trade")
public class ManualTradeController {

    private static final Logger log = LoggerFactory.getLogger(ManualTradeController.class);

    private static final String MANUAL = "MANUAL";

    private final WebullProperties props;
    private final OrderService orderService;
    private final PositionTracker positionTracker;
    private final com.trading.webull.WebullV3Client client;

    public ManualTradeController(WebullProperties props,
                                  OrderService orderService,
                                  PositionTracker positionTracker,
                                  com.trading.webull.WebullV3Client client) {
        this.props = props;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
        this.client = client;
    }

    // -----------------------------------------------------------------------
    // POST /api/trade/buy/{ticker}
    // -----------------------------------------------------------------------

    /**
     * Places a manual market BUY for {@code quantity} shares of {@code ticker}.
     *
     * <p>On success, the position is registered in the {@link PositionTracker}
     * (so a later manual/strategy SELL is permitted). If a position for the ticker
     * is already open, the new quantity is added to it.</p>
     *
     * @param ticker   equity symbol
     * @param quantity shares to buy (default 1)
     */
    @PostMapping("/buy/{ticker}")
    public ResponseEntity<Map<String, Object>> buy(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "1") int quantity) {

        String symbol = normalise(ticker);
        log.info("[ManualTradeController] POST /api/trade/buy/{} quantity={}", symbol, quantity);

        if (quantity <= 0) {
            return badRequest("quantity must be positive", symbol, quantity);
        }

        OrderService.OrderResult result = orderService.placeMarketBuy(symbol, quantity, MANUAL);

        if (result.success()) {
            // Track the position so the no-short guardrail permits a later SELL.
            // If one already exists, increase the held quantity.
            var existing = positionTracker.getPosition(symbol);
            int newQty = existing.map(Position::quantity).orElse(0) + quantity;
            // Re-register with the combined quantity (openPosition is idempotent per ticker,
            // so close then open to update the held quantity).
            positionTracker.closePosition(symbol);
            positionTracker.openPosition(new Position(
                    symbol, BigDecimal.ZERO, newQty, BigDecimal.ZERO, BigDecimal.ZERO,
                    result.clientOrderId()));
        }

        return respond("BUY", symbol, quantity, result);
    }

    // -----------------------------------------------------------------------
    // POST /api/trade/sell/{ticker}
    // -----------------------------------------------------------------------

    /**
     * Places a manual market SELL for {@code quantity} shares of {@code ticker}.
     *
     * <p>Subject to the no-short guardrail — the quantity cannot exceed the tracked
     * held quantity, so this can only flatten/reduce an existing long position, never
     * open a short. On a successful full sell the position is removed; on a partial
     * sell the held quantity is reduced.</p>
     *
     * @param ticker   equity symbol
     * @param quantity shares to sell (default 1)
     */
    @PostMapping("/sell/{ticker}")
    public ResponseEntity<Map<String, Object>> sell(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "1") int quantity) {

        String symbol = normalise(ticker);
        log.info("[ManualTradeController] POST /api/trade/sell/{} quantity={}", symbol, quantity);

        if (quantity <= 0) {
            return badRequest("quantity must be positive", symbol, quantity);
        }

        OrderService.OrderResult result = orderService.placeMarketSell(symbol, quantity, MANUAL);

        if (result.success()) {
            // Reduce or clear the tracked position to match what was sold.
            var existing = positionTracker.getPosition(symbol);
            if (existing.isPresent()) {
                int remaining = existing.get().quantity() - quantity;
                positionTracker.closePosition(symbol);
                if (remaining > 0) {
                    positionTracker.openPosition(new Position(
                            symbol, existing.get().entryPrice(), remaining,
                            existing.get().stopPrice(), existing.get().targetPrice(),
                            result.clientOrderId()));
                }
            }
        }

        return respond("SELL", symbol, quantity, result);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> respond(
            String side, String symbol, int quantity, OrderService.OrderResult result) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("side", side);
        body.put("ticker", symbol);
        body.put("quantity", quantity);
        body.put("success", result.success());
        body.put("clientOrderId", result.clientOrderId());
        body.put("orderId", result.orderId());
        body.put("message", result.message());
        body.put("accountId", client.resolveAccountId());     // Webull account the order targets
        body.put("orderRequest", result.requestSent());       // exact order fields we sent to Webull
        body.put("webullHttpStatus", result.httpStatus());    // -1 = not sent (simulated/guardrail)
        body.put("webullResponse", result.rawResponse());     // raw Webull success/failure payload
        body.put("submittedToWebull", result.httpStatus() > 0);
        body.put("mode", props.trading().mode().name());
        body.put("heldAfter", positionTracker.getPosition(symbol)
                .map(Position::quantity).orElse(0));
        body.put("timestamp", TimeFormat.nowEt());

        // Failures (e.g. SHORT_BLOCKED, TRADING_HALTED, INSUFFICIENT_BUYING_POWER)
        // return 422 so the caller can distinguish from a transport error.
        return result.success()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String msg, String symbol, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", msg);
        body.put("ticker", symbol);
        body.put("quantity", quantity);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.badRequest().body(body);
    }

    private static String normalise(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase();
    }
}
