package com.trading.api;

import com.trading.broker.BrokerClient;
import com.trading.broker.BrokerException;
import com.trading.broker.model.PriceSnapshot;
import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — Current price / snapshot for any stock.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/price/{ticker}</td>
 *       <td>Latest price + key snapshot fields for a stock</td></tr>
 * </table>
 *
 * <p>Backed by the broker's data API via {@link BrokerClient#fetchPriceSnapshot}.
 * The ticker does not need to be in the watchlist — any US stock symbol works.</p>
 */
@RestController
@RequestMapping("/api/price")
public class PriceController {

    private static final Logger log = LoggerFactory.getLogger(PriceController.class);

    private final WebullProperties props;
    private final BrokerClient client;

    public PriceController(WebullProperties props, BrokerClient client) {
        this.props = props;
        this.client = client;
    }

    /**
     * Returns the current price and snapshot data for {@code ticker}.
     *
     * <p>Example — {@code GET /api/price/AAPL}:
     * <pre>{@code
     * {
     *   "ticker": "AAPL",
     *   "price": "185.50",
     *   "open": "184.00",
     *   "high": "186.20",
     *   "low": "183.80",
     *   "preClose": "184.00",
     *   "volume": "52340000",
     *   "change": "1.50",
     *   "changeRatio": "0.0082",
     *   "timestamp": "2024-01-15T14:30:00Z"
     * }
     * }</pre>
     *
     * <p>Returns {@code 502} with a {@code webullError} block on a broker failure,
     * or {@code 404} if the symbol returns no snapshot.</p>
     */
    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getPrice(@PathVariable String ticker) {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        log.info("[PriceController] GET /api/price/{}", symbol);

        PriceSnapshot snap;
        try {
            snap = client.fetchPriceSnapshot(symbol);
        } catch (BrokerException e) {
            return badGateway(symbol, WebullErrors.fromBroker(e));
        } catch (Exception e) {
            return badGateway(symbol, WebullErrors.fromThrowable(e));
        }

        if (snap == null || snap.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "No snapshot returned for ticker");
            body.put("ticker", symbol);
            body.put("mode", props.trading().mode().name());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("price", snap.price());
        body.put("open", snap.open());
        body.put("high", snap.high());
        body.put("low", snap.low());
        body.put("preClose", snap.preClose());
        body.put("volume", snap.volume());
        body.put("change", snap.change());
        body.put("changeRatio", snap.changeRatio());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> badGateway(String symbol, Map<String, Object> webullError) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Snapshot request failed");
        body.put("ticker", symbol);
        body.put("mode", props.trading().mode().name());
        body.put("endpoint", props.resolvedApiHost());
        body.put("webullError", webullError);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
}
