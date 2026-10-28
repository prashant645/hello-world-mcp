package com.sap.gtlc.helloworld.mcp.config;

import com.sap.gtlc.helloworld.mcp.client.RestClients;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Exposes OAuth metadata required by MCP clients and bridges legacy clients
 * that resolve /authorize and /token relative to the MCP server URL.
 */
@RestController
public class OAuthBridgeController {

    private final String issuerUri;
    private final String publicBaseUrl;
    private final RestClient restClient;

    @Autowired
    public OAuthBridgeController(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${mcp.public-base-url}") String publicBaseUrl) {
        this(issuerUri, publicBaseUrl, RestClients.create());
    }

    OAuthBridgeController(String issuerUri, String publicBaseUrl, RestClient restClient) {
        this.issuerUri = issuerUri.replaceAll("/+$", "");
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.restClient = restClient;
    }

    @GetMapping({
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp"
    })
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", publicBaseUrl + "/mcp",
                "authorization_servers", List.of(issuerUri),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", List.of("openid", "offline_access")
        );
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public ResponseEntity<Void> authorizationServerMetadata() {
        return redirect(issuerUri + "/.well-known/oauth-authorization-server");
    }

    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<Void> openIdConfiguration() {
        return redirect(issuerUri + "/.well-known/openid-configuration");
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        request.getParameterMap().forEach((name, values) ->
                parameters.put(name, List.of(values)));
        String target = UriComponentsBuilder
                .fromUriString(issuerUri + "/oauth2/authorize")
                .queryParams(parameters)
                .build()
                .encode()
                .toUriString();
        return redirect(target);
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> token(
            @RequestBody MultiValueMap<String, String> form,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return restClient.post()
                .uri(issuerUri + "/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> {
                    if (authorization != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .body(form)
                .exchange((request, response) -> ResponseEntity
                        .status(response.getStatusCode())
                        .headers(headers -> {
                            MediaType contentType = response.getHeaders().getContentType();
                            if (contentType != null) {
                                headers.setContentType(contentType);
                            }
                            // getOrEmpty() was a MultiValueMap default method removed in Spring Framework 7.
                            // Use get() with an explicit null guard instead.
                            List<String> cacheControl = response.getHeaders().get(HttpHeaders.CACHE_CONTROL);
                            if (cacheControl != null) {
                                cacheControl.forEach(value -> headers.add(HttpHeaders.CACHE_CONTROL, value));
                            }
                            List<String> pragma = response.getHeaders().get(HttpHeaders.PRAGMA);
                            if (pragma != null) {
                                pragma.forEach(value -> headers.add(HttpHeaders.PRAGMA, value));
                            }
                        })
                        .body(new String(response.getBody().readAllBytes())));
    }

    private ResponseEntity<Void> redirect(String target) {
        return ResponseEntity.status(302).location(URI.create(target)).build();
    }
}

