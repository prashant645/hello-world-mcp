package com.sap.gtlc.helloworld.mcp.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Shared RestClient factory for outbound HTTP calls.
 *
 * Timeouts are intentionally shorter than the observed Joule/MCP request timeout,
 * so stalled backend or IAS calls fail fast and surface a concrete exception
 * instead of ending as an opaque upstream cancellation.
 */
public final class RestClients {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private RestClients() {
    }

    public static RestClient create() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}

