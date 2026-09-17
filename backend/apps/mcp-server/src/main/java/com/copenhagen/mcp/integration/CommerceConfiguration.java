package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.AgentJwtProperties;
import com.copenhagen.mcp.auth.McpAuthProperties;
import com.copenhagen.mcp.core.CoreProperties;
import com.copenhagen.mcp.core.InternalCoreClient;
import com.copenhagen.mcp.core.ServiceAuthProperties;
import com.copenhagen.mcp.core.ServiceTokenProvider;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.integration.enabled", havingValue = "true")
@EnableConfigurationProperties({CommerceProperties.class, AgentJwtProperties.class, ServiceAuthProperties.class})
public class CommerceConfiguration {
    @Bean
    CoreCommerceService coreCommerceService(CoreProperties core, HttpClient coreHttpClient,
            ServiceTokenProvider tokens, McpJsonMapper mapper, JsonSchemaValidator validator,
            CommerceProperties properties, AgentJwtProperties jwt, ServiceAuthProperties serviceAuth,
            McpAuthProperties auth, @Value("${mcp.catalog.enabled:false}") boolean legacyCatalog,
            @Value("${mcp.cart.enabled:false}") boolean legacyCart,
            @Value("${mcp.handoff.enabled:false}") boolean legacyHandoff,
            @Value("${mcp.merchants.enabled:false}") boolean legacyMerchants) {
        if (!jwt.enabled() || !serviceAuth.enabled() || !auth.configured()
                || !"https".equals(auth.resource().getScheme())
                || !"https".equals(auth.issuer().getScheme())
                || legacyCatalog || legacyCart || legacyHandoff || legacyMerchants) {
            throw new IllegalArgumentException("Core integration requires JWT authentication and disables QA adapters");
        }
        return new CoreCommerceService(new InternalCoreClient(core, coreHttpClient, tokens, mapper),
                properties, validator);
    }

    @Bean
    McpToolDefinition coreSearchProducts(CoreCommerceService service) {
        return tool("search_products", "상품 검색", "전체 상품군을 검색합니다. categoryId 생략 시 전체 검색입니다. "
                + "다음 결과는 nextCursor를 cursor로 전달합니다. 비교 그룹은 Core가 반환한 것만 사용합니다.",
                CommerceSchemas.SEARCH, "catalog:read", true,
                (identity, request) -> service.search(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition coreProductDetail(CoreCommerceService service) {
        return tool("get_product_detail", "상품 옵션·재고 조회", "검색 offer의 merchantId와 merchantProductId로 조회합니다. "
                + "사용자가 선택할 옵션은 반환된 merchantVariantId만 사용합니다.", CommerceSchemas.DETAIL,
                "catalog:read", true, (identity, request) -> service.detail(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition coreGetCart(CoreCommerceService service) {
        return tool("get_cart", "장바구니 조회", "현재 사용자의 Core 장바구니를 조회합니다. 미확정 금액은 null입니다.",
                CommerceSchemas.EMPTY, "cart:read", true, (identity, request) -> service.cart(identity));
    }

    @Bean
    McpToolDefinition coreAddCartItem(CoreCommerceService service) {
        return mutation("add_cart_item", "장바구니 추가", "POST", CommerceSchemas.ADD, service);
    }

    @Bean
    McpToolDefinition coreUpdateCartItem(CoreCommerceService service) {
        return mutation("update_cart_item", "장바구니 옵션·수량 변경", "PATCH", CommerceSchemas.UPDATE, service);
    }

    @Bean
    McpToolDefinition coreDeleteCartItem(CoreCommerceService service) {
        return mutation("delete_cart_item", "장바구니 항목 삭제", "DELETE", CommerceSchemas.DELETE, service);
    }

    @Bean
    McpToolDefinition corePreparePurchase(CoreCommerceService service) {
        return tool("prepare_purchase_handoff", "앱 구매 인계", "사용자가 구매 진행을 요청한 경우에만 호출합니다. "
                + "다수 판매처를 지원하며 배송지·승인·결제는 앱에서 이어갑니다. PENDING_ADDRESS는 결제 성공이 아닙니다. "
                + "UUID 멱등키는 같은 요청에 재사용하고 결과 불명 시 본문을 바꾸지 않습니다. "
                + "재호출은 생성 당시 결과이며 현재 결제 상태를 조회하지 않습니다.", CommerceSchemas.PURCHASE,
                "purchase:handoff", false, (identity, request) -> service.purchase(identity, request.arguments()));
    }

    private McpToolDefinition mutation(String name, String title, String method,
            Map<String, Object> schema, CoreCommerceService service) {
        return tool(name, title, "사용자가 선택한 변경만 반영합니다. cartVersion은 get_cart의 version입니다. "
                + "수량은 최종 수량이며 옵션과 함께 원자적으로 변경됩니다. idempotencyKey는 UUID입니다. "
                + "결과 불명 시 같은 키·본문으로 재호출하고 새 키로 중복 변경하지 마세요. APPLIED 이후 조회 실패 시 "
                + "get_cart만 다시 호출합니다.", schema, "cart:write", false,
                (identity, request) -> service.mutate(identity, method, request.arguments()));
    }

    static McpToolDefinition tool(String name, String title, String description, Map<String, Object> input,
            String scope, boolean readOnly,
            java.util.function.BiFunction<com.copenhagen.mcp.auth.McpIdentity,
                    McpSchema.CallToolRequest, Map<String, Object>> handler) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, input).title(title).description(description)
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(Map.of("securitySchemes", List.of(Map.of("type", "oauth2", "scopes", List.of(scope))),
                        "ui", Map.of("visibility", List.of("model", "app")), "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly)
                        .destructiveHint(!readOnly).idempotentHint(true).openWorldHint(true).build()).build(),
                Set.of(scope), handler);
    }
}
