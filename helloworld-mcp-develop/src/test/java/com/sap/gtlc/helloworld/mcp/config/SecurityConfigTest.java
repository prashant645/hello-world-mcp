package com.sap.gtlc.helloworld.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void diagnosticsHandleNonStringIdentityClaimsWithoutFailingAuthentication() {
        SecurityConfig securityConfig = new SecurityConfig();
        ReflectionTestUtils.setField(securityConfig, "authDiagnosticsEnabled", true);
        Converter<Jwt, AbstractAuthenticationToken> converter =
                securityConfig.iasJwtAuthenticationConverter();

        Map<String, Object> malformedClaims = Map.of(
                "user_uuid", List.of("user-123"),
                "sap_id_type", 17,
                "email", List.of("user@example.com"),
                "entra_tid", 123,
                "azp", List.of("client-id"),
                "preferred_username", Map.of("name", "user"),
                "sub", List.of("subject"));

        for (Map.Entry<String, Object> malformedClaim : malformedClaims.entrySet()) {
            Map<String, Object> claims = new HashMap<>(Map.of(
                    "user_uuid", "user-123",
                    "sap_id_type", "user",
                    "email", "user@example.com",
                    "entra_tid", "tenant-123",
                    "azp", "client-id",
                    "preferred_username", "user@example.com",
                    "sub", "subject"));
            claims.put(malformedClaim.getKey(), malformedClaim.getValue());

            Jwt jwt = Jwt.withTokenValue("incoming-token")
                    .header("alg", "none")
                    .claims(tokenClaims -> tokenClaims.putAll(claims))
                    .build();

            assertThat(converter.convert(jwt)).isNotNull();
        }
    }
}