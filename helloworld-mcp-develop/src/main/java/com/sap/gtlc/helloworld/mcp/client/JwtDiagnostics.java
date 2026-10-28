package com.sap.gtlc.helloworld.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/**
 * Lightweight JWT claim summariser for auth-flow diagnostics.
 *
 * <p>Used only for logging selected claims needed to debug IAS ↔ corporate IdP
 * user linking. Never logs the raw token.</p>
 */
final class JwtDiagnostics {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    // Non-personal routing and classification claims only.
    // Personal identifiers (sub, email, preferred_username, user_uuid) and
    // authorization data (groups, ias_apis) are intentionally excluded from the
    // verbatim output; their PRESENCE is captured separately via has_user_uuid
    // and human_indicators below.
    //
    // Entra ID / corporate IdP detection claims:
    //   entra_tid  – IAS Self-defined Attribute forwarding the Entra ID OIDC tid claim.
    //               Present in all tokens issued for users who authenticated via Entra ID.
    //               (tid itself is never forwarded directly; it is mapped to entra_tid in IAS.)
    //
    // Omitted intentionally:
    //   tid              – raw Entra ID claim; IAS maps it to entra_tid, never forwards it as tid
    //   corporate_idp_type – static Option-1 enrichment; not implemented
    private static final List<String> CLAIM_ORDER = List.of(
            "sap_id_type",
            "azp",
            "client_id",
            "origin",
            "app_tid",
            "entra_tid"
    );

    private JwtDiagnostics() {
    }

    static String summarizeToken(String token) {
        Map<String, Object> claims = extractClaims(token);
        return summarizeClaims(claims, token);
    }

    static String summarizeClaims(Map<String, Object> claims, String tokenValue) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fingerprint", fingerprint(tokenValue));
        for (String claimName : CLAIM_ORDER) {
            Object value = claims.get(claimName);
            if (value != null) {          // omit absent claims — keeps the log line compact
                summary.put(claimName, value);
            }
        }
        summary.put("has_user_uuid", hasText(claims.get("user_uuid")));
        summary.put("human_indicators", detectHumanIndicators(claims));
        return summary.toString();
    }

    static boolean hasUserUuid(String token) {
        return hasText(extractClaims(token).get("user_uuid"));
    }

    private static List<String> detectHumanIndicators(Map<String, Object> claims) {
        java.util.ArrayList<String> indicators = new java.util.ArrayList<>();
        if (hasText(claims.get("email"))) {
            indicators.add("email");
        }
        if (hasText(claims.get("preferred_username"))) {
            indicators.add("preferred_username");
        }
        if (hasText(claims.get("user_uuid"))) {
            indicators.add("user_uuid");
        }
        Object sapIdType = claims.get("sap_id_type");
        if (sapIdType != null && !"app".equals(String.valueOf(sapIdType))) {
            indicators.add("sap_id_type=" + sapIdType);
        }
        return indicators;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractClaims(String token) {
        if (!StringUtils.hasText(token)) {
            return Map.of();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Map.of();
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = OBJECT_MAPPER.readValue(decoded, MAP_TYPE);
            return claims != null ? claims : Map.of();
        } catch (Exception ex) {
            return Map.of("decode_error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static boolean hasText(Object value) {
        return value instanceof String stringValue && StringUtils.hasText(stringValue);
    }

    private static String fingerprint(String token) {
        if (!StringUtils.hasText(token)) {
            return "missing";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception ex) {
            return "unavailable";
        }
    }
}

