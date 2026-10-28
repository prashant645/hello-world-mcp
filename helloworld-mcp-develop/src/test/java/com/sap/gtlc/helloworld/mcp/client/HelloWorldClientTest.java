package com.sap.gtlc.helloworld.mcp.client;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelloWorldClientTest {

    @Test
    void classifiesEntraAuthenticatedLocalUserForObo() {
        assertThat(classify(claims(
                "sap_id_type", "user",
                "user_uuid", "user-123",
                "entra_tid", "tenant-123",
                "email", "user@example.com")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.LOCAL_IAS_USER);
    }

    @Test
    void classifiesEntraAuthenticatedUserWithoutLocalIasAccountForObo() {
        assertThat(classify(claims(
                "sap_id_type", "user",
                "entra_tid", "tenant-123",
                "email", "user@example.com")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.ENTRA_GUEST);
    }

    @Test
    void rejectsLocalIasUserWithoutEntraAuthentication() {
        assertThat(classify(claims(
                "sap_id_type", "user",
                "user_uuid", "user-123",
                "email", "user@example.com")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.REJECTED);
    }

    @Test
    void rejectsEntraTokenWithIncompleteHumanIdentity() {
        assertThat(classify(claims(
                "entra_tid", "tenant-123",
                "email", "user@example.com")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.REJECTED);
    }

    @Test
    void rejectsHumanIndicatorsWithoutEntraAuthentication() {
        assertThat(classify(claims(
                "sap_id_type", "user",
                "email", "user@example.com")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.REJECTED);
    }

    @Test
    void acceptsOnlyExplicitApplicationTokenForJwtM2mFallback() {
        assertThat(classify(claims("sap_id_type", "app")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.APPLICATION);
    }

    @Test
    void rejectsApplicationTokenContainingHumanIdentityClaims() {
        assertThat(classify(claims(
                "sap_id_type", "app",
                "email", "user@example.com")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.REJECTED);
    }

    @Test
    void rejectsTokensWithMissingOrUnknownIdentityType() {
        assertThat(classify(Map.of("iss", "issuer")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.REJECTED);
        assertThat(classify(claims("sap_id_type", "unknown")))
                .isEqualTo(HelloWorldClient.IncomingTokenType.REJECTED);
    }

    private static HelloWorldClient.IncomingTokenType classify(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("incoming-token")
                .header("alg", "none")
                .claims(tokenClaims -> tokenClaims.putAll(claims))
                .build();
        return HelloWorldClient.classifyIncomingToken(new JwtAuthenticationToken(jwt));
    }

    private static Map<String, Object> claims(Object... entries) {
        Map<String, Object> claims = new java.util.HashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            claims.put((String) entries[index], entries[index + 1]);
        }
        return claims;
    }
}