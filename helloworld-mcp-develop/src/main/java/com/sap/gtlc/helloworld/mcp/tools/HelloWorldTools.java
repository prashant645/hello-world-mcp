package com.sap.gtlc.helloworld.mcp.tools;

import com.sap.gtlc.helloworld.mcp.client.HelloWorldClient;
import com.sap.gtlc.helloworld.mcp.model.ConfigUpdateRequest;
import com.sap.gtlc.helloworld.mcp.model.ConfigUpdateResponse;
import com.sap.gtlc.helloworld.mcp.model.GreetingRequest;
import com.sap.gtlc.helloworld.mcp.model.GreetingResponse;
import com.sap.gtlc.helloworld.mcp.model.GreetingServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * MCP tools wrapping the Hello World service REST API.
 *
 * Each method maps to one endpoint. Spring AI reads the method signature,
 * parameter types, and @JsonPropertyDescription annotations on the record fields
 * to generate a precise JSON schema for each tool — giving Joule Desktop full
 * knowledge of what data to supply.
 *
 * AMS permission requirements (enforced by hello-world-service):
 *   createGreeting        – readers | writers | admins  (read privilege)
 *   updateConfiguration   – writers | admins            (write privilege)
 *   getGreetingConfig     – readers | writers | admins  (read privilege)
 *   getActuatorInfo       – no AMS restriction (Spring Boot actuator)
 */
@Service
public class HelloWorldTools {

    private static final Logger log = LoggerFactory.getLogger(HelloWorldTools.class);

    private final HelloWorldClient client;

    public HelloWorldTools(HelloWorldClient client) {
        this.client = client;
    }

    // ── Identity helper ───────────────────────────────────────────────────────

    /**
     * Logs the IAS caller identity at DEBUG level for diagnostics.
     * Only token metadata (iss, aud, exp) is logged.
     */
    private void logUserIdentity(String toolName) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                var jwt = jwtAuth.getToken();
                log.debug("IAS caller [tool={}, iss={}, aud={}, exp={}]",
                        toolName, jwt.getIssuer(), jwt.getAudience(), jwt.getExpiresAt());
            } else {
                log.warn("=== IAS User Identity [tool={}] — no JWT in SecurityContext (auth={}) ===",
                        toolName, auth);
            }
        } catch (Exception e) {
            log.warn("=== IAS User Identity [tool={}] — could not extract identity ===", toolName, e);
        }
    }

    // ── Error translation ───────────────────────────────────────────────────

    /**
     * Translates a failure from a backend call into the exception a tool should
     * throw.
     *
     * For 401 Unauthorized / 403 Forbidden — the expected outcome when the
    * caller lacks the required AMS privilege — this logs the attempted action,
    * required privilege, and status, then returns a {@link ToolAccessDeniedException}
    * with a generic caller-facing message. Spring AI rethrows it and the MCP
    * server reports the result with {@code isError=true}; backend URLs, SAP
    * problem details, and required privilege details are not exposed to the model.
     *
     * Everything else (other 4xx, 5xx, timeouts, deserialization failures) is
     * logged at ERROR with the full stacktrace and rethrown unchanged, so
     * genuine faults stay diagnosable in the server logs.
     *
     * @param ex      the exception thrown by the backend call
    * @param action what the tool attempted, for server-side diagnostics
    * @param privilege required AMS permissions, for server-side diagnostics only
     * @return the exception to throw (never returns normally)
     */
    private RuntimeException translateAccessDenied(RuntimeException ex, String action, String privilege) {
        if (ex instanceof HttpClientErrorException clientError && isAuthStatus(clientError)) {
            log.warn("Tool access denied: action={}, requiredPrivilege={}, status={}",
                    action, privilege != null ? privilege : "none", clientError.getStatusCode());
            return new ToolAccessDeniedException("Permission denied.");
        }
        log.error("Tool call failed while trying to {}", action, ex);
        return ex;
    }

    private static boolean isAuthStatus(HttpClientErrorException ex) {
        HttpStatusCode status = ex.getStatusCode();
        return status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                || status.isSameCodeAs(HttpStatus.FORBIDDEN);
    }

    // ── 1. createGreeting ─────────────────────────────────────────────────────

    @Tool(description = """
            Create a greeting response for a user message.
            The message must be plain text, max 10 words and 50 characters.
            Returns a formatted greeting string.
            """)
    public GreetingResponse createGreeting(GreetingRequest request) {
        logUserIdentity("createGreeting");
        log.info("Tool createGreeting invoked");
        try {
            GreetingResponse response = client.post("/api/v1/greetings", request, GreetingResponse.class);
            log.info("Tool createGreeting completed successfully");
            return response;
        } catch (RuntimeException ex) {
            throw translateAccessDenied(ex, "create a greeting", "readers, writers, or admins");
        }
    }

    // ── 2. updateConfiguration ────────────────────────────────────────────────

    @Tool(description = """
            Update the Hello World service runtime configuration.
            Allows changing rate limiting (requests per minute, enabled/disabled)
            and message validation rules (max words, max characters).
            All fields are optional — supply only what you want to change.
            Changes take effect immediately.
            """)
    public ConfigUpdateResponse updateConfiguration(ConfigUpdateRequest request) {
        logUserIdentity("updateConfiguration");
        log.info("Tool updateConfiguration invoked");
        try {
            ConfigUpdateResponse response = client.post("/api/v1/greetings/update", request, ConfigUpdateResponse.class);
            log.info("Tool updateConfiguration completed successfully");
            return response;
        } catch (RuntimeException ex) {
            throw translateAccessDenied(ex, "update the configuration", "writers or admins");
        }
    }

    // ── 3. getGreetingConfig ──────────────────────────────────────────────────

    @Tool(description = """
            Get the current runtime configuration of the Hello World service.
            Returns the active rate limit settings and message validation rules.
            """)
    public GreetingServiceConfig getGreetingConfig() {
        logUserIdentity("getGreetingConfig");
        log.info("Tool getGreetingConfig invoked");
        try {
            GreetingServiceConfig response = client.get("/api/v1/greetings/config", GreetingServiceConfig.class);
            log.info("Tool getGreetingConfig completed successfully");
            return response;
        } catch (RuntimeException ex) {
            throw translateAccessDenied(ex, "read the configuration", "readers, writers, or admins");
        }
    }

    // ── 4. getActuatorInfo ────────────────────────────────────────────────────

    @Tool(description = """
            Get Spring Boot application metadata from the actuator info endpoint.
            Returns build version, git commit hash, and other deployment information.
            """)
    public String getActuatorInfo() {
        logUserIdentity("getActuatorInfo");
        log.info("Tool getActuatorInfo invoked");
        try {
            String response = client.getRaw("/actuator/info");
            log.info("Tool getActuatorInfo completed successfully");
            return response;
        } catch (RuntimeException ex) {
            throw translateAccessDenied(ex, "read the application info", "none");
        }
    }
}
