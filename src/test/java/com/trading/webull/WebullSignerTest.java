package com.trading.webull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link WebullSigner} against the official worked example from
 * <a href="https://developer.webull.com/apis/docs/authentication/signature">the Signature docs</a>.
 *
 * If {@link #reproducesDocsVector()} passes, the signing implementation matches
 * Webull's reference exactly.
 */
class WebullSignerTest {

    @Test
    @DisplayName("Reproduces the docs' worked-example signature: kvlS6opdZDhEBo5jq40nHYXaLvM=")
    void reproducesDocsVector() {
        // ── Inputs from the docs worked example ──────────────────────────
        String path = "/trade/place_order";

        // Query parameters a1/a2/a3/q1 (order irrelevant — signer sorts)
        Map<String, String> query = new LinkedHashMap<>();
        query.put("a1", "webull");
        query.put("a2", "123");
        query.put("a3", "xxx");
        query.put("q1", "yyy");

        // Body (compact, exactly as documented)
        String body = "{\"k1\":123,\"k2\":\"this is the api request body\",\"k3\":true,\"k4\":{\"foo\":[1,2]}}";

        String appKey    = "776da210ab4a452795d74e726ebd74b6";
        String appSecret = "0f50a2e853334a9aae1a783bee120c1f";
        String host      = "api.webull.com";
        String timestamp = "2022-01-04T03:55:31Z";
        String nonce     = "48ef5afed43d4d91ae514aaeafbc29ba";

        String signature = WebullSigner.sign(
                path, query, body, appKey, appSecret, host, timestamp, nonce);

        assertThat(signature).isEqualTo("kvlS6opdZDhEBo5jq40nHYXaLvM=");
    }

    @Test
    @DisplayName("Body MD5 matches the docs' value E296C96787E1A309691CEF3692F5EEDD")
    void bodyMd5MatchesDocs() {
        String body = "{\"k1\":123,\"k2\":\"this is the api request body\",\"k3\":true,\"k4\":{\"foo\":[1,2]}}";
        assertThat(WebullSigner.md5UpperHex(body))
                .isEqualTo("E296C96787E1A309691CEF3692F5EEDD");
    }

    @Test
    @DisplayName("Empty-body request signs without throwing and is stable")
    void emptyBodySignsConsistently() {
        Map<String, String> query = new LinkedHashMap<>();
        String sig1 = WebullSigner.sign(
                "/trading/accounts/list", query, null,
                "appkey", "secret", "api.sandbox.webull.com",
                "2024-01-15T14:30:00Z", "nonce123");
        String sig2 = WebullSigner.sign(
                "/trading/accounts/list", query, "",
                "appkey", "secret", "api.sandbox.webull.com",
                "2024-01-15T14:30:00Z", "nonce123");

        // null body and empty body must produce identical signatures
        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).isNotBlank();
    }
}
