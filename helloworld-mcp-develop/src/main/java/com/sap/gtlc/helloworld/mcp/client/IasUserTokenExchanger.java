package com.sap.gtlc.helloworld.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Performs RFC 8693 OAuth 2.0 Token Exchange on behalf of a human user.
 *
 * The MCP server uses its own IAS client credentials (same IAS_CLIENT_ID /
 * IAS_CLIENT_SECRET used for M2M) to exchange the incoming Joule user JWT
 * for a new token scoped to the hello-world-service backend (ias-dev).
 *
 * The resulting token carries:
 *   - The user's identity  (sub, email, user_uuid, etc.)
 *   - ias_apis=["writers"] — IAS propagates the MCP server's own permission level
 *     (the value declared on the 'scope' dependency, currently "writers") into the
 *     exchanged token, NOT a "principal-propagation" sentinel. This is identical to
 *     what the M2M client_credentials token carries. The backend distinguishes M2M
 *     from OBO tokens solely via the presence of the user_uuid claim.
 *   - The backend SciAuthorizationsProvider.checkPrivilege() evaluates the user's
 *     personal AMS policy assignment. If the user is unenrolled (no policy), the
 *     backend falls back to the ias_apis value above as the effective MCP permission.
 *
 * Caching: tokens are per-user and valid for ~60 min.  Per-user caching
 * (keyed by user_uuid) can be added later if exchange latency is a concern.
 * For now we exchange on every tool invocation for simplicity and correctness.
 *
 * POST https://a3enmtmdf.accounts400.ondemand.com/oauth2/token
 *   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
 *   subject_token=<Joule user JWT>
 *   subject_token_type=urn:ietf:params:oauth:token-type:jwt
 *   resource=urn:sap:identity:application:provider:name:scope
 *   Authorization: Basic <MCP client_id:client_secret>
 */
@Component
public class IasUserTokenExchanger {

    private static final Logger log = LoggerFactory.getLogger(IasUserTokenExchanger.class);

    private static final String GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String SUBJECT_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:jwt";

    /**
     * IAS dependency name declared on the MCP server application in IAS.
     * Must match the value used in IasTokenProvider for the M2M path.
     */
    private static final String RESOURCE =
            "urn:sap:identity:application:provider:name:scope";

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final boolean authDiagnosticsEnabled;
    private final RestClient restClient;

    @Autowired
    public IasUserTokenExchanger(
            @Value("${ias.token-url}") String tokenUrl,
            @Value("${ias.client-id}") String clientId,
            @Value("${ias.client-secret}") String clientSecret,
            @Value("${mcp.auth-diagnostics.enabled:false}") boolean authDiagnosticsEnabled) {
        this(tokenUrl, clientId, clientSecret, authDiagnosticsEnabled, RestClients.create());
    }

    @SuppressWarnings("unused")
    IasUserTokenExchanger(String tokenUrl, String clientId, String clientSecret,
                          boolean authDiagnosticsEnabled,
                          RestClient restClient) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authDiagnosticsEnabled = authDiagnosticsEnabled;
        this.restClient = restClient;
    }

    /**
     * Exchanges {@code userJwt} (issued by IAS for Joule Desktop) for a new
     * IAS token scoped to the hello-world-service backend.
     *
     * @param userJwt the raw JWT string from the user's SecurityContext
     * @return access_token to use as Bearer token against the backend
     * @throws RuntimeException if IAS rejects the exchange or returns no token
     */
    @SuppressWarnings("unchecked") // Map.class raw type for JSON response deserialization
    public String exchange(String userJwt) {
        long startNanos = System.nanoTime();
        log.info("IAS token exchange → subject_token fingerprint={}", fingerprint(userJwt));
        if (authDiagnosticsEnabled) {
            log.info("IAS token exchange subject token summary: {}", JwtDiagnostics.summarizeToken(userJwt));
        }

        String requestBody =
                "grant_type="          + encode(GRANT_TYPE)
                + "&subject_token="    + encode(userJwt)
                + "&subject_token_type=" + encode(SUBJECT_TOKEN_TYPE)
                + "&resource="         + encode(RESOURCE);

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Authorization", basicAuthHeader())
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException ex) {
            log.error("IAS token exchange failed after {} ms", elapsedMillis(startNanos), ex);
            throw ex;
        }

        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException(
                    "IAS token exchange returned no access_token – check that "
                    + "Token Exchange (RFC 8693) grant type is enabled on the MCP "
                    + "server IAS application and that the backend IAS application "
                    + "is registered as a dependency with the 'scope' dependency name. "
                    + "Note: the user does not need an AMS policy assignment — "
                    + "unenrolled users inherit the MCP server's ias_apis permission.");
        }

        Object tokenValue = response.get("access_token");
        if (!(tokenValue instanceof String exchangedToken) || !StringUtils.hasText(exchangedToken)) {
            throw new IllegalStateException(
                    "IAS token exchange returned a null, blank, or non-string access_token "
                    + "(actual type: " + (tokenValue == null ? "null" : tokenValue.getClass().getName()) + ")");
        }
        if (authDiagnosticsEnabled) {
            log.info("IAS token exchange response token summary: {}", JwtDiagnostics.summarizeToken(exchangedToken));
            if (!JwtDiagnostics.hasUserUuid(exchangedToken)) {
                log.warn("IAS token exchange returned a token without user_uuid; backend will classify it as M2M/app token");
            }
        }
        log.info("IAS token exchange succeeded in {} ms – exchanged token fingerprint={}",
                elapsedMillis(startNanos), fingerprint(exchangedToken));
        return exchangedToken;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String basicAuthHeader() {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception ex) {
            return "unavailable";
        }
    }
}
