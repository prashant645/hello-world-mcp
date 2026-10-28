package com.sap.gtlc.helloworld.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Spring Security configuration.
 *
 * Incoming requests must carry a valid IAS JWT issued by the IAS tenant at
 * a3enmtmdf.accounts400.ondemand.com.
 *
 * Two validations are applied:
 *   1. Issuer  – token must come from the configured IAS tenant.
 *   2. Audience – token's aud claim must contain at least one of the client IDs
 *                 listed in IAS_MCP_UI_CLIENT_ID (comma-separated).
 *                 This prevents tokens issued for unrelated apps in the same
 *                 tenant from being accepted.
 *                 Supports multiple clients, e.g. Joule Desktop and Claude Code:
 *                   IAS_MCP_UI_CLIENT_ID=<joule-client-id>,<claude-client-id>
 *                 Leave blank to skip audience validation (local testing only).
 *
 * CORS (Streamable HTTP):
 *   MCP spec 2025-11-25 (Streamable HTTP) requires CORS to be configured so
 *   that Joule Desktop's renderer process can reach the /mcp endpoint from
 *   a cross-origin context (e.g. app://joule-work-desktop or null origin).
 *   Without this, all POST /mcp requests are blocked before authentication.
 *   The Mcp-Session-Id response header must also be exposed so the client
 *   can read and reuse the session ID across requests.
 *
 * Authorization — AMS (Authorization Management Service) enforcement is at the
 *   backend layer (hello-world-service), not in this MCP server.
 *   The MCP server's role in the authorization flow is:
 *   - M2M path: acquire a client_credentials token that carries this server's
 *     ias_apis permission group (e.g. "writers") and forward it to the backend.
 *     The backend @PreAuthorize gate checks ias_apis and AMS is not consulted.
 *   - OBO path (human user, user_uuid present): exchange the incoming Joule user JWT
 *     via RFC 8693 token exchange. The backend receives a token carrying the user's
 *     identity and then applies a two-tier resolution:
 *     (1) user has an AMS policy binding → that policy is the sole authorizer;
 *         ias_apis from this MCP server is ignored for that user.
 *     (2) user has no AMS policy → this MCP server's ias_apis is inherited as the
 *         effective permission. If ias_apis is absent, the request is denied.
 *   See: HelloWorldClient, IasUserTokenExchanger (MCP), GreetingController.resolveOboAuthorization (backend)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwksUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /**
     * Comma-separated list of IAS OAuth2 client IDs allowed to call this MCP server.
     * Each entry is matched against the token's aud claim.
     * Typical value: {@code <joule-client-id>,<claude-client-id>}
     * Injected via IAS_MCP_UI_CLIENT_ID env var (set in Helm values, not the K8s secret).
     * If blank, audience validation is skipped (local testing only).
     */
    @Value("${ias.mcp-ui-client-id:}")
    private String mcpUiClientIds;

    @Value("${mcp.public-base-url}")
    private String publicBaseUrl;

    @Value("${mcp.auth-diagnostics.enabled:false}")
    private boolean authDiagnosticsEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // CORS must be enabled before authentication so that cross-origin
            // preflight (OPTIONS) requests from Joule Desktop are permitted
            // without a Bearer token.  Without this, all Streamable HTTP POST
            // /mcp requests from the renderer are blocked before auth runs.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Kyma liveness / readiness probes – no token required
                .requestMatchers(
                    "/actuator/health",
                    "/authorize",
                    "/token",
                    "/.well-known/**"
                ).permitAll()
                // All MCP endpoints require a valid IAS JWT
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(iasJwtAuthenticationConverter()))
                .authenticationEntryPoint((request, response, exception) -> {
                    String metadataUrl = publicBaseUrl.replaceAll("/+$", "")
                        + "/.well-known/oauth-protected-resource";
                    response.addHeader("WWW-Authenticate",
                        "Bearer resource_metadata=\"" + metadataUrl + "\"");
                    response.sendError(401);
                }));

        // Spring Boot 4 / Spring Security 7 auto-configures OAuth2ProtectedResourceMetadataFilter
        // (RFC 9728) which intercepts GET /.well-known/oauth-protected-resource at the filter
        // level — before DispatcherServlet — so OAuthBridgeController never handles it.
        // The auto-generated response omits authorization_servers entirely, so Joule cannot
        // discover IAS and the PKCE flow never starts.
        // Register our filter immediately after LogoutFilter (which precedes
        // OAuth2ProtectedResourceMetadataFilter in the chain) so it responds with the correct
        // metadata before Spring Security's filter can intercept the request.
        // Note: addFilterBefore(OAuth2ProtectedResourceMetadataFilter.class) fails at startup
        // because that class is not in Spring Security's FilterOrderRegistrar.
        http.addFilterAfter(protectedResourceMetadataFilter(), LogoutFilter.class);

        return http.build();
    }

    /**
     * Serves GET /.well-known/oauth-protected-resource with the correct metadata
     * so that Joule Desktop can discover IAS and start the PKCE authorization flow.
     *
     * Runs before Spring Security 7's OAuth2ProtectedResourceMetadataFilter, which
     * auto-generates an incomplete response (missing authorization_servers).
     */
    private OncePerRequestFilter protectedResourceMetadataFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if ("GET".equalsIgnoreCase(request.getMethod())
                        && "/.well-known/oauth-protected-resource".equals(request.getRequestURI())) {
                    String baseUrl = publicBaseUrl.replaceAll("/+$", "");
                    String body = String.format(
                        "{\"resource\":\"%s/mcp\"," +
                        "\"authorization_servers\":[\"%s\"]," +
                        "\"bearer_methods_supported\":[\"header\"]," +
                        "\"scopes_supported\":[\"openid\",\"offline_access\"]}",
                        baseUrl, issuerUri);
                    response.setContentType("application/json");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(body);
                    return;  // response committed – downstream filters and Servlet skipped
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    /**
     * CORS configuration for the Streamable HTTP MCP endpoint.
     *
     * Joule Desktop's renderer process connects to /mcp from a cross-origin
     * context (e.g. app://joule-work-desktop or null origin).  We allow all
     * origins — auth is enforced by the Bearer token, not by origin.
     * Mcp-Session-Id must be exposed so the client can read and reuse the
     * session ID across requests (MCP spec 2025-11-25 §3.2).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        // MCP Streamable HTTP uses GET (SSE stream), POST (messages), DELETE (session end).
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Expose MCP-specific response headers so the renderer can read them.
        config.setExposedHeaders(List.of("Mcp-Session-Id", "WWW-Authenticate"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Custom JWT authentication converter that logs key claims at the point of
     * token validation — immediately after the signature/expiry/audience checks pass.
     *
     * <p>This is the earliest observable point in the request lifecycle for a decoded
     * JWT. Logging here confirms exactly what IAS sent to the MCP server, before
     * {@code HelloWorldClient} makes the routing decision.</p>
     *
     * <p>When {@code user_uuid} is absent the converter applies the same three-branch
     * classification used by {@code HelloWorldClient} to avoid false alarms:</p>
     * <ul>
     *   <li>M2M ({@code sap_id_type=app}) — no warning, expected path.</li>
     *   <li>Entra ID guest ({@code entra_tid} present) — INFO; Case 4 OBO path
     *       will be taken downstream.</li>
     *   <li>Human with no {@code entra_tid} — WARN; request will be rejected by
     *       {@code HelloWorldClient} before reaching the backend.</li>
     * </ul>
     *
     * <p>DEBUG level logs all key claims. Enable via:
     * {@code logging.level.com.sap.gtlc.helloworld.mcp=DEBUG} in application.yaml.</p>
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> iasJwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        return jwt -> {
            // Gate all diagnostics behind the flag — no logging overhead on every
            // authenticated request unless explicitly enabled.
            if (!authDiagnosticsEnabled) {
                return delegate.convert(jwt);
            }

            Map<String, Object> claims = jwt.getClaims();
            String userUuid = stringClaim(claims, "user_uuid");
            String sapIdType = stringClaim(claims, "sap_id_type");
            String sub = stringClaim(claims, "sub");
            String email = stringClaim(claims, "email");
            String azp = stringClaim(claims, "azp");
            String entraTid = stringClaim(claims, "entra_tid");
        
            boolean hasUserUuid = StringUtils.hasText(userUuid);
            boolean hasEntraTid = StringUtils.hasText(entraTid);
            boolean hasEmail = StringUtils.hasText(email);
    
            boolean isUser = "user".equalsIgnoreCase(sapIdType);
            boolean isApplication = "app".equalsIgnoreCase(sapIdType);
            boolean isCompleteEntraHuman = isUser && hasEntraTid && hasEmail;
            boolean isApplicationToken = isApplication && !hasUserUuid && !hasEntraTid
                    && !hasEmail;

            log.debug("[AUTH] Incoming MCP JWT: sub={}, sap_id_type={}, user_uuid={}, " +
                      "entra_tid={}, email={}, azp={}, aud={}",
                      sub, sapIdType,
                      hasUserUuid ? "[present]" : "null",
                      hasEntraTid ? "[present]" : "null",
                      hasEmail ? "[present]" : "null",
                      azp, jwt.getAudience());

            if (hasUserUuid && isCompleteEntraHuman) {
                // Case 2 & 3: local IAS user — AMS-eligible OBO path
                log.info("[AUTH] user_uuid present — OBO/local path will be taken. sub={}, azp={}", sub, azp);
                return delegate.convert(jwt);
            }

            // No user_uuid — classify by remaining signals
            if (isApplicationToken) {
                // Case 1: M2M — expected, no warning
                return delegate.convert(jwt);
            }

            if (isCompleteEntraHuman && !hasUserUuid) {
                // Case 4: Entra ID guest — OBO path will be taken downstream
                log.info("[AUTH] No user_uuid but entra_tid present — OBO/guest (Entra ID) path will be taken. " +
                         "sub={}, azp={}", sub, azp);
            } else {
                // Rejected downstream: identity claims are incomplete or inconsistent.
                // HelloWorldClient will throw ToolAccessDeniedException before the backend is called.
                log.warn("[AUTH] Incomplete or inconsistent identity claims — request will be REJECTED. " +
                         "sap_id_type={}, user_uuid={}, entra_tid={}, email={}. " +
                         "Check: (1) entra_tid Self-defined Attribute configured in IAS " +
                         "(Applications → [app] → Trust → [Entra ID IdP] → Attributes, " +
                         "Name=entra_tid, Source=Corporate Identity Provider, Value=tid), " +
                         "(2) or user provisioned in IAS Identity Directory for user_uuid. " +
                         "sub={}, azp={}, aud={}",
                         sapIdType, hasUserUuid ? "[present]" : "null",
                         hasEntraTid ? "[present]" : "null",
                         hasEmail ? "[present]" : "null",
                 
                         sub, azp, jwt.getAudience());
            }
            return delegate.convert(jwt);
        };
    }

    private static String stringClaim(Map<String, Object> claims, String claimName) {
        Object value = claims.get(claimName);
        return value instanceof String stringValue ? stringValue : null;
    }

    /**
     * Custom JwtDecoder that enforces issuer + optional audience validation.
     * Spring Boot backs off its auto-configured decoder when this bean is present.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();

        // 1. Default validators: signature, expiry, issuer
        validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));

        // 2. Audience validator – accept tokens whose aud claim contains any allowed client ID
        List<String> allowedIds = Arrays.stream(mcpUiClientIds.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (StringUtils.hasText(mcpUiClientIds) && allowedIds.isEmpty()) {
            throw new IllegalStateException(
                "mcp.ui.client-ids is set but contains no valid entries. " +
                "Fix the configuration or remove the property to disable audience validation.");
        }
        if (!allowedIds.isEmpty()) {
            log.info("Audience validation enabled – allowed MCP UI client IDs: {}", allowedIds);
            validators.add(token -> {
                if (token.getAudience().stream().anyMatch(allowedIds::contains)) {
                    return OAuth2TokenValidatorResult.success();
                }
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token audience does not include any allowed MCP UI client. " +
                    "Allowed: " + allowedIds + ", got: " + token.getAudience(),
                    null));
            });
        } else {
            // IAS_MCP_UI_CLIENT_ID is blank – audience validation is DISABLED.
            // Without it, any token issued by this IAS tenant (for any application)
            // is accepted, not just tokens issued to the MCP UI clients.
            // This is safe on a developer laptop where the tenant is not shared,
            // but must never reach production.  Set IAS_MCP_UI_CLIENT_ID in your
            // Helm values (or equivalent) before deploying.
            log.warn("SECURITY WARNING: Audience validation is disabled because "
                    + "IAS_MCP_UI_CLIENT_ID is not set. "
                    + "Any token issued by this IAS tenant will be accepted. "
                    + "This configuration must not be used in production.");
        }

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }
}
