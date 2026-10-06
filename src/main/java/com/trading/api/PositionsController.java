package com.trading.api;

import com.trading.broker.BrokerClient;
import com.trading.config.WebullProperties;
import com.trading.state.PositionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — Positions endpoints (broker API via {@link BrokerClient}).
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsController {

    private static final Logger log = LoggerFactory.getLogger(PositionsController.class);

    private final WebullProperties props;
    private final BrokerClient client;
    private final PositionTracker positionTracker;

    public PositionsController(WebullProperties props,
                                BrokerClient client,
                                PositionTracker positionTracker) {
        this.props = props;
        this.client = client;
        this.positionTracker = positionTracker;
    }

    /**
     * Live holdings from the broker, mapped to neutral per-holding fields
     * ({@code symbol}, {@code quantity}, {@code unitCost}).
     *
     * <p>Note: the {@code pageSize}/{@code lastId} params are retained for backward
     * compatibility but the neutral holdings fetch returns the current holdings set
     * (up to the adapter's page size) in one call.</p>
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getPositions(
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String lastId) {

        log.info("[PositionsController] GET /api/positions pageSize={} lastId={}", pageSize, lastId);

        try {
            String accountId = client.resolveAccountId();
            if (accountId == null) {
                return badGateway(Map.of("kind", "NO_ACCOUNT",
                        "message", "Could not resolve account ID from /trading/accounts/list"));
            }

            com.trading.broker.model.HoldingsResult res = client.fetchHoldings(accountId);
            if (!res.valid()) {
                return badGateway(Map.of("kind", "HTTP_ERROR",
                        "message", "Positions request failed"));
            }

            List<Map<String, String>> holdings = res.list().stream()
                    .map(h -> Map.of(
                            "symbol",   h.symbol(),
                            "quantity", String.valueOf(h.quantity()),
                            "unitCost", h.unitCost().toPlainString()
                    ))
                    .toList();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("holdings",  holdings);
            body.put("count",     holdings.size());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);

        } catch (com.trading.broker.BrokerException e) {
            log.error("[PositionsController] Error fetching positions", e);
            return badGateway(WebullErrors.fromBroker(e));
        } catch (Exception e) {
            log.error("[PositionsController] Error fetching positions", e);
            return badGateway(WebullErrors.fromThrowable(e));
        }
    }

    /**
     * Positions tracked in-memory by the bot (opened by a strategy, not yet closed).
     */
    @GetMapping("/tracked")
    public ResponseEntity<Map<String, Object>> getTrackedPositions() {
        log.info("[PositionsController] GET /api/positions/tracked");

        List<Map<String, String>> tracked = positionTracker.allPositions().values().stream()
                .map(p -> Map.of(
                        "ticker",        p.ticker(),
                        "entryPrice",    p.entryPrice().toPlainString(),
                        "quantity",      String.valueOf(p.quantity()),
                        "stopPrice",     p.stopPrice().toPlainString(),
                        "targetPrice",   p.targetPrice().toPlainString(),
                        "clientOrderId", p.clientOrderId()
                ))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positions", tracked);
        body.put("count",     tracked.size());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> badGateway(Map<String, Object> webullError) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Failed to fetch positions");
        err.put("mode", props.trading().mode().name());
        err.put("endpoint", props.resolvedApiHost());
        err.put("webullError", webullError);
        err.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err);
    }
}
