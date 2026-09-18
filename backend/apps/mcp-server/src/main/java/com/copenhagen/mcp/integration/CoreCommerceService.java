package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import com.copenhagen.mcp.core.InternalCoreClient;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoreCommerceService {
    private final InternalCoreClient client;
    private final CommerceProperties properties;
    private final JsonSchemaValidator validator;

    public CoreCommerceService(InternalCoreClient client, CommerceProperties properties,
            JsonSchemaValidator validator) {
        this.client = client;
        this.properties = properties;
        this.validator = validator;
    }

    public Map<String, Object> search(McpIdentity identity, Map<String, Object> input) {
        validate(CommerceSchemas.SEARCH, input);
        if (input.get("minPrice") instanceof Number min && input.get("maxPrice") instanceof Number max
                && min.longValue() > max.longValue()) {
            throw failure("INVALID_ARGUMENT", false);
        }
        Map<String, Object> data = client.exchange(identity, "GET", "/internal/v1/catalog/products", input, null, null);
        // 비교 묶음은 Core가 반환한 경계를 유지한다. 이름으로 합치거나 실패 판매처를 제거하지 않는다.
        if (!(data.get("products") instanceof List<?>) || !(data.get("filters") instanceof Map<?, ?>)
                || !(data.get("failedMerchants") instanceof List<?>) || !data.containsKey("nextCursor")) {
            throw failure("CORE_INVALID_RESPONSE", false);
        }
        return output("catalog.search", CommerceProjection.search(data));
    }

    public Map<String, Object> detail(McpIdentity identity, Map<String, Object> input) {
        validate(CommerceSchemas.DETAIL, input);
        String path = "/internal/v1/merchants/" + input.get("merchantId")
                + "/products/" + input.get("merchantProductId");
        Map<String, Object> data = client.exchange(identity, "GET", path, Map.of(), null, null);
        if (!input.get("merchantId").equals(data.get("merchantId"))
                || !input.get("merchantProductId").equals(data.get("merchantProductId"))) {
            throw failure("CORE_INVALID_RESPONSE", false);
        }
        return output("catalog.detail", CommerceProjection.detail(data));
    }

    public Map<String, Object> cart(McpIdentity identity) {
        return output("cart.view", CommerceProjection.cart(client.exchange(identity, "GET",
                "/internal/v1/carts/current", Map.of(), null, null)));
    }

    public Map<String, Object> mutate(McpIdentity identity, String operation, Map<String, Object> input) {
        Map<String, Object> schema = switch (operation) {
            case "POST" -> CommerceSchemas.ADD;
            case "PATCH" -> CommerceSchemas.UPDATE;
            case "DELETE" -> CommerceSchemas.DELETE;
            default -> throw new IllegalArgumentException("Unknown cart mutation");
        };
        validate(schema, input);
        Map<String, Object> body = new LinkedHashMap<>(input);
        String key = (String) body.remove("idempotencyKey");
        String path = "/internal/v1/carts/current/items";
        if (!operation.equals("POST")) {
            path += "/" + body.remove("cartItemId");
        }
        Map<String, Object> query = operation.equals("DELETE")
                ? Map.of("cartVersion", body.get("cartVersion")) : Map.of();
        Map<String, Object> response = client.exchange(identity, operation, path, query,
                operation.equals("DELETE") ? null : body, key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "APPLIED");
        result.put("cart", null);
        result.put("cartSource", "UNAVAILABLE");
        result.put("nextAction", "REFETCH_CART");
        // 성공 응답 이후 조회 실패를 변경 실패로 표현하지 않는다. Core의 저장 응답만 사용한다.
        Object candidate = response.containsKey("cartId") ? response : response.get("cart");
        if (candidate instanceof Map<?, ?> map) {
            try {
                result.put("cart", CommerceProjection.cart(stringMap(map)));
                result.put("cartSource", "MUTATION_RESPONSE");
                result.put("nextAction", "NONE");
            } catch (CoreApiException exception) {
                // 변경 성공은 확인됐으므로 사용자가 현재 장바구니만 다시 조회하게 한다.
            }
        }
        return output("cart.mutation", result);
    }

    public Map<String, Object> purchase(McpIdentity identity, Map<String, Object> input) {
        validate(CommerceSchemas.PURCHASE, input);
        Map<String, Object> response = client.exchange(identity, "POST", "/internal/v1/purchase-requests", Map.of(),
                Map.of("cartId", input.get("cartId"), "cartVersion", input.get("cartVersion")),
                (String) input.get("idempotencyKey"));
        Map<String, Object> data = CommerceProjection.pick(response, "purchaseRequestId", "status", "cartId",
                "cartVersion", "expiresAt", "appLink", "nextAction");
        try {
            String id = CommerceProjection.id(data.get("purchaseRequestId"));
            URI expected = properties.appLinkBase().resolve(id);
            if (!expected.toString().equals(data.get("appLink")) || !input.get("cartId").equals(data.get("cartId"))
                    || !input.get("cartVersion").toString().equals(data.get("cartVersion").toString())
                    || !"PENDING_ADDRESS".equals(data.get("status"))
                    || !"SELECT_SHIPPING_ADDRESS".equals(data.get("nextAction"))) {
                throw failure("CORE_INVALID_RESPONSE", true);
            }
            Instant expires = Instant.parse((String) data.get("expiresAt"));
            if (expires.isAfter(Instant.now().plusSeconds(1860))) {
                throw failure("CORE_INVALID_RESPONSE", true);
            }
            // 같은 키의 응답은 생성 당시 스냅샷이다. 현재 구매/결제 상태로 해석하지 않는다.
            data.put("linkExpired", !Instant.now().isBefore(expires));
        } catch (CoreApiException | IllegalArgumentException | NullPointerException
                | java.time.DateTimeException | ClassCastException exception) {
            throw failure("CORE_INVALID_RESPONSE", true);
        }
        return output("purchase.handoff", data);
    }

    public Map<String, Object> purchaseStatus(McpIdentity identity, Map<String, Object> input, boolean resend) {
        validate(resend ? CommerceSchemas.PURCHASE_NOTIFY : CommerceSchemas.PURCHASE_STATUS, input);
        String path = "/internal/v1/purchase-requests/" + input.get("purchaseRequestId");
        Map<String, Object> response = client.exchange(identity, resend ? "POST" : "GET",
                resend ? path + "/notifications" : path, Map.of(), null,
                resend ? (String) input.get("idempotencyKey") : null);
        if (!input.get("purchaseRequestId").equals(response.get("purchaseRequestId"))) {
            throw failure("CORE_INVALID_RESPONSE", resend);
        }
        return output("purchase.status", CommerceProjection.purchaseStatus(response));
    }

    private void validate(Map<String, Object> schema, Map<String, Object> input) {
        if (input == null || !validator.validate(schema, input).valid()) {
            throw failure("INVALID_ARGUMENT", false);
        }
    }

    private static Map<String, Object> output(String kind, Map<String, Object> data) {
        return Map.of("kind", kind, "data", data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    static CoreApiException failure(String code, boolean write) {
        return new CoreApiException(code, write ? "UNKNOWN" : "NOT_APPLICABLE", UUID.randomUUID().toString());
    }
}
