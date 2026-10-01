package com.eazybytes.support.agent.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the agent to the support tools published by the MCP server over
 * Streamable HTTP and exposes them as a LangChain4j {@link McpToolProvider}, which
 * the {@code SupportAgent} AI service hands to the LLM.
 */
@Configuration
public class McpClientConfig {

    /**
     * The MCP server speaks the classic {@code initialize} handshake, so pin that
     * protocol version instead of letting the client probe for the newer one first.
     */
    private static final String MCP_PROTOCOL_VERSION = "2025-11-25";

    @Bean
    public McpTransport mcpTransport(@Value("${support-agent.mcp.url}") String url,
                                     @Value("${support-agent.mcp.log-traffic:false}") boolean logTraffic) {
        return StreamableHttpMcpTransport.builder()
                .url(url)
                .logRequests(logTraffic)
                .logResponses(logTraffic)
                .build();
    }

    @Bean
    public McpClient mcpClient(McpTransport mcpTransport) {
        return DefaultMcpClient.builder()
                .key("support-agent")
                .transport(mcpTransport)
                .protocolVersion(MCP_PROTOCOL_VERSION)
                .build();
    }

    @Bean
    public McpToolProvider mcpToolProvider(McpClient mcpClient) {
        return McpToolProvider.builder()
                .mcpClients(mcpClient)
                .build();
    }
}
