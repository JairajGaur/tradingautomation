package com.trading.api;

import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics endpoint to verify which Webull credentials the application actually
 * loaded — WITHOUT exposing the full secret in plaintext.
 *
 * <h2>Why not print the raw secret?</h2>
 * Logging or returning a full app secret is a security risk: logs and HTTP responses
 * get persisted, copied into tickets, and scraped. A leaked secret lets anyone sign
 * requests as you. Instead this endpoint returns:
 * <ul>
 *   <li><b>App key</b> — in full (it is an identifier sent in request headers anyway).</li>
 *   <li><b>App secret</b> — masked (first 4 + last 4 chars), plus its length and a
 *       SHA-256 fingerprint.</li>
 * </ul>
 *
 * <h2>How to verify an exact match</h2>
 * Compute the SHA-256 of your intended secret locally and compare it to the
 * {@code appSecretSha256} field. Matching fingerprints prove the exact value was
 * loaded — without the plaintext ever leaving the process.
 * <pre>{@code
 *   echo -n "your_secret_here" | shasum -a 256
 * }</pre>
 *
 * <h2>Safety gate</h2>
 * This endpoint is DISABLED by default. Enable it explicitly for local debugging:
 * <pre>{@code
 *   webull.diagnostics.credentials-endpoint-enabled: true
 * }</pre>
 * When disabled it returns 404. Never enable it in a shared/production environment.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class CredentialsDiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(CredentialsDiagnosticsController.class);

    private final WebullProperties props;
    private final boolean enabled;

    public CredentialsDiagnosticsController(
            WebullProperties props,
            @Value("${webull.diagnostics.credentials-endpoint-enabled:false}") boolean enabled) {
        this.props = props;
        this.enabled = enabled;
    }

    @GetMapping("/credentials")
    public ResponseEntity<Map<String, Object>> credentials() {
        if (!enabled) {
            // Behave as if the endpoint does not exist when the flag is off.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Diagnostics endpoint is disabled",
                    "hint", "Set webull.diagnostics.credentials-endpoint-enabled=true to enable "
                          + "(local debugging only — never in production)."));
        }

        log.warn("[CredentialsDiagnostics] Credentials diagnostics endpoint accessed — " +
                 "masked values only, ensure this endpoint is disabled outside local dev.");

        String appKey    = safe(props.api().appKey());
        String appSecret = safe(props.api().appSecret());

        Map<String, Object> body = new LinkedHashMap<>();
        // App key: shown in full — it is an identifier, already sent as x-app-key header.
        body.put("appKey", appKey);
        body.put("appKeyLength", appKey.length());

        // App secret: never shown in full.
        body.put("appSecretMasked", mask(appSecret));
        body.put("appSecretLength", appSecret.length());
        body.put("appSecretSha256", sha256(appSecret));

        body.put("regionId", props.api().regionId());
        body.put("endpoint", props.resolvedApiHost());
        body.put("mode", props.trading().mode().name());
        body.put("verifyHint",
                "Compare appSecretSha256 with:  echo -n \"<your_secret>\" | shasum -a 256");
        body.put("timestamp", TimeFormat.nowEt());

        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    /**
     * Masks a secret: shows the first 4 and last 4 characters, hides the middle.
     * Short values (≤ 8 chars) are fully masked to avoid revealing most of the value.
     */
    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "(empty)";
        int len = value.length();
        if (len <= 8) {
            return "*".repeat(len);
        }
        String head = value.substring(0, 4);
        String tail = value.substring(len - 4);
        return head + "*".repeat(Math.min(len - 8, 16)) + tail;
    }

    /** Returns the hex-encoded SHA-256 fingerprint of the value. */
    private static String sha256(String value) {
        if (value == null || value.isEmpty()) return "(empty)";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "(sha256-unavailable)";
        }
    }
}
