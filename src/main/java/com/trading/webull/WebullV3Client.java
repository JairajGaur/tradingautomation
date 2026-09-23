package com.trading.webull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.WebullProperties;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Direct HTTP client for the Webull OpenAPI <b>v3</b> endpoints.
 *
 * <p>Built because the bundled {@code webull-java-sdk} 0.2.17 targets the legacy
 * {@code v1} API (paths like {@code /app/subscriptions/list}) which the current
 * sandbox no longer serves — producing {@code 404 UnknownServerError}. This client
 * calls the current {@code v3} routes (e.g. {@code /trading/accounts/list}) and
 * signs each request with {@link WebullSigner} following the official
 * <a href="https://developer.webull.com/apis/docs/authentication/signature">
 * authentication spec</a>.</p>
 *
 * <h2>Headers sent</h2>
 * {@code x-app-key}, {@code x-timestamp}, {@code x-signature-algorithm=HMAC-SHA1},
 * {@code x-signature-version=1.0}, {@code x-signature-nonce}, {@code x-version=v3},
 * {@code x-signature}, and (when configured) {@code x-access-token}.
 * The app secret is never sent — it is used only to compute the signature.
 */
@Component
public class WebullV3Client {

    private static final Logger log = LoggerFactory.getLogger(WebullV3Client.class);

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final MediaType JSON = MediaType.parse("application/json");

    private final WebullProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebullV3Client(WebullProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(30))
                .build();
    }

    // -----------------------------------------------------------------------
    // Response wrapper
    // -----------------------------------------------------------------------

    /**
     * Result of a v3 call.
     *
     * @param statusCode HTTP status
     * @param success    true when statusCode is 2xx
     * @param body       parsed JSON tree (may be null if body empty/unparseable)
     * @param rawBody    raw response body string (for diagnostics)
     */
    public record V3Response(int statusCode, boolean success, JsonNode body, String rawBody) {}

    // -----------------------------------------------------------------------
    // Public request methods
    // -----------------------------------------------------------------------

    /**
     * Sends a signed GET request to a v3 path.
     *
     * @param path        request path, e.g. {@code /trading/accounts/list}
     * @param queryParams query parameters (may be null/empty)
     */
    public V3Response get(String path, Map<String, String> queryParams) {
        return execute("GET", path, queryParams, null);
    }

    /**
     * Sends a signed POST request with a JSON body to a v3 path.
     *
     * @param path        request path
     * @param queryParams query parameters (may be null/empty)
     * @param bodyObject  object serialised to compact JSON as the request body
     */
    public V3Response post(String path, Map<String, String> queryParams, Object bodyObject) {
        String bodyJson = serializeCompact(bodyObject);
        return execute("POST", path, queryParams, bodyJson);
    }

    // -----------------------------------------------------------------------
    // Typed helpers for the specific v3 endpoints the bot uses
    // -----------------------------------------------------------------------

    /** GET account list — {@code /trading/accounts/list}. */
    public V3Response accountList() {
        return get(props.endpoints().accountList(), null);
    }

    /** Cached first account ID. */
    private volatile String cachedAccountId = null;

    /**
     * Resolves and caches the first account ID from {@code /trading/accounts/list}.
     * The v3 response is an array of account objects each with an {@code account_id}.
     *
     * @return the account ID, or {@code null} if the call failed or returned none
     */
    /** Account classes/labels that are NOT equity (stock) accounts. */
    private static final java.util.Set<String> NON_EQUITY = java.util.Set.of(
            "CRYPTO", "FUTURES", "EVENTS_CASH");

    /**
     * Returns true when the account is the MARGIN <em>equity</em> account.
     *
     * <p>IMPORTANT: {@code account_type=MARGIN} alone is NOT sufficient — Webull
     * marks the FUTURES account as {@code account_type=MARGIN} too. We must match
     * on the equity margin class/label ({@code INDIVIDUAL_MARGIN} / "Individual
     * Margin") AND confirm it is an equity account (not futures/crypto/events).
     * Selecting the futures account for a stock order returns
     * {@code OPENAPI_TRADE_WEBULL_FUTURES_ACCOUNT_STOCK_ERROR}.</p>
     */
    private static boolean isMargin(JsonNode acct) {
        if (!isEquity(acct)) return false;
        String clazz = text(acct, "account_class");
        String label = text(acct, "account_label");
        return (clazz != null && clazz.equalsIgnoreCase("INDIVIDUAL_MARGIN"))
                || (label != null && label.equalsIgnoreCase("Individual Margin"));
    }

    /** Returns true when the account is an equity (stock) account — not crypto/futures/events. */
    private static boolean isEquity(JsonNode acct) {
        String clazz = text(acct, "account_class");
        String label = text(acct, "account_label");
        boolean nonEquity = (clazz != null && NON_EQUITY.contains(clazz.toUpperCase()))
                || (label != null && (label.equalsIgnoreCase("Crypto")
                                    || label.equalsIgnoreCase("Futures")
                                    || label.equalsIgnoreCase("Events Cash")));
        return !nonEquity && acct.get("account_id") != null;
    }

    public String resolveAccountId() {
        if (cachedAccountId != null) return cachedAccountId;

        // 1. Explicit override from config wins.
        String configured = props.api().accountId();
        if (configured != null && !configured.isBlank()) {
            cachedAccountId = configured.trim();
            log.info("[WebullV3Client] Using configured accountId={}", cachedAccountId);
            return cachedAccountId;
        }

        V3Response resp = accountList();
        if (!resp.success() || resp.body() == null) {
            log.warn("[WebullV3Client] accountList failed: status={} body={}",
                    resp.statusCode(), resp.rawBody());
            return null;
        }
        JsonNode arr = resp.body();
        if (!arr.isArray() || arr.size() == 0) {
            log.warn("[WebullV3Client] accountList returned no accounts: {}", resp.rawBody());
            return null;
        }

        // 2. Prefer the MARGIN account for equity trading. Fall back to any equity
        //    (non-crypto/futures/events) account, then finally to the first account.
        //    Equity orders placed against a non-equity account get "Instrument type invalid".
        JsonNode margin = null;
        JsonNode equity = null;
        for (JsonNode acct : arr) {
            if (acct.get("account_id") == null) continue;
            if (margin == null && isMargin(acct)) {
                margin = acct;
            }
            if (equity == null && isEquity(acct)) {
                equity = acct;
            }
        }

        JsonNode chosen = margin != null ? margin
                : equity != null ? equity
                : arr.get(0);
        JsonNode id = chosen.get("account_id");
        if (id == null) {
            log.warn("[WebullV3Client] No usable account_id in account list: {}", resp.rawBody());
            return null;
        }
        cachedAccountId = id.asText();
        String kind = margin != null ? "MARGIN"
                : equity != null ? "EQUITY (non-margin)"
                : "FALLBACK (first account)";
        log.info("[WebullV3Client] Resolved {} accountId={} (class={} label={} type={})",
                kind, cachedAccountId, text(chosen, "account_class"),
                text(chosen, "account_label"), text(chosen, "account_type"));
        if (margin == null && equity == null) {
            log.warn("[WebullV3Client] No margin or equity account matched — fell back to first account. " +
                    "If orders fail with 'Instrument type invalid', set webull.trading.account-id explicitly.");
        } else if (margin == null) {
            log.warn("[WebullV3Client] No MARGIN account found — using a non-margin equity account instead.");
        }
        return cachedAccountId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** GET account balance for an account — {@code /trading/assets/balances/get?account_id=}. */
    public V3Response accountBalance(String accountId) {
        return get(props.endpoints().accountBalance(), Map.of("account_id", accountId));
    }

    /** GET positions for an account — {@code /trading/assets/positions/list?account_id=}. */
    public V3Response positions(String accountId, int pageSize, String lastId) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("account_id", accountId);
        q.put("page_size", String.valueOf(pageSize));
        if (lastId != null && !lastId.isBlank()) {
            q.put("last_instrument_id", lastId);
        }
        return get(props.endpoints().positions(), q);
    }

    /**
     * POST historical bars — {@code /market-data/stocks/bars/list}.
     *
     * <p>Body: {@code {symbols:[...], category, timespan, count, real_time_required}}.
     * Response: {@code {result:[{symbol, instrument_id, result:[{time,open,close,high,low,volume}]}]}}.</p>
     */
    public V3Response bars(String symbol, String category, String timespan, int count) {
        return bars(symbol, category, timespan, count, null);
    }

    /**
     * POST historical bars with an explicit trading-sessions filter.
     *
     * @param tradingSessions comma-separated sessions (RTH,PRE,ATH,OVN), or null to
     *                        omit the field (Webull default applies)
     */
    public V3Response bars(String symbol, String category, String timespan, int count,
                           String tradingSessions) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symbols", java.util.List.of(symbol));
        body.put("category", category);
        body.put("timespan", timespan);
        body.put("count", count);
        body.put("real_time_required", true);
        if (tradingSessions != null && !tradingSessions.isBlank()) {
            body.put("trading_sessions", tradingSessions);
        }
        return post(props.endpoints().bars(), null, body);
    }

    /**
     * POST place order — {@code /trading/orders/place}.
     *
     * <p>Webull expects the request body to wrap the order(s) in a {@code new_orders}
     * array, with {@code account_id} and {@code client_combo_order_id} at the top level:
     * <pre>{@code
     * { "account_id": "...", "client_combo_order_id": "...", "new_orders": [ { ...order... } ] }
     * }</pre>
     *
     * @param accountId the account to trade in
     * @param order     a single order as a Map (the fields inside one {@code new_orders} entry)
     */
    public V3Response placeOrder(String accountId, Map<String, Object> order) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("account_id", accountId);
        // A combo id is required to group the order(s); reuse the order's client id.
        Object clientOrderId = order.get("client_order_id");
        body.put("client_combo_order_id",
                clientOrderId != null ? clientOrderId.toString() : java.util.UUID.randomUUID().toString().replace("-", ""));
        body.put("new_orders", java.util.List.of(order));
        return post(props.endpoints().placeOrder(), null, body);
    }

    /**
     * GET real-time snapshot — {@code /market-data/stocks/snapshots/list?symbols=&category=}.
     */
    public V3Response snapshot(String symbol, String category) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("symbols", symbol);
        q.put("category", category);
        return get(props.endpoints().snapshot(), q);
    }

    /**
     * GET historical orders — {@code /trading/orders/historical-orders/list?account_id=}.
     * Defaults to the last 7 days when no time range is supplied.
     */
    public V3Response orderHistory(String accountId, int pageSize, String paginationKey) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("account_id", accountId);
        q.put("page_size", String.valueOf(pageSize));
        if (paginationKey != null && !paginationKey.isBlank()) {
            q.put("pagination_key", paginationKey);
        }
        return get(props.endpoints().orderHistory(), q);
    }

    /** GET open (resting) orders — {@code /trading/orders/open-orders/list?account_id=}. */
    public V3Response openOrders(String accountId, int pageSize, String paginationKey) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("account_id", accountId);
        q.put("page_size", String.valueOf(pageSize));
        if (paginationKey != null && !paginationKey.isBlank()) {
            q.put("pagination_key", paginationKey);
        }
        return get(props.endpoints().openOrders(), q);
    }

    // -----------------------------------------------------------------------
    // Core execution
    // -----------------------------------------------------------------------

    /** Max total attempts for idempotent GETs (1 = no retry). POSTs are never retried. */
    private static final int GET_MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_BACKOFF_MS = 300;

    private V3Response execute(String method, String path,
                               Map<String, String> queryParams, String bodyJson) {
        // Order placement and any other POST is NEVER retried: a retry after a
        // request that actually succeeded (but whose response was lost) would place
        // a duplicate order. Only idempotent GETs are retried.
        if (!"GET".equals(method)) {
            return executeOnce(method, path, queryParams, bodyJson);
        }

        V3Response resp = null;
        for (int attempt = 1; attempt <= GET_MAX_ATTEMPTS; attempt++) {
            resp = executeOnce(method, path, queryParams, bodyJson);
            if (!isRetryable(resp)) {
                return resp;
            }
            if (attempt < GET_MAX_ATTEMPTS) {
                long backoff = RETRY_BASE_BACKOFF_MS * attempt;   // linear backoff
                log.warn("[WebullV3Client] GET {} attempt {}/{} failed (status={}); retrying in {}ms",
                        path, attempt, GET_MAX_ATTEMPTS, resp.statusCode(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return resp;
                }
            }
        }
        log.warn("[WebullV3Client] GET {} failed after {} attempts (status={})",
                path, GET_MAX_ATTEMPTS, resp == null ? "n/a" : resp.statusCode());
        return resp;
    }

    /** Retry only on transport failure (-1), 429, or 5xx. 4xx (except 429) are not retried. */
    private static boolean isRetryable(V3Response resp) {
        int s = resp.statusCode();
        return s == -1 || s == 429 || (s >= 500 && s <= 599);
    }

    private V3Response executeOnce(String method, String path,
                                   Map<String, String> queryParams, String bodyJson) {
        String host      = props.resolvedApiHost();
        String timestamp = TS_FORMAT.format(Instant.now());
        String nonce     = UUID.randomUUID().toString().replace("-", "");

        // Sorted query params also feed the signature — keep them consistent.
        TreeMap<String, String> query = new TreeMap<>();
        if (queryParams != null) query.putAll(queryParams);

        String signature = WebullSigner.sign(
                path, query, bodyJson,
                props.api().appKey(), props.api().appSecret(),
                host, timestamp, nonce);

        // Build the URL
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(host)
                .addPathSegments(path.startsWith("/") ? path.substring(1) : path);
        for (Map.Entry<String, String> e : query.entrySet()) {
            urlBuilder.addQueryParameter(e.getKey(), e.getValue());
        }
        HttpUrl url = urlBuilder.build();

        // Build the request with the signed headers
        Request.Builder rb = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("x-app-key", props.api().appKey())
                .addHeader("x-timestamp", timestamp)
                .addHeader("x-signature-algorithm", WebullSigner.ALGORITHM)
                .addHeader("x-signature-version", WebullSigner.SIGNATURE_VERSION)
                .addHeader("x-signature-nonce", nonce)
                .addHeader("x-version", WebullSigner.API_VERSION)
                .addHeader("x-signature", signature);

        // Optional 2FA access token (only if configured)
        String token = props.api().accessToken();
        if (token != null && !token.isBlank()) {
            rb.addHeader("x-access-token", token);
        }

        if ("POST".equals(method)) {
            rb.post(RequestBody.create(bodyJson == null ? "" : bodyJson, JSON));
        } else {
            rb.get();
        }

        // ── Log the outgoing request (DEBUG, opt-in via webull.api.log-requests) ──
        // Secrets are never logged: the app secret is not a header, and the
        // x-signature / x-access-token values are masked.
        boolean logRequests = props.api().logRequests() && log.isDebugEnabled();
        if (logRequests) {
            log.debug("[WebullV3Client] --> {} https://{}{}", method, host, url.encodedQuery() == null
                    ? path : path + "?" + url.encodedQuery());
            log.debug("[WebullV3Client]     headers: x-app-key={} x-timestamp={} x-version={} " +
                            "x-signature-algorithm={} x-signature-nonce={} x-signature={} x-access-token={}",
                    props.api().appKey(), timestamp, WebullSigner.API_VERSION, WebullSigner.ALGORITHM,
                    nonce, mask(signature), token != null && !token.isBlank() ? mask(token) : "(none)");
            if (bodyJson != null && !bodyJson.isEmpty()) {
                log.debug("[WebullV3Client]     body: {}", bodyJson);
            }
        }

        long start = System.currentTimeMillis();
        try (Response resp = http.newCall(rb.build()).execute()) {
            String raw = resp.body() != null ? resp.body().string() : "";
            JsonNode json = parseJsonSafe(raw);
            boolean ok = resp.isSuccessful();
            long ms = System.currentTimeMillis() - start;
            if (logRequests) {
                log.debug("[WebullV3Client] <-- {} {} HTTP {} ({}ms) body={}",
                        method, path, resp.code(), ms, raw);
            }
            return new V3Response(resp.code(), ok, json, raw);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("[WebullV3Client] <-- {} {} FAILED ({}ms)", method, path, ms, e);
            return new V3Response(-1, false, null, e.getMessage());
        }
    }

    /** Masks a sensitive value, showing only the first 4 and last 4 characters. */
    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "(empty)";
        if (value.length() <= 8) return "*".repeat(value.length());
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String serializeCompact(Object obj) {
        if (obj == null) return null;
        try {
            // Jackson emits compact JSON by default (no spaces) — matches the
            // signature MD5 requirement.
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize request body", e);
        }
    }

    private JsonNode parseJsonSafe(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
