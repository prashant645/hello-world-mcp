package com.sap.gtlc.helloworld.mcp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IasTokenProviderTest {

    @Test
    void requestsTokenWithBasicAuthAndResourceParameter() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();

        IasTokenProvider provider = new IasTokenProvider(
                "https://a3enmtmdf.accounts400.ondemand.com/oauth2/token",
                "client-id",
                "client-secret",
                restClientBuilder.build());

        String expectedAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                "client-id:client-secret".getBytes(StandardCharsets.UTF_8));
        String expectedBody = "grant_type=client_credentials&resource=urn:sap:identity:application:provider:name:scope";

        server.expect(requestTo("https://a3enmtmdf.accounts400.ondemand.com/oauth2/token"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", expectedAuthHeader))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(expectedBody))
                .andRespond(withSuccess("{" +
                        "\"access_token\":\"token-value\"," +
                        "\"expires_in\":3600" +
                        "}", MediaType.APPLICATION_JSON));

        assertThat(provider.getToken()).isEqualTo("token-value");
        assertThat(provider.getToken()).isEqualTo("token-value");

        server.verify();
    }
}
