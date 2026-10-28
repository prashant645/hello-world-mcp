package com.sap.gtlc.helloworld.mcp.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Rate limit configuration used in ConfigUpdateRequest and GreetingServiceConfig.
 */
public record RateLimitConfig(

    @JsonPropertyDescription("Maximum number of requests allowed per minute")
    Integer requestsPerMinute,

    @JsonPropertyDescription("Whether rate limiting is currently enabled")
    Boolean enabled

) {}
