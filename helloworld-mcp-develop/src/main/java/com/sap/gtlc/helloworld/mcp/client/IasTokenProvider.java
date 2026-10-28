package com.sap.gtlc.helloworld.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.time.Instant;
import java.util.Map;

/**
 * Fetches and caches an IAS client_credentials access token for M2M calls
 * to the Hello World backend service.
 *
 * The token is refreshed automatically 60 seconds before it expires.
 * client_id and client_secret are injected from Kubernetes Secrets via env vars.
 */
@Component
public class IasTokenProvider {

    private static final String RESOURCE = "urn:sap:identity:application:provider:name:scope";

    private static final Logger log = LoggerFactory.getLogger(IasTokenProvider.class);

    private final String tokenUrl;

    private final String clientId;

    private final String clientSecret;

    private final RestClient restClient;

    @Autowired
    public IasTokenProvider(
            @Value("${ias.token-url}") String tokenUrl,
            @Value("${ias.client-id}") String clientId,
            @Value("${ias.client-secret}") String clientSecret) {
        this(tokenUrl, clientId, clientSecret, RestClients.create());
    }

    @SuppressWarnings("unused")
    IasTokenProvider(String tokenUrl, String clientId, String clientSecret, RestClient restClient) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = restClient;
    }

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    @SuppressWarnings("unchecked")
    public synchronized String getToken() {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            log.warn("Backend M2M credentials are missing; MCP backend calls cannot be executed");
            throw new IllegalStateException(
                    "IAS M2M credentials are not configured. Set IAS_CLIENT_ID and IAS_CLIENT_SECRET "
                            + "to enable MCP calls to hello-world-service.");
        }

        if (cachedToken == null || Instant.now().isAfter(tokenExpiry.minusSeconds(60))) {
            long startNanos = System.nanoTime();
            log.info("Refreshing backend M2M token from {}", tokenUrl);

            String requestBody = "grant_type=client_credentials&resource=" + RESOURCE;

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
                log.error("IAS token retrieval failed for client {} after {} ms",
                        clientId, elapsedMillis(startNanos), ex);
                throw ex;
            }

            if (response == null || !response.containsKey("access_token")) {
                throw new IllegalStateException("IAS token endpoint returned no access_token for backend M2M flow");
            }

            Object tokenValue = response.get("access_token");
            if (!(tokenValue instanceof String token) || !StringUtils.hasText(token)) {
                throw new IllegalStateException(
                        "IAS token endpoint returned a null, blank, or non-string access_token for backend M2M flow "
                        + "(actual type: " + (tokenValue == null ? "null" : tokenValue.getClass().getName()) + ")");
            }
            cachedToken = token;
            Number expiresInValue = (Number) response.getOrDefault("expires_in", 3600);
            int expiresIn = expiresInValue.intValue();
            tokenExpiry = Instant.now().plusSeconds(expiresIn);

            log.info("Backend M2M token refreshed successfully – expires in {}s, fingerprint={}",
                    expiresIn, tokenFingerprint(cachedToken));
            log.info("IAS token refresh completed in {} ms", elapsedMillis(startNanos));
        }
        return cachedToken;
    }


    private String basicAuthHeader() {
        String credentials = clientId + ":" + clientSecret;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String tokenFingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception ex) {
            return "unavailable";
        }
    }
}
