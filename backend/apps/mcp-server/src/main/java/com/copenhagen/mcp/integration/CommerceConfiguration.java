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
@EnableConfigurationProperties({AgentJwtProperties.class, ServiceAuthProperties.class})
public class CommerceConfiguration {
    static final String SEARCH_GUIDANCE = "상품 검색 조건이 모호하면 먼저 용도·예산·종류 중 중요한 조건을 "
            + "1~2개 질문하고 사용자의 답으로 범위를 좁히세요. 충분히 구체적이거나 바로 검색 요청이면 "
            + "추가 질문 없이 실행합니다. query에는 직접 검색어를, relatedQueries에는 용도에 맞는 유사 객체 "
            + "최대 2개를 넣으세요. 예: 물컵 → 머그컵·텀블러. 사용자가 제외한 종류는 추가하지 않습니다. "
            + "결과의 직접 상품과 유사 상품을 구분하고 확인되지 않은 추천 근거를 만들지 마세요. "
            + "categoryId 생략 시 전체 상품군입니다. 후속 페이지는 해당 검색어의 nextCursor를 사용합니다.";

    private final org.springframework.beans.factory.ObjectProvider<CommerceUiConfiguration.CommerceViews> views;

    public CommerceConfiguration(
            org.springframework.beans.factory.ObjectProvider<CommerceUiConfiguration.CommerceViews> views) {
        this.views = views;
    }

    @Bean
    CoreCommerceService coreCommerceService(CoreProperties core, HttpClient coreHttpClient,
            ServiceTokenProvider tokens, McpJsonMapper mapper, JsonSchemaValidator validator,
            AgentJwtProperties jwt, ServiceAuthProperties serviceAuth,
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
                validator);
    }

    @Bean
    McpToolDefinition coreSearchProducts(CoreCommerceService service) {
        return tool("search_products", "상품 검색", SEARCH_GUIDANCE
                + " 여러 재료를 함께 찾을 때 search_products_batch가 제공되면 해당 도구를 우선 사용하세요."
                + " 먼저 이 도구로 조회하세요. 오류 또는 직접·유사 상품이 모두 없으면 짧은 텍스트로만 안내하고 "
                + "표시 도구를 호출하지 마세요. 상품이 있으면 같은 조건으로 display_products를 한 번 호출하세요.",
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
    McpToolDefinition corePrepareCart(CoreCommerceService service) {
        return tool("prepare_cart", "장바구니 이용 준비", "사용자가 장바구니 이용을 요청하면 먼저 호출합니다. "
                + "조회·변경 권한을 함께 확인하고 현재 장바구니만 조회합니다. 항목을 변경하지 않습니다.",
                CommerceSchemas.EMPTY, Set.of("cart:read", "cart:write"), true,
                (identity, request) -> service.cart(identity));
    }

    @Bean
    McpToolDefinition coreGetCart(CoreCommerceService service) {
        return tool("get_cart", "장바구니 조회", "사용자가 텍스트 또는 음성으로 장바구니 보여줘, 담은 상품 확인 등 "
                + "장바구니 조회를 요청하면 호출하여 상품 카드와 결제 요청 UI를 표시합니다. 검색 UI를 먼저 열 필요가 "
                + "없습니다. 조회만 하며 변경하거나 결제를 요청하지 않습니다. 미확정 금액은 null입니다.",
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
                + "앱에 배송지 선택 알림을 보내고 사용자가 고른 배송지로 검증한 뒤 승인 알림을 보냅니다. "
                + "배송지와 결제수단은 앱에서 선택·등록합니다. "
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

    @Bean
    McpToolDefinition corePurchaseStatus(CoreCommerceService service) {
        return tool("get_purchase_status", "구매 진행 상태", "구매 요청의 현재 검증·승인·결제·알림 상태를 조회합니다. "
                + "COMPLETED만 전체 결제 완료입니다. PARTIALLY_COMPLETED는 일부 판매처만 완료된 상태입니다.",
                CommerceSchemas.PURCHASE_STATUS, "purchase:handoff", true,
                (identity, request) -> service.purchaseStatus(identity, request.arguments(), false));
    }

    @Bean
    McpToolDefinition coreResendNotification(CoreCommerceService service) {
        return tool("resend_purchase_notification", "구매 알림 재전송", "사용자가 요청한 경우에만 앱 알림을 재전송합니다. "
                + "새 구매를 만들지 않습니다. 결과 불명 시 같은 UUID 멱등키를 재사용합니다.",
                CommerceSchemas.PURCHASE_NOTIFY, "purchase:handoff", false,
                (identity, request) -> service.purchaseStatus(identity, request.arguments(), true));
    }

    McpToolDefinition tool(String name, String title, String description, Map<String, Object> input,
            String scope, boolean readOnly,
            java.util.function.BiFunction<com.copenhagen.mcp.auth.McpIdentity,
                    McpSchema.CallToolRequest, Map<String, Object>> handler) {
        return tool(name, title, description, input, Set.of(scope), readOnly, handler);
    }

    private McpToolDefinition tool(String name, String title, String description, Map<String, Object> input,
            Set<String> scopes, boolean readOnly,
            java.util.function.BiFunction<com.copenhagen.mcp.auth.McpIdentity,
                    McpSchema.CallToolRequest, Map<String, Object>> handler) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, input).title(title).description(description)
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(views.getIfAvailable() == null || "search_products".equals(name)
                        ? Map.of("securitySchemes", List.of(Map.of("type", "oauth2", "scopes",
                                scopes.stream().sorted().toList())),
                                "ui", Map.of("visibility", List.of("model", "app")), "openai/widgetAccessible", true)
                        : views.getObject().metadata(name, scopes))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly)
                        .destructiveHint(!readOnly).idempotentHint(true).openWorldHint(true).build()).build(),
                scopes, handler);
    }
}
