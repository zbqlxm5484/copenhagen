package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WidgetActionStoreTest {
    private final Instant now = Instant.parse("2026-09-19T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final WidgetActionStore store = new WidgetActionStore(McpJsonDefaults.getMapper(),
            McpJsonDefaults.getSchemaValidator(), clock);

    private McpIdentity identity(String user, String connection, String scope) {
        return new McpIdentity(user, connection, Set.of(scope), now.plusSeconds(3600));
    }

    private String prepare(McpIdentity owner, String tool, Map<String, Object> args) {
        when(clock.instant()).thenReturn(now);
        var output = store.prepare(owner, Map.of("tool", tool, "arguments", args));
        return (String) ((Map<?, ?>) output.get("data")).get("reference");
    }

    @Test
    void selectionIsSnapshottedAndIsolatedFromOtherCardsAndAccounts() {
        var owner = identity("one", "connection", "catalog:read");
        Map<String, Object> args = new HashMap<>(Map.of("merchantId", "merchant-a", "merchantProductId", "onion"));
        String reference = prepare(owner, "get_product_detail", args);
        args.put("merchantProductId", "changed");
        prepare(owner, "search_products", Map.of("query", "empty"));
        var resolved = store.resolve(owner, reference);
        assertEquals(Map.of("tool", "get_product_detail", "arguments",
                Map.of("merchantId", "merchant-a", "merchantProductId", "onion")), resolved.get("data"));
        assertThrows(RuntimeException.class,
                () -> store.resolve(identity("two", "connection", "catalog:read"), reference));
        assertThrows(RuntimeException.class,
                () -> store.resolve(identity("one", "other", "catalog:read"), reference));
        when(clock.instant()).thenReturn(now.plusSeconds(900));
        assertThrows(RuntimeException.class, () -> store.resolve(owner, reference));
    }

    @Test
    void writeArgumentsAreStableAndScopesAreCheckedAgainOnResolve() {
        var owner = identity("one", "connection", "cart:write");
        var args = Map.<String, Object>of("cartVersion", 2, "cartItemId", "item-1",
                "idempotencyKey", "00000000-0000-4000-8000-000000000001");
        String reference = prepare(owner, "delete_cart_item", args);
        assertEquals(store.resolve(owner, reference), store.resolve(owner, reference));
        assertEquals(Map.of("tool", "delete_cart_item", "arguments", args),
                store.resolve(owner, reference).get("data"));
        assertThrows(RuntimeException.class,
                () -> store.resolve(identity("one", "connection", "cart:read"), reference));
        assertThrows(RuntimeException.class, () -> prepare(owner, "delete_cart_item", Map.of()));
        assertThrows(RuntimeException.class, () -> prepare(owner, "unknown", Map.of()));
    }
}
