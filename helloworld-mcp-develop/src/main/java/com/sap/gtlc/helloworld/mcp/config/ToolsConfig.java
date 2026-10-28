package com.sap.gtlc.helloworld.mcp.config;

import com.sap.gtlc.helloworld.mcp.tools.HelloWorldTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers HelloWorldTools with the Spring AI MCP server.
 *
 * MethodToolCallbackProvider scans the given bean for @Tool-annotated methods
 * and exposes each as a named MCP tool.  The MCP server auto-configuration
 * discovers all ToolCallbackProvider beans in the application context.
 */
@Configuration
public class ToolsConfig {

    @Bean
    public ToolCallbackProvider helloWorldToolProvider(HelloWorldTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
