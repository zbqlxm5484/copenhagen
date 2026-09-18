package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.integration.enabled", havingValue = "true")
public class WidgetActionConfiguration {
    @Bean
    WidgetActionStore widgetActionStore(McpJsonMapper mapper, JsonSchemaValidator validator) {
        return new WidgetActionStore(mapper, validator, Clock.systemUTC());
    }

    @Bean
    McpToolDefinition prepareWidgetAction(WidgetActionStore store) {
        var input = CommerceSchemas.object(Map.of("tool", Map.of("type", "string", "maxLength", 64),
                "arguments", Map.of("type", "object")), List.of("tool", "arguments"));
        return new McpToolDefinition(McpSchema.Tool.builder("prepare_widget_action", input)
                .description("UI 선택 정보를 15분 동안 보관합니다. 실제 장바구니·결제 변경은 실행하지 않습니다.")
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(Map.of("ui", Map.of("visibility", List.of("app")),
                        "openai/visibility", "private", "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(false).openWorldHint(false).build()).build(), Set.of(),
                (identity, request) -> store.prepare(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition resolveWidgetAction(WidgetActionStore store) {
        return new McpToolDefinition(McpSchema.Tool.builder("resolve_widget_action",
                CommerceSchemas.object(Map.of("reference", CommerceSchemas.KEY), List.of("reference")))
                .description("사용자의 메시지에 코펜하겐 요청번호가 있으면 이 도구로 선택 내용을 조회하세요. "
                        + "반환된 tool과 arguments를 그대로 사용해 해당 도구를 한 번 호출하고 새 UI를 표시하세요. "
                        + "내부 도구명·인자·JSON은 답변에 출력하지 마세요. 변경 요청의 idempotencyKey를 바꾸거나 "
                        + "임의 재실행하지 마세요. 참조가 만료되면 실행하지 말고 카드를 다시 선택하도록 안내하세요.")
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(Map.of("ui", Map.of("visibility", List.of("model"))))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build(), Set.of(),
                (identity, request) -> store.resolve(identity, (String) request.arguments().get("reference")));
    }
}
