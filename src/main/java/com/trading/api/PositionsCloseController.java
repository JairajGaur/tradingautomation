package com.trading.api;

import com.trading.config.WebullProperties;
import com.trading.service.OrderService;
import com.trading.state.PositionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — Manually flatten all open positions.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/positions/close-all</td>
 *       <td>Market-sell every position tracked by the bot and clear the tracker</td></tr>
 * </table>
 *
 * <p>Each close is a market SELL for exactly the held quantity, so the no-short
 * guardrail always permits it. A position is removed from the tracker only when
 * its SELL is accepted.</p>
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsCloseController {

    private static final Logger log = LoggerFactory.getLogger(PositionsCloseController.class);

    private final WebullProperties props;
    private final OrderService orderService;
    private final PositionTracker positionTracker;

    public PositionsCloseController(WebullProperties props,
                                     OrderService orderService,
                                     PositionTracker positionTracker) {
        this.props = props;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
    }

    /**
     * Closes all tracked open positions with market SELL orders.
     *
     * <p>Example response:
     * <pre>{@code
     * {
     *   "requested": 2,
     *   "closed": 2,
     *   "failed": 0,
     *   "results": [
     *     { "ticker": "AAPL", "quantity": 1, "success": true, "orderId": "ord_x", "message": "" },
     *     { "ticker": "MSFT", "quantity": 1, "success": true, "orderId": "ord_y", "message": "" }
     *   ],
     *   "mode": "PAPER",
     *   "timestamp": "2024-01-15T14:30:00Z"
     * }
     * }</pre>
     */
    @PostMapping("/close-all")
    public ResponseEntity<Map<String, Object>> closeAll() {
        var open = positionTracker.allPositions();
        log.warn("[PositionsCloseController] POST /api/positions/close-all — closing {} position(s): {}",
                open.size(), open.keySet());

        List<Map<String, Object>> results = new ArrayList<>();
        int closed = 0;
        int failed = 0;

        // Snapshot the keys first (we mutate the tracker as we go)
        for (String ticker : new ArrayList<>(open.keySet())) {
            var posOpt = positionTracker.getPosition(ticker);
            if (posOpt.isEmpty()) continue;
            int qty = posOpt.get().quantity();

            OrderService.OrderResult result = orderService.placeMarketSell(ticker, qty, "MANUAL_CLOSE_ALL");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", ticker);
            row.put("quantity", qty);
            row.put("success", result.success());
            row.put("orderId", result.orderId());
            row.put("message", result.message());
            results.add(row);

            if (result.success()) {
                positionTracker.closePosition(ticker);
                closed++;
            } else {
                failed++;
                log.error("[PositionsCloseController] Failed to close ticker={}: {}", ticker, result.message());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requested", results.size());
        body.put("closed", closed);
        body.put("failed", failed);
        body.put("results", results);
        body.put("mode", props.trading().mode().name());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
