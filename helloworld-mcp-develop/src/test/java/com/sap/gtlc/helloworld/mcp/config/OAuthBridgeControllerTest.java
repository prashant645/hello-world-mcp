package com.sap.gtlc.helloworld.mcp.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OAuthBridgeControllerTest {

    private MockMvc mockMvc;
    private MockRestServiceServer oauthServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        oauthServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        OAuthBridgeController controller = new OAuthBridgeController(
                "https://tenant.accounts.example.com/",
                "https://mcp.example.com/",
                restClientBuilder.build());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void redirectsAuthorizationRequestToIdentityProviderAndPreservesPkceParameters() throws Exception {
        mockMvc.perform(get("/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "client-id")
                        .queryParam("code_challenge", "challenge")
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("redirect_uri", "https://client.example.com/oauth/callback"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        containsString("https://tenant.accounts.example.com/oauth2/authorize?")))
                .andExpect(header().string("Location", containsString("client_id=client-id")))
                .andExpect(header().string("Location", containsString("code_challenge=challenge")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
                .andExpect(header().string("Location",
                        containsString("redirect_uri=https://client.example.com/oauth/callback")));
    }

    @Test
    void publishesProtectedResourceMetadata() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource")
                        .secure(true)
                        .header("Host", "mcp.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("https://mcp.example.com/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]")
                        .value("https://tenant.accounts.example.com"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("openid"));
    }

    @Test
    void redirectsOAuthAndOidcMetadataToIdentityProvider() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://tenant.accounts.example.com/.well-known/oauth-authorization-server"));

        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://tenant.accounts.example.com/.well-known/openid-configuration"));
    }

    @Test
    void forwardsPkceTokenExchangeToIdentityProvider() throws Exception {
        oauthServer.expect(requestTo("https://tenant.accounts.example.com/oauth2/token"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("grant_type=authorization_code")))
                .andExpect(content().string(containsString("code_verifier=verifier")))
                .andRespond(withSuccess("{\"access_token\":\"token\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", "authorization-code")
                        .param("code_verifier", "verifier")
                        .param("client_id", "client-id")
                        .param("redirect_uri", "https://client.example.com/oauth/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("token"));

        oauthServer.verify();
    }
}

