package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import com.copenhagen.mcp.core.InternalCoreClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreCommerceServiceTest {
    private final InternalCoreClient client = mock(InternalCoreClient.class);
    private final CoreCommerceService service = new CoreCommerceService(client,
            new CommerceProperties(true, URI.create("https://app.example/purchase/")),
            McpJsonDefaults.getSchemaValidator());
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of(),
            Instant.now().plusSeconds(300));

    @Test
    void search_allCategories_keepsCursorAndPartialResultsWithoutPrivateFields() throws Exception {
        Map<String, Object> response = json("""
                {"products":[{"name":"신발","brand":"브랜드","category":{"id":"shoes","name":"신발"},
                "imageUrl":null,"offers":[{"merchantId":"m1","merchantName":"상점","merchantProductId":"p1",
                "priceAmount":12000,"currency":"KRW","availability":"IN_STOCK","privateToken":"excluded"}]}],
                "filters":{"categories":[],"merchants":[]},"nextCursor":"opaque-cursor","failedMerchants":["m2"],
                "checkedAt":"2026-09-17T00:00:00Z","userId":123}
                """);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(response);
        Map<String, Object> output = service.search(identity, Map.of("query", "신발", "pageSize", 30));
        assertEquals("catalog.search", output.get("kind"));
        assertFalse(output.toString().contains("privateToken"));
        assertFalse(output.toString().contains("userId"));
        assertEquals("opaque-cursor", ((Map<?, ?>) output.get("data")).get("nextCursor"));
    }

    @Test
    void mutation_atomicPatchAndDelete_useFinalQuantityAndQueryVersion() {
        String key = UUID.randomUUID().toString();
        when(client.exchange(eq(identity), any(), any(), any(), any(), eq(key))).thenReturn(Map.of());
        service.mutate(identity, "PATCH", Map.of("cartItemId", "item1", "cartVersion", 12,
                "merchantVariantId", "variant2", "quantity", 3, "idempotencyKey", key));
        verify(client).exchange(identity, "PATCH", "/internal/v1/carts/current/items/item1", Map.of(),
                Map.of("cartVersion", 12, "merchantVariantId", "variant2", "quantity", 3), key);
        when(client.exchange(eq(identity), eq("DELETE"), any(), any(), isNull(), eq(key))).thenReturn(Map.of());
        Map<String, Object> output = service.mutate(identity, "DELETE", Map.of("cartItemId", "item1",
                "cartVersion", 13, "idempotencyKey", key));
        verify(client).exchange(identity, "DELETE", "/internal/v1/carts/current/items/item1",
                Map.of("cartVersion", 13), null, key);
        Map<?, ?> result = (Map<?, ?>) output.get("data");
        assertEquals("APPLIED", result.get("outcome"));
        assertNull(result.get("cart"));
        assertEquals("REFETCH_CART", result.get("nextAction"));
    }

    @Test
    void purchase_originalResponseExpired_keepsSnapshotButRejectsUnsafeLink() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = Map.of("cartId", "cart1", "cartVersion", 12, "idempotencyKey", key);
        when(client.exchange(eq(identity), eq("POST"), eq("/internal/v1/purchase-requests"), any(), any(), eq(key)))
                .thenReturn(Map.of("purchaseRequestId", "request1", "status", "PENDING_ADDRESS",
                        "cartId", "cart1", "cartVersion", 12, "expiresAt", "2020-01-01T00:00:00Z",
                        "appLink", "https://app.example/purchase/request1", "nextAction", "SELECT_SHIPPING_ADDRESS"));
        assertEquals(true, ((Map<?, ?>) service.purchase(identity, input).get("data")).get("linkExpired"));
        when(client.exchange(eq(identity), eq("POST"), eq("/internal/v1/purchase-requests"), any(), any(), eq(key)))
                .thenReturn(Map.of("purchaseRequestId", "request1", "status", "PENDING_ADDRESS",
                        "cartId", "cart1", "cartVersion", 12, "expiresAt", "2020-01-01T00:00:00Z",
                        "appLink", "https://evil.example/purchase/request1", "nextAction", "SELECT_SHIPPING_ADDRESS"));
        assertThrows(CoreApiException.class, () -> service.purchase(identity, input));
    }

    @Test
    void purchase_malformedSuccess_keepsOutcomeUnknownForSameKeyRecovery() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = Map.of("cartId", "cart1", "cartVersion", 12, "idempotencyKey", key);
        for (String invalidId : java.util.List.of("../unsafe", "request1")) {
            when(client.exchange(eq(identity), eq("POST"), any(), any(), any(), eq(key)))
                    .thenReturn(Map.of("purchaseRequestId", invalidId, "status", "PENDING_ADDRESS",
                            "cartId", "cart1", "cartVersion", 12, "expiresAt", "invalid-date",
                            "appLink", "https://app.example/purchase/" + invalidId,
                            "nextAction", "SELECT_SHIPPING_ADDRESS"));
            var error = assertThrows(CoreApiException.class, () -> service.purchase(identity, input));
            assertEquals("UNKNOWN", ((Map<?, ?>) error.body().get("error")).get("outcome"));
        }
    }

    @Test
    void cart_unknownTotals_remainNull() throws Exception {
        var cart = CommerceProjection.cart(json("""
                {"cartId":"cart1","version":1,"items":[],"merchantSummaries":[],
                 "summary":{"currency":"KRW","itemSubtotalAmount":0,"discountAmount":null,
                 "shippingAmount":null,"totalAmount":null,"status":"INCOMPLETE"}}
                """));
        assertNull(((Map<?, ?>) cart.get("summary")).get("totalAmount"));
    }

    private Map<String, Object> json(String value) throws Exception {
        return McpJsonDefaults.getMapper().readValue(value, new TypeRef<>() { });
    }
}
