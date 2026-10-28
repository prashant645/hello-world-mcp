package com.sap.gtlc.helloworld.mcp.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Message validation configuration used in ConfigUpdateRequest and GreetingServiceConfig.
 */
public record ValidationConfig(

    @JsonPropertyDescription("Maximum number of words allowed in a greeting message")
    Integer maxWords,

    @JsonPropertyDescription("Maximum number of characters allowed in a greeting message")
    Integer maxCharacters

) {}
