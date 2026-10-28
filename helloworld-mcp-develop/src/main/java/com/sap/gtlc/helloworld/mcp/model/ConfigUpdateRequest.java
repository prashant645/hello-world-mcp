package com.sap.gtlc.helloworld.mcp.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Request body for POST /api/v1/greetings/update.
 * All fields are optional — supply only the settings you want to change.
 */
public record ConfigUpdateRequest(

    @JsonPropertyDescription("Rate limiting settings to apply. Omit to leave unchanged.")
    RateLimitConfig rateLimit,

    @JsonPropertyDescription("Message validation settings to apply. Omit to leave unchanged.")
    ValidationConfig validation

) {}
