package dev.sandeep.mcp.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal MCP Streamable HTTP endpoint in front of LangChain4j's transport-agnostic
 * {@link McpServer} (which only ships a stdio transport out of the box).
 * <p>
 * Every JSON-RPC message arrives as a {@code POST /mcp}; requests are answered with a
 * plain {@code application/json} body and notifications with {@code 202 Accepted}, both
 * allowed by the spec. The server never pushes messages on its own, so the optional
 * {@code GET} SSE stream and {@code DELETE} session termination answer {@code 405}.
 * <p>
 * The body is handled as a raw string with a Jackson 2 {@link ObjectMapper}, because
 * the LangChain4j MCP protocol types are Jackson 2 models while Spring Boot 4's message
 * converters are built on Jackson 3.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpServer server;

    public McpController(McpServer server) {
        this.server = server;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body) throws JsonProcessingException {
        JsonNode message;
        try {
            message = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            return jsonRpcError(-32700, "Parse error");
        }

        Object response;
        if (message.isArray()) {
            List<McpJsonRpcMessage> responses = new ArrayList<>();
            message.forEach(node -> {
                McpJsonRpcMessage reply = server.handle(node);
                if (reply != null) {
                    responses.add(reply);
                }
            });
            response = responses.isEmpty() ? null : responses;
        } else {
            response = server.handle(message);
        }

        if (response == null) {
            // Notifications (e.g. notifications/initialized) and responses need no reply.
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(response));
    }

    @GetMapping
    public ResponseEntity<Void> openEventStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> terminateSession() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    private static ResponseEntity<String> jsonRpcError(int code, String message) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":%d,\"message\":\"%s\"}}"
                .formatted(code, message);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
