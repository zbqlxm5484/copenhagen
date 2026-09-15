package com.copenhagen.mcp.tool;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpAuthResponses;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

    private final McpJsonMapper mapper;
    private final JsonSchemaValidator validator;
    private final McpAuthResponses authResponses;

    public McpToolExecutor(McpJsonMapper mapper, JsonSchemaValidator validator, McpAuthResponses authResponses) {
        this.mapper = mapper;
        this.validator = validator;
        this.authResponses = authResponses;
    }

    public McpServerFeatures.SyncToolSpecification specification(McpToolDefinition definition) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(definition.tool())
                .callHandler((exchange, request) -> execute(definition, exchange, request))
                .build();
    }

    private McpSchema.CallToolResult execute(
            McpToolDefinition definition,
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            McpIdentity identity = McpIdentity.from(exchange);
            identity.requireScopes(definition.requiredScopes());
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            if (!validator.validate(definition.tool().inputSchema(), arguments).valid()) {
                return error(ToolErrorCode.INVALID_ARGUMENT);
            }
            Map<String, Object> output = definition.handler().apply(identity, request);
            // 검증 오류의 원문에는 입력·출력 값이 포함될 수 있어 고정된 오류만 반환한다.
            if (output == null || !validator.validate(definition.tool().outputSchema(), output).valid()) {
                return error(ToolErrorCode.INTERNAL_ERROR);
            }
            return result(output, false);
        } catch (McpAuthException exception) {
            try {
                McpSchema.CallToolResult result = result(authResponses.body(exception), true);
                return new McpSchema.CallToolResult(result.content(), true, result.structuredContent(),
                        authResponses.toolMetadata(exception));
            } catch (IOException serializationException) {
                return error(ToolErrorCode.INTERNAL_ERROR);
            }
        } catch (CoreClientException exception) {
            return error(exception.code());
        } catch (RuntimeException | IOException exception) {
            // 최상위 Tool 경계에서는 예외 메시지와 요청 본문을 기록하지 않는다.
            log.warn("MCP tool failed: tool={}, errorType={}", definition.tool().name(),
                    exception.getClass().getSimpleName());
            return error(ToolErrorCode.INTERNAL_ERROR);
        }
    }

    private McpSchema.CallToolResult error(ToolErrorCode code) {
        try {
            return result(Map.of("error", Map.of("code", code.name(), "message", code.message())), true);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot serialize MCP error response", exception);
        }
    }

    private McpSchema.CallToolResult result(Map<String, Object> value, boolean error) throws IOException {
        String json = mapper.writeValueAsString(value);
        Object snapshot = mapper.readValue(json, Object.class);
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(json).build()))
                .structuredContent(snapshot)
                .isError(error)
                .build();
    }
}
