package com.sap.gtlc.helloworld.mcp.model;

/**
 * Response body for POST /api/v1/greetings/update.
 */
public record ConfigUpdateResponse(

    String status,
    ConfigUpdateRequest appliedConfig,
    String message,
    String updatedBy,
    String updatedAt   // ISO-8601 date-time string

) {}
