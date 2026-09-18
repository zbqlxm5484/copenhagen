package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"mcp.integration.enabled", "mcp.ui.enabled"}, havingValue = "true")
public class CommerceUiConfiguration {
    @Bean
    CommerceResource commerceResource() throws IOException, NoSuchAlgorithmException {
        String html = new ClassPathResource("mcp-ui/integration.html").getContentAsString(StandardCharsets.UTF_8)
                .replace("__CART_ENABLED__", "true").replace("__HANDOFF_ENABLED__", "true");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(html.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        return new CommerceResource("ui://copenhagen/core-" + hash + ".html", html);
    }

    @Bean
    SyncResourceSpecification commerceResourceSpecification(CommerceResource resource) {
        String mime = "text/html;profile=mcp-app";
        List<String> images = List.of("https://3p-image.kurly.com", "https://cdn.daisomall.co.kr",
                "https://img-cf.kurly.com", "https://img.danuri.io", "https://product-image.kurly.com");
        Map<String, Object> metadata = Map.of("ui", Map.of("prefersBorder", true,
                "csp", Map.of("connectDomains", List.of(), "resourceDomains", images)),
                "openai/widgetCSP", Map.of("connect_domains", List.of(), "resource_domains", images),
                "openai/widgetDescription", "상품 검색·판매처 비교·장바구니와 앱 결제 승인 상태 화면입니다.");
        return new SyncResourceSpecification(McpSchema.Resource.builder(resource.uri(), "copenhagen-core")
                .mimeType(mime).meta(metadata).build(), (exchange, request) -> {
                    McpIdentity.from(exchange).requireScopes(Set.of("catalog:read"));
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(resource.uri(), mime, resource.html(), metadata)));
                });
    }

    @Bean
    McpToolDefinition coreOpenCopenhagen(CommerceResource resource, CoreCommerceService service) {
        return display("open_copenhagen", resource, service);
    }

    @Bean
    McpToolDefinition coreDisplayProducts(CommerceResource resource, CoreCommerceService service) {
        return display("display_products", resource, service);
    }

    private McpToolDefinition display(String name, CommerceResource resource, CoreCommerceService service) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, CommerceSchemas.SEARCH)
                .title("코펜하겐 상품 화면 열기")
                .description("전체 카테고리 상품 탐색과 판매처 비교 화면을 표시합니다. 선택된 검색 조건으로 한 번만 호출하세요.")
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(Map.of("ui", Map.of("resourceUri", resource.uri(), "visibility", List.of("model")),
                        "securitySchemes", List.of(Map.of("type", "oauth2", "scopes", List.of("catalog:read"))),
                        "openai/outputTemplate", resource.uri(), "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), Set.of("catalog:read"),
                (identity, request) -> service.search(identity, request.arguments()));
    }

    record CommerceResource(String uri, String html) {
    }
}
