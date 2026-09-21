package com.trading.api;

import com.trading.config.WebullProperties;
import com.trading.webull.WebullV3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — Health / connectivity checks.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/health</td><td>Liveness (no external calls)</td></tr>
 *   <tr><td>GET</td><td>/api/health/webull</td>
 *       <td>Connectivity — probes the Webull v3 API via {@link WebullV3Client}</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final WebullProperties props;
    private final WebullV3Client v3Client;

    public HealthController(WebullProperties props, WebullV3Client v3Client) {
        this.props = props;
        this.v3Client = v3Client;
    }

    /** Liveness probe — 200 while the app runs. No external calls. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", "tradingautomation");
        body.put("mode", props.trading().mode().name());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /**
     * Active connectivity + auth probe against the v3 account-list endpoint.
     *
     * <ul>
     *   <li>{@code 200 UP} — call succeeded (auth + connectivity confirmed)</li>
     *   <li>{@code 503 DOWN} — call failed; includes httpStatus + raw response</li>
     * </ul>
     *
     * <p>Issues a real authenticated request — poll sparingly.</p>
     */
    @GetMapping("/webull")
    public ResponseEntity<Map<String, Object>> webullHealth() {
        log.info("[HealthController] GET /api/health/webull — probing v3 /trading/accounts/list");

        long start = System.currentTimeMillis();
        WebullV3Client.V3Response resp = v3Client.accountList();
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", props.trading().mode().name());
        body.put("endpoint", props.resolvedApiHost());
        body.put("path", props.endpoints().accountList());
        body.put("apiVersion", "v3");
        body.put("httpStatus", resp.statusCode());
        body.put("latencyMs", latency);

        if (resp.success()) {
            int accounts = (resp.body() != null && resp.body().isArray()) ? resp.body().size() : 0;
            body.put("status", "UP");
            body.put("webullReachable", true);
            body.put("authenticated", true);
            body.put("accountsFound", accounts);
            body.put("response", resp.body());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        body.put("status", "DOWN");
        body.put("webullReachable", resp.statusCode() > 0);
        body.put("authenticated", false);
        body.put("rawResponse", resp.rawBody());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
