package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.config.WebullProperties;
import com.trading.webull.WebullV3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
 * <p>Backed by the Webull v3 Data API endpoint
 * {@code GET /market-data/stocks/snapshots/list}. The ticker does not need to be
 * in the watchlist — any US stock symbol works.</p>
 */
@RestController
@RequestMapping("/api/price")
public class PriceController {

    private static final Logger log = LoggerFactory.getLogger(PriceController.class);

    private static final String CATEGORY_US_STOCK = "US_STOCK";

    private final WebullProperties props;
    private final WebullV3Client client;

    public PriceController(WebullProperties props, WebullV3Client client) {
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
     *   "snapshot": { ...full raw snapshot object... },
     *   "timestamp": "2024-01-15T14:30:00Z"
     * }
     * }</pre>
     *
     * <p>Returns {@code 502} with a {@code webullError} block on a Webull failure,
     * or {@code 404} if the symbol returns no snapshot.</p>
     */
    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getPrice(@PathVariable String ticker) {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        log.info("[PriceController] GET /api/price/{}", symbol);

        WebullV3Client.V3Response resp;
        try {
            resp = client.snapshot(symbol, CATEGORY_US_STOCK);
        } catch (Exception e) {
            return badGateway(symbol, WebullErrors.fromThrowable(e));
        }

        if (!resp.success()) {
            return badGateway(symbol, WebullErrors.fromResponse(resp));
        }

        // Snapshot response is an array of per-symbol objects
        JsonNode snap = firstSnapshot(resp.body());
        if (snap == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "No snapshot returned for ticker");
            body.put("ticker", symbol);
            body.put("mode", props.trading().mode().name());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("price", text(snap, "price", "last_price", "close"));
        body.put("open", text(snap, "open"));
        body.put("high", text(snap, "high"));
        body.put("low", text(snap, "low"));
        body.put("preClose", text(snap, "pre_close", "preClose"));
        body.put("volume", text(snap, "volume"));
        body.put("change", text(snap, "change"));
        body.put("changeRatio", text(snap, "change_ratio", "changeRatio"));
        body.put("snapshot", snap);   // full raw object for any extra fields
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JsonNode firstSnapshot(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root.size() > 0 ? root.get(0) : null;
        if (root.has("data")) return firstSnapshot(root.get("data"));
        if (root.has("result")) return firstSnapshot(root.get("result"));
        // already a single object with price fields
        return root.has("price") || root.has("close") ? root : null;
    }

    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) return v.asText();
        }
        return null;
    }

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
