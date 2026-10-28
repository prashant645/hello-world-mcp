package com.sap.gtlc.helloworld.mcp.model;

/**
 * Response body for GET /api/v1/greetings/config.
 * Reflects the current runtime configuration of the Hello World service.
 */
public record GreetingServiceConfig(

    RateLimitConfig rateLimit,
    ValidationConfig validation

) {}
