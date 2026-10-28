package com.sap.gtlc.helloworld.mcp.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import com.sap.gtlc.helloworld.mcp.tools.ToolAccessDeniedException;

/**
 * HTTP client for the Hello World backend service.
 *
 * Token strategy (resolved per-request via {@link #resolveBackendToken()}):
 *
 *   Case 2 &amp; 3 — Human user with local IAS account (user_uuid present):
 *     The Joule user JWT is exchanged via RFC 8693 (IasUserTokenExchanger)
 *     for a backend-scoped token carrying user_uuid and AMS policy assignments.
 *     The backend's SciAuthorizationsProvider enforces per-user privileges.
 *     If the user has no AMS group assignment (Case 3), the backend falls back
 *     to the MCP server's ias_apis permission level.
 *
 *   Entra guest — Human user authenticated by Entra ID, no local IAS account:
 *     sap_id_type=user, entra_tid, and email are required; user_uuid is absent.
 *     The token is exchanged via RFC 8693. The backend applies its AMS policy
 *     when assigned, otherwise it uses the MCP permission fallback.
 *
 *   Rejected-A — Entra ID authenticated but missing required human-identity claims:
 *     entra_tid is present (Entra ID auth confirmed) but one or more of the
 *     corroborating human-identity claims required by Case 4 is absent or invalid:
 *     sap_id_type≠user or email absent.
 *     Rejected with a diagnostic pointing to missing IAS attribute configuration,
 *     NOT a "wrong IdP" message — the IdP is correct, the claims are incomplete.
 *
 *   Rejected-B — human indicators present but no Entra ID authentication:
 *     Token carries human indicators (sap_id_type=user or email) but has neither
 *     user_uuid (Case 2/3) nor entra_tid (Case 4). Entra ID is mandatory;
 *     rejected with a "not issued via the configured corporate IdP" message.
 *
 *   M2M:
 *     No authenticated user principal, or an explicit sap_id_type=app token with
 *     no human identity claims, uses the cached client_credentials token.
 *     Missing or ambiguous JWT identity claims are rejected.
 */
@Component
public class HelloWorldClient {

    private static final Logger log = LoggerFactory.getLogger(HelloWorldClient.class);

    private final String baseUrl;
    private final IasTokenProvider tokenProvider;
    private final IasUserTokenExchanger userTokenExchanger;
    private final boolean authDiagnosticsEnabled;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HelloWorldClient(
            @Value("${backend.base-url}") String baseUrl,
            IasTokenProvider tokenProvider,
            IasUserTokenExchanger userTokenExchanger,
            @Value("${mcp.auth-diagnostics.enabled:false}") boolean authDiagnosticsEnabled) {
        this.baseUrl = baseUrl;
        this.tokenProvider = tokenProvider;
        this.userTokenExchanger = userTokenExchanger;
        this.authDiagnosticsEnabled = authDiagnosticsEnabled;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.restClient = RestClients.create();
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * HTTP POST – deserializes the response into the given type.
     *
     * @param path         e.g. "/api/v1/greetings"
     * @param body         request object (serialized to JSON by RestClient)
     * @param responseType target class for response deserialization
     */
    public <T> T post(String path, Object body, Class<T> responseType) {
        String uri = baseUrl + path;
        long startNanos = System.nanoTime();
        log.info("Calling backend POST {} with request body: {}", uri, serializeRequestBody(body));
        try {
            T response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + resolveBackendToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
            log.info("Backend POST {} completed successfully in {} ms", uri, elapsedMillis(startNanos));
            return response;
        } catch (RuntimeException ex) {
            logBackendFailure("POST", uri, startNanos, ex);
            throw ex;
        }
    }

    /**
     * HTTP GET – deserializes the response into the given type.
     *
     * @param path         e.g. "/api/v1/greetings/config"
     * @param responseType target class for response deserialization
     */
    public <T> T get(String path, Class<T> responseType) {
        String uri = baseUrl + path;
        long startNanos = System.nanoTime();
        log.info("Calling backend GET {} with no request body", uri);
        try {
            T response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + resolveBackendToken())
                    .retrieve()
                    .body(responseType);
            log.info("Backend GET {} completed successfully in {} ms", uri, elapsedMillis(startNanos));
            return response;
        } catch (RuntimeException ex) {
            logBackendFailure("GET", uri, startNanos, ex);
            throw ex;
        }
    }

    /**
     * HTTP GET – returns raw response string (used for endpoints without a schema,
     * e.g. /actuator/info).
     */
    public String getRaw(String path) {
        String uri = baseUrl + path;
        long startNanos = System.nanoTime();
        log.info("Calling backend GET {} with no request body", uri);
        try {
            String response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + resolveBackendToken())
                    .retrieve()
                    .body(String.class);
            log.info("Backend GET {} completed successfully in {} ms", uri, elapsedMillis(startNanos));
            return response;
        } catch (RuntimeException ex) {
            logBackendFailure("GET", uri, startNanos, ex);
            throw ex;
        }
    }

    // ── token resolution ─────────────────────────────────────────────────────

    /**
     * Resolves the Bearer token to attach to backend requests.
     *
     * Three paths, evaluated in order:
     *
    *   Local IAS user — {@code user_uuid}, {@code sap_id_type=user},
    *     {@code entra_tid}, and email present: exchange via RFC 8693. The
    *     backend enforces the AMS policy or its MCP-permission fallback.
     *
    *   Entra guest — {@code sap_id_type=user}, {@code entra_tid}, and email
    *     present, but no {@code user_uuid}: exchange via RFC 8693. The backend
    *     applies AMS policy resolution and its MCP-permission fallback.
     *
    *   M2M — no authenticated principal or an explicit {@code sap_id_type=app}
    *     token without human identity claims: uses client_credentials. Any
    *     other or incomplete JWT identity is rejected rather than treated as M2M.
     */
    private String resolveBackendToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && authDiagnosticsEnabled) {
            log.info("Incoming MCP bearer token summary: {}",
                    JwtDiagnostics.summarizeClaims(jwtAuth.getToken().getClaims(), jwtAuth.getToken().getTokenValue()));
        }
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Map<String, Object> claims = jwtAuth.getToken().getClaims();
            return switch (classifyIncomingToken(jwtAuth)) {
                case LOCAL_IAS_USER -> {
                    log.info("Token path: OBO/local (Entra-authenticated IAS user) – performing IAS token exchange");
                    yield userTokenExchanger.exchange(jwtAuth.getToken().getTokenValue());
                }
                case ENTRA_GUEST -> {
                    log.info("Token path: OBO/guest (Entra-authenticated, no local IAS account) – performing IAS token exchange");
                    yield userTokenExchanger.exchange(jwtAuth.getToken().getTokenValue());
                }
                case APPLICATION -> {
                    log.info("Token path: M2M – explicit IAS application token");
                    yield tokenProvider.getToken();
                }
                case REJECTED -> {
                    log.warn("Auth rejected: unsupported or incomplete token identity " +
                                    "(sap_id_type={}, user_uuid={}, entra_tid={}, email={}, preferred_username={})",
                            claims.get("sap_id_type"), hasTextClaim(claims, "user_uuid"),
                            hasTextClaim(claims, "entra_tid"), hasTextClaim(claims, "email"),
                            hasTextClaim(claims, "preferred_username"));
                    throw new ToolAccessDeniedException(
                            "You don't have permission to use this service. " +
                            "Entra ID authentication and a complete user identity are required.");
                }
            };
        }
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            log.warn("Auth rejected: unsupported authenticated principal type {}", auth.getClass().getName());
            throw new ToolAccessDeniedException("You don't have permission to use this service.");
        }
        log.info("Token path: M2M – no authenticated human principal; using client_credentials token");
        return tokenProvider.getToken();
    }

    enum IncomingTokenType { LOCAL_IAS_USER, ENTRA_GUEST, APPLICATION, REJECTED }

    static IncomingTokenType classifyIncomingToken(JwtAuthenticationToken jwtAuth) {
        Map<String, Object> claims = jwtAuth.getToken().getClaims();
        boolean hasUserUuid = hasTextClaim(claims, "user_uuid");
        boolean hasEntraTid = hasTextClaim(claims, "entra_tid");
        boolean hasEmail = hasTextClaim(claims, "email");
    
        Object sapIdType = claims.get("sap_id_type");
        boolean isUser = sapIdType instanceof String type && "user".equalsIgnoreCase(type);
        boolean isApplication = sapIdType instanceof String type && "app".equalsIgnoreCase(type);

        if (isUser && hasEntraTid && hasEmail) {
            return hasUserUuid ? IncomingTokenType.LOCAL_IAS_USER : IncomingTokenType.ENTRA_GUEST;
        }

        if (isApplication && !hasUserUuid && !hasEntraTid && !hasEmail) {
            return IncomingTokenType.APPLICATION;
        }

        return IncomingTokenType.REJECTED;
    }

    private static boolean hasTextClaim(Map<String, Object> claims, String claimName) {
        Object value = claims.get(claimName);
        return value instanceof String stringValue && StringUtils.hasText(stringValue);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Logs a backend call failure, distinguishing expected authorization
     * outcomes from genuine faults.
     *
     * A 401 Unauthorized or 403 Forbidden is an expected, caller-handled
     * outcome on an authz-gated endpoint: logged at WARN with the status and
     * backend response body, but without a stacktrace. Everything else — other
     * 4xx (400/404/409, which usually indicate a client bug), 5xx, timeouts,
     * connection resets, deserialization failures — is logged at ERROR with the
     * full stacktrace for diagnosis.
     */
    private static void logBackendFailure(String method, String uri, long startNanos, RuntimeException ex) {
        long elapsed = elapsedMillis(startNanos);
        if (ex instanceof HttpClientErrorException clientError && isExpectedAuthStatus(clientError)) {
            log.warn("Backend {} {} returned {} after {} ms: {}",
                    method, uri, clientError.getStatusCode(), elapsed,
                    clientError.getResponseBodyAsString());
            return;
        }
        log.error("Backend {} {} failed after {} ms", method, uri, elapsed, ex);
    }

    private static boolean isExpectedAuthStatus(HttpClientErrorException ex) {
        HttpStatusCode status = ex.getStatusCode();
        return status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                || status.isSameCodeAs(HttpStatus.FORBIDDEN);
    }

    private String serializeRequestBody(Object body) {
        if (body == null) {
            return "<null>";
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize backend request body for logging; falling back to toString()", ex);
            return String.valueOf(body);
        }
    }
}
