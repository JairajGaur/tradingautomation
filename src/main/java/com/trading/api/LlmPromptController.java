package com.trading.api;

import com.trading.config.AppProperties;
import com.trading.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — direct, free-form passthrough to the local LLM.
 *
 * <p>The simplest possible way to talk to the model: send a prompt, get the raw text reply.
 * No trading logic, no JSON contract, no distillation — just a thin wrapper over the runtime,
 * for quick testing and prompt tinkering. Pick the model and timeout per call.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/llm/prompt?prompt=...</td><td>Quick prompt via query param</td></tr>
 *   <tr><td>POST</td><td>/api/llm/prompt</td><td>Prompt in the request body (for longer text)</td></tr>
 * </table>
 *
 * <p>Params (both verbs): {@code model} (default {@code app.llm.model}), {@code think}
 * (default false), {@code timeout} seconds (default {@code app.llm.timeout-seconds}).
 * Requires {@code app.llm.enabled=true}. Advisory/testing only — nothing here trades.</p>
 */
@RestController
@RequestMapping("/api/llm")
public class LlmPromptController {

    private static final Logger log = LoggerFactory.getLogger(LlmPromptController.class);

    private final AppProperties props;
    private final LlmClient client;

    public LlmPromptController(AppProperties props, LlmClient client) {
        this.props = props;
        this.client = client;
    }

    /** Quick prompt via query param: GET /api/llm/prompt?prompt=hello&model=qwen3:14b */
    @GetMapping("/prompt")
    public ResponseEntity<Map<String, Object>> promptGet(
            @RequestParam String prompt,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "false") boolean think,
            @RequestParam(required = false) Integer timeout) {
        return run(prompt, model, think, timeout);
    }

    /** Body prompt (for longer text): POST /api/llm/prompt?model=... with the prompt as the body. */
    @PostMapping("/prompt")
    public ResponseEntity<Map<String, Object>> promptPost(
            @RequestBody String prompt,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "false") boolean think,
            @RequestParam(required = false) Integer timeout) {
        return run(prompt, model, think, timeout);
    }

    private ResponseEntity<Map<String, Object>> run(String prompt, String model,
                                                    boolean think, Integer timeout) {
        AppProperties.Llm cfg = props.llm();
        String effectiveModel = (model != null && !model.isBlank()) ? model.trim() : cfg.model();

        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "prompt must not be empty",
                    "timestamp", TimeFormat.nowEt()));
        }
        if (!cfg.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "LLM disabled (app.llm.enabled=false)",
                    "timestamp", TimeFormat.nowEt()));
        }

        log.info("[LlmPromptController] prompt len={} model={} think={}",
                prompt.length(), effectiveModel, think);

        long start = System.currentTimeMillis();
        String reply;
        try {
            reply = client.generateText(prompt, effectiveModel, think, timeout == null ? 0 : timeout);
        } catch (RuntimeException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "LLM request failed");
            body.put("detail", e.getMessage());
            body.put("model", effectiveModel);
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
        long ms = System.currentTimeMillis() - start;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", effectiveModel);
        body.put("think", think);
        body.put("latencyMs", ms);
        body.put("prompt", prompt);
        body.put("response", reply);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
