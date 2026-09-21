package com.trading.webull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implements the Webull OpenAPI request signature algorithm (v3, HMAC-SHA1),
 * exactly as specified at:
 * <a href="https://developer.webull.com/apis/docs/authentication/signature">Signature docs</a>.
 *
 * <h2>What gets signed</h2>
 * The signature is computed from four parts of the request:
 * <ol>
 *   <li>Request path</li>
 *   <li>Query parameters</li>
 *   <li>Request body (MD5, uppercase)</li>
 *   <li>Signing headers: {@code x-app-key}, {@code x-signature-algorithm},
 *       {@code x-signature-version}, {@code x-signature-nonce},
 *       {@code x-timestamp}, {@code host}</li>
 * </ol>
 * ({@code x-signature} and {@code x-version} do NOT participate in signing.)
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Merge query params + signing headers, sort by name ascending,
 *       join as {@code name1=value1&name2=value2&...} → {@code str1}</li>
 *   <li>If body present: {@code str2 = toUpper(MD5(body))}</li>
 *   <li>{@code str3 = path + "&" + str1 + "&" + str2} (omit {@code str2} + its
 *       separator when body is empty)</li>
 *   <li>{@code encoded = urlEncode(str3)}</li>
 *   <li>{@code key = appSecret + "&"}</li>
 *   <li>{@code signature = base64(HMAC-SHA1(key, encoded))}</li>
 * </ol>
 *
 * <p>This class is verified against the docs' worked example in
 * {@code WebullSignerTest} (expected {@code kvlS6opdZDhEBo5jq40nHYXaLvM=}).</p>
 */
public final class WebullSigner {

    public static final String ALGORITHM = "HMAC-SHA1";
    public static final String SIGNATURE_VERSION = "1.0";
    public static final String API_VERSION = "v3";

    private static final String HMAC_SHA1 = "HmacSHA1";

    private WebullSigner() {
        // utility
    }

    /**
     * Computes the {@code x-signature} value for a request.
     *
     * @param path        request path (e.g. {@code /trading/accounts/list})
     * @param queryParams query parameters (may be empty, never null)
     * @param body        request body string, or {@code null}/empty for no body
     * @param appKey      the app key (x-app-key header value)
     * @param appSecret   the app secret (used to build the signing key; never sent)
     * @param host        the request host (e.g. {@code api.sandbox.webull.com})
     * @param timestamp   ISO-8601 UTC timestamp (x-timestamp header value)
     * @param nonce       unique per-request random string (x-signature-nonce value)
     * @return the base64-encoded HMAC-SHA1 signature
     */
    public static String sign(String path,
                              Map<String, String> queryParams,
                              String body,
                              String appKey,
                              String appSecret,
                              String host,
                              String timestamp,
                              String nonce) {

        // Step 1: merge query params + signing headers into a sorted map
        TreeMap<String, String> all = new TreeMap<>();
        if (queryParams != null) {
            all.putAll(queryParams);
        }
        all.put("x-app-key", appKey);
        all.put("x-signature-algorithm", ALGORITHM);
        all.put("x-signature-version", SIGNATURE_VERSION);
        all.put("x-signature-nonce", nonce);
        all.put("x-timestamp", timestamp);
        all.put("host", host);

        // Join as key=value pairs → str1
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (!first) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        String str1 = sb.toString();

        // Step 1 (cont.): str3 = path + & + str1 [+ & + str2]
        String str3;
        if (body != null && !body.isEmpty()) {
            String str2 = md5UpperHex(body);
            str3 = path + "&" + str1 + "&" + str2;
        } else {
            str3 = path + "&" + str1;
        }

        // URL-encode str3 (encode everything; safe="")
        String encoded = urlEncodeAll(str3);

        // Step 2: key = appSecret + "&"
        String signingKey = appSecret + "&";

        // Step 3: base64(HMAC-SHA1(key, encoded))
        return base64HmacSha1(signingKey, encoded);
    }

    // -----------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------

    static String md5UpperHex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b)); // uppercase hex
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    static String base64HmacSha1(String key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 signing failed", e);
        }
    }

    /**
     * URL-encodes the entire string with nothing left "safe".
     * Java's {@link URLEncoder} encodes spaces as {@code +} and does not encode
     * {@code * - _ .} — we normalise to percent-encoding matching RFC 3986 /
     * the Python {@code urllib.parse.quote(s, safe="")} used in the docs.
     */
    static String urlEncodeAll(String s) {
        String encoded = URLEncoder.encode(s, StandardCharsets.UTF_8);
        return encoded
                .replace("+", "%20")   // space
                .replace("*", "%2A")   // asterisk
                .replace("%7E", "~");  // tilde stays literal (quote leaves ~ unencoded)
    }
}
