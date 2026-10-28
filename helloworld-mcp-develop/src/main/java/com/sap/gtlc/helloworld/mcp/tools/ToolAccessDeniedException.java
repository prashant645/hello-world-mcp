package com.sap.gtlc.helloworld.mcp.tools;

/**
 * Thrown by a tool when the backend rejects the call with 401 Unauthorized or
 * 403 Forbidden.
 *
 * The message is deliberately generic for MCP callers. Detailed action and
 * privilege context belongs in server logs, not the tool result. The exception
 * does not expose backend URLs, SAP problem-detail JSON, or Java exceptions.
 *
 * Spring AI's MethodToolCallback wraps whatever a @Tool method throws in a
 * ToolExecutionException and rethrows it; the MCP server handler
 * (McpToolUtils) catches that and returns a CallToolResult with isError=true
 * whose text is this exception's message. So throwing this — rather than
 * returning a value — is what both surfaces a clean message to the model and
 * flags the result as an error.
 */
public class ToolAccessDeniedException extends RuntimeException {

    public ToolAccessDeniedException(String message) {
        super(message);
    }
}
