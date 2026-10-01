package dev.sandeep.mcp.server.config;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import dev.sandeep.mcp.server.tool.SupportActionTools;
import dev.sandeep.mcp.server.tool.SupportQueryTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds the LangChain4j {@link McpServer} that publishes every {@code @Tool}
 * method on the query and action tool beans as an MCP tool. The server itself is
 * transport-agnostic; {@code McpController} exposes it over Streamable HTTP.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public McpServer mcpServer(SupportQueryTools queryTools,
                               SupportActionTools actionTools,
                               @Value("${mcp.server.name}") String name,
                               @Value("${mcp.server.version}") String version) {
        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(name);
        serverInfo.setVersion(version);
        return new McpServer(List.of(queryTools, actionTools), serverInfo);
    }
}
