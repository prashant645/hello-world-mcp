package com.sap.gtlc.helloworld.mcp.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Request body for POST /api/v1/greetings.
 * Message must not be blank, max 10 words and 50 characters.
 */
public record GreetingRequest(

    @JsonPropertyDescription("The message to greet. Max 10 words and 50 characters. Plain text only.")
    String message

) {}
