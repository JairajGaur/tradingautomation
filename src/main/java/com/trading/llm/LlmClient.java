package com.trading.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.config.AppProperties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Thin HTTP client for a local LLM runtime (Ollama-style {@code /api/generate}).
 *
 * <p>Deliberately reuses the OkHttp + Jackson stack already on the classpath (same tools
 * as {@code com.trading.webull}), so adding the LLM capability pulls in <b>no new
 * dependency</b>. It knows nothing about trading — it just sends a prompt and returns the
 * model's raw text (which, with {@code format=json}, is a JSON string the caller parses).</p>
 *
 * <p>Transport failures surface as {@link LlmException}; callers (the analyst service) turn
 * those into a fail-safe verdict so trading is never affected by an LLM outage.</p>
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(AppProperties props) {
        this.props = props;
    }

    /**
     * Sends {@code prompt} to the configured model and returns the model's response text.
     * Requests strict JSON output ({@code format=json}) so the caller can parse a verdict.
     *
     * @param prompt the fully-rendered prompt (system instructions + briefing)
     * @return the model's {@code response} string (expected to be a JSON object)
     * @throws LlmException on any transport, HTTP, or decoding failure
     */
    public String generateJson(String prompt) {
        return generateJson(prompt, null, 0);
    }

    /**
     * Model/timeout-override variant — same contract as {@link #generateJson(String)} but lets
     * a caller run a DIFFERENT model (e.g. a deeper {@code qwen3:14b} for the off-critical-path
     * alert review) and a longer timeout, without changing {@code app.llm.model} globally.
     *
     * @param prompt          the fully-rendered prompt
     * @param modelOverride   model tag to use; null/blank → {@code app.llm.model}
     * @param timeoutOverride per-call timeout in seconds; &le;0 → {@code app.llm.timeout-seconds}
     */
    public String generateJson(String prompt, String modelOverride, int timeoutOverride) {
        AppProperties.Llm cfg = props.llm();
        String url = cfg.generateUrl();
        String model = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : cfg.model();
        int timeout = timeoutOverride > 0 ? timeoutOverride : cfg.timeoutSeconds();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("format", "json");          // force a JSON object back
        body.put("think", cfg.think());      // reasoning monologue on/off
        ObjectNode options = body.putObject("options");
        options.put("temperature", cfg.temperature().doubleValue());

        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(timeout))
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeout)))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();

        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                    .build();
        } catch (Exception e) {
            throw new LlmException("Failed to encode LLM request", e);
        }

        long t0 = System.nanoTime();
        try (Response resp = http.newCall(request).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                throw new LlmException("LLM HTTP " + resp.code() + " from " + url + ": "
                        + abbreviate(raw));
            }
            JsonNode root = mapper.readTree(raw);
            JsonNode response = root.get("response");
            if (response == null || response.isNull()) {
                throw new LlmException("LLM response missing 'response' field: " + abbreviate(raw));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[LlmClient] model={} url={} took={}ms", model, url, ms);
            return response.asText();
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("LLM request to " + url + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Schema-constrained generation (the "Jev-like" typed path): sends {@code prompt} with a
     * JSON <b>schema</b> as the {@code format}, so the runtime forces the model to emit output
     * conforming to that schema — not just "some JSON". The caller can then deserialize the
     * reply straight into a typed record with confidence it matches the expected shape.
     *
     * @param prompt          the fully-rendered prompt
     * @param schema          a JSON-schema object (Jackson node) describing the required output
     * @param modelOverride   model tag; null/blank → {@code app.llm.model}
     * @param timeoutOverride per-call timeout in seconds; &le;0 → {@code app.llm.timeout-seconds}
     * @return the model's {@code response} string (guaranteed to match {@code schema})
     * @throws LlmException on any transport/HTTP/decoding failure
     */
    public String generateSchema(String prompt, JsonNode schema, String modelOverride, int timeoutOverride) {
        AppProperties.Llm cfg = props.llm();
        String url = cfg.generateUrl();
        String model = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : cfg.model();
        int timeout = timeoutOverride > 0 ? timeoutOverride : cfg.timeoutSeconds();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.set("format", schema);          // JSON SCHEMA — forces conforming output
        body.put("think", cfg.think());
        ObjectNode options = body.putObject("options");
        options.put("temperature", cfg.temperature().doubleValue());

        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(timeout))
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeout)))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();

        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                    .build();
        } catch (Exception e) {
            throw new LlmException("Failed to encode LLM request", e);
        }

        long t0 = System.nanoTime();
        try (Response resp = http.newCall(request).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                throw new LlmException("LLM HTTP " + resp.code() + " from " + url + ": "
                        + abbreviate(raw));
            }
            JsonNode root = mapper.readTree(raw);
            JsonNode response = root.get("response");
            if (response == null || response.isNull()) {
                throw new LlmException("LLM response missing 'response' field: " + abbreviate(raw));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[LlmClient] (schema) model={} url={} took={}ms", model, url, ms);
            return response.asText();
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("LLM request to " + url + " failed: " + e.getMessage(), e);
        }
    }

    /** Exposes the shared ObjectMapper so callers can build schema nodes / parse replies. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Free-form generation: sends {@code prompt} and returns the model's plain-text reply.
     * Unlike {@link #generateJson}, it does NOT force {@code format=json}, so this is the raw
     * passthrough used by the direct "prompt the LLM" endpoint.
     *
     * @param prompt          the prompt text
     * @param modelOverride   model tag; null/blank → {@code app.llm.model}
     * @param think           whether to enable the model's reasoning monologue
     * @param timeoutOverride per-call timeout in seconds; &le;0 → {@code app.llm.timeout-seconds}
     * @throws LlmException on any transport/HTTP/decoding failure
     */
    public String generateText(String prompt, String modelOverride, boolean think, int timeoutOverride) {
        AppProperties.Llm cfg = props.llm();
        String url = cfg.generateUrl();
        String model = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : cfg.model();
        int timeout = timeoutOverride > 0 ? timeoutOverride : cfg.timeoutSeconds();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("think", think);
        ObjectNode options = body.putObject("options");
        options.put("temperature", cfg.temperature().doubleValue());

        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(timeout))
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeout)))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();

        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                    .build();
        } catch (Exception e) {
            throw new LlmException("Failed to encode LLM request", e);
        }

        long t0 = System.nanoTime();
        try (Response resp = http.newCall(request).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                throw new LlmException("LLM HTTP " + resp.code() + " from " + url + ": "
                        + abbreviate(raw));
            }
            JsonNode root = mapper.readTree(raw);
            JsonNode response = root.get("response");
            if (response == null || response.isNull()) {
                throw new LlmException("LLM response missing 'response' field: " + abbreviate(raw));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[LlmClient] (text) model={} url={} took={}ms", model, url, ms);
            return response.asText();
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("LLM request to " + url + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Lightweight health probe: checks the runtime is reachable (via {@code /api/version})
     * and whether the configured model is installed (via {@code /api/tags}). Does NOT run a
     * generation, so it never cold-loads a model — cheap enough to poll.
     *
     * <p>Never throws: an unreachable server yields a result with {@code reachable=false} and
     * a {@code detail} message, mirroring the broker connectivity probe's style.</p>
     *
     * @return a {@link Ping} snapshot of runtime reachability + model availability
     */
    public Ping ping() {
        AppProperties.Llm cfg = props.llm();
        String base = cfg.baseUrl() == null ? "" : cfg.baseUrl().replaceAll("/+$", "");

        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(Math.min(5, Math.max(1, cfg.timeoutSeconds()))))
                .connectTimeout(Duration.ofSeconds(Math.min(3, Math.max(1, cfg.timeoutSeconds()))))
                .readTimeout(Duration.ofSeconds(Math.min(5, Math.max(1, cfg.timeoutSeconds()))))
                .build();

        // 1) /api/version — is the runtime up?
        String version;
        try (Response resp = http.newCall(new Request.Builder()
                .url(base + "/api/version").get().build()).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                return Ping.down("version HTTP " + resp.code() + ": " + abbreviate(raw));
            }
            JsonNode v = mapper.readTree(raw).get("version");
            version = v == null || v.isNull() ? "?" : v.asText();
        } catch (Exception e) {
            return Ping.down("cannot reach " + base + ": " + e.getMessage());
        }

        // 2) /api/tags — is the configured model installed? (best-effort; non-fatal)
        boolean modelInstalled = false;
        try (Response resp = http.newCall(new Request.Builder()
                .url(base + "/api/tags").get().build()).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (resp.isSuccessful()) {
                JsonNode models = mapper.readTree(raw).get("models");
                if (models != null && models.isArray()) {
                    for (JsonNode m : models) {
                        JsonNode name = m.get("name");
                        if (name != null && cfg.model().equals(name.asText())) {
                            modelInstalled = true;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[LlmClient] /api/tags probe failed: {}", e.getMessage());
        }

        return new Ping(true, version, cfg.model(), modelInstalled, "");
    }

    /**
     * Result of {@link #ping()}.
     *
     * @param reachable      true if the runtime answered {@code /api/version}
     * @param version        the runtime version string ("?" if unknown)
     * @param model          the configured model tag that was checked
     * @param modelInstalled true if {@code model} appears in the runtime's installed list
     * @param detail         error summary when {@code reachable=false}; else blank
     */
    public record Ping(boolean reachable, String version, String model,
                       boolean modelInstalled, String detail) {
        static Ping down(String detail) {
            return new Ping(false, "?", null, false, detail);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** Thrown on any LLM transport/decoding failure; callers fail safe. */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
