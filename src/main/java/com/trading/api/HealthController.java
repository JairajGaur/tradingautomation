package com.trading.api;

import com.trading.broker.BrokerClient;
import com.trading.broker.model.ConnectivityStatus;
import com.trading.config.AppProperties;
import com.trading.llm.LlmClient;
import com.trading.webull.WebullApiProperties;
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
 *       <td>Connectivity — probes the broker API via {@link BrokerClient}</td></tr>
 *   <tr><td>GET</td><td>/api/health/llm</td>
 *       <td>Connectivity — probes the local LLM runtime via {@link LlmClient}</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final AppProperties props;
    private final WebullApiProperties brokerApi;
    private final BrokerClient v3Client;
    private final LlmClient llmClient;

    public HealthController(AppProperties props, WebullApiProperties brokerApi,
                            BrokerClient v3Client, LlmClient llmClient) {
        this.props = props;
        this.brokerApi = brokerApi;
        this.v3Client = v3Client;
        this.llmClient = llmClient;
    }

    /** Liveness probe — 200 while the app runs. No external calls. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", "tradingautomation");
        body.put("mode", props.engine().mode().name());
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
        ConnectivityStatus status = v3Client.checkConnectivity();
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", props.engine().mode().name());
        body.put("endpoint", brokerApi.resolvedApiHost());
        body.put("path", brokerApi.endpoints().accountList());
        body.put("apiVersion", "v3");
        body.put("httpStatus", status.httpStatus());
        body.put("latencyMs", latency);

        if (status.authenticated()) {
            body.put("status", "UP");
            body.put("webullReachable", true);
            body.put("authenticated", true);
            body.put("accountsFound", status.accountsFound());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        body.put("status", "DOWN");
        body.put("webullReachable", status.reachable());
        body.put("authenticated", false);
        body.put("rawResponse", status.detail());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Health probe for the local-LLM advisory layer (app.llm.*).
     *
     * <p>Checks the configured runtime (Ollama by default) is reachable and whether the
     * configured model is installed. Uses a cheap {@code /api/version} + {@code /api/tags}
     * probe — it does NOT run a generation, so it never cold-loads a model and is safe to
     * poll.</p>
     *
     * <ul>
     *   <li>{@code 200 UP} — runtime reachable. {@code modelReady} is true when the
     *       configured model is also installed (false → the analyst would fail safe).</li>
     *   <li>{@code 200 DISABLED} — {@code app.llm.enabled=false}; nothing is probed.</li>
     *   <li>{@code 503 DOWN} — runtime unreachable; {@code detail} shows why. The app still
     *       runs fine — the analyst simply fails safe to WAIT until the runtime returns.</li>
     * </ul>
     */
    @GetMapping("/llm")
    public ResponseEntity<Map<String, Object>> llmHealth() {
        AppProperties.Llm cfg = props.llm();
        log.info("[HealthController] GET /api/health/llm — enabled={} baseUrl={} model={}",
                cfg.enabled(), cfg.baseUrl(), cfg.model());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseUrl", cfg.baseUrl());
        body.put("model", cfg.model());
        body.put("enabled", cfg.enabled());

        if (!cfg.enabled()) {
            body.put("status", "DISABLED");
            body.put("reachable", false);
            body.put("detail", "app.llm.enabled=false — the analyst layer is off");
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        long start = System.currentTimeMillis();
        LlmClient.Ping ping = llmClient.ping();
        long latency = System.currentTimeMillis() - start;
        body.put("latencyMs", latency);

        if (ping.reachable()) {
            body.put("status", "UP");
            body.put("reachable", true);
            body.put("version", ping.version());
            body.put("modelReady", ping.modelInstalled());
            if (!ping.modelInstalled()) {
                body.put("warning", "Model '" + cfg.model()
                        + "' is not installed — pull it: ollama pull " + cfg.model());
            }
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        body.put("status", "DOWN");
        body.put("reachable", false);
        body.put("detail", ping.detail());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
