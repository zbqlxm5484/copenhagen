package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Short-lived references never authorize execution; the original tool still checks permissions. */
final class WidgetActionStore {
    private static final Map<String, Map<String, Object>> SCHEMAS = Map.of(
            "search_products", CommerceSchemas.SEARCH, "get_product_detail", CommerceSchemas.DETAIL,
            "get_cart", CommerceSchemas.EMPTY, "add_cart_item", CommerceSchemas.ADD,
            "update_cart_item", CommerceSchemas.UPDATE, "delete_cart_item", CommerceSchemas.DELETE,
            "prepare_purchase_handoff", CommerceSchemas.PURCHASE,
            "get_purchase_status", CommerceSchemas.PURCHASE_STATUS,
            "resend_purchase_notification", CommerceSchemas.PURCHASE_NOTIFY);
    private final Map<String, Entry> entries = new HashMap<>();
    private final McpJsonMapper mapper;
    private final JsonSchemaValidator validator;
    private final Clock clock;

    WidgetActionStore(McpJsonMapper mapper, JsonSchemaValidator validator, Clock clock) {
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
    }

    synchronized Map<String, Object> prepare(McpIdentity identity, Map<String, Object> input) {
        String tool = (String) input.get("tool");
        Object arguments = input.get("arguments");
        if (!SCHEMAS.containsKey(tool) || !validator.validate(SCHEMAS.get(tool), arguments).valid()) {
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        identity.requireScopes(scopes(tool));
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        // Fail closed instead of evicting another still-valid selection.
        if (entries.size() >= 10000 || entries.values().stream()
                .filter(entry -> entry.owner().equals(identity.publicUserRef())).count() >= 100) {
            throw new CoreClientException(ToolErrorCode.ACTION_REFERENCE_UNAVAILABLE);
        }
        try {
            String reference = UUID.randomUUID().toString();
            entries.put(reference, new Entry(identity.publicUserRef(), identity.connectionRef(), tool,
                    mapper.writeValueAsString(arguments), now.plus(Duration.ofMinutes(15))));
            return Map.of("kind", "action.reference", "data", Map.of("reference", reference));
        } catch (IOException exception) {
            throw new CoreClientException(ToolErrorCode.INTERNAL_ERROR);
        }
    }

    synchronized Map<String, Object> resolve(McpIdentity identity, String reference) {
        Entry entry = entries.get(reference);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())
                || !entry.owner().equals(identity.publicUserRef())
                || !entry.connection().equals(identity.connectionRef())) {
            throw new CoreClientException(ToolErrorCode.ACTION_REFERENCE_UNAVAILABLE);
        }
        identity.requireScopes(scopes(entry.tool()));
        try {
            return Map.of("kind", "action.resolved", "data", Map.of("tool", entry.tool(),
                    "arguments", mapper.readValue(entry.arguments(), Object.class)));
        } catch (IOException exception) {
            throw new CoreClientException(ToolErrorCode.INTERNAL_ERROR);
        }
    }

    private static Set<String> scopes(String tool) {
        return Set.of(switch (tool) {
            case "get_cart" -> "cart:read";
            case "add_cart_item", "update_cart_item", "delete_cart_item" -> "cart:write";
            case "prepare_purchase_handoff", "get_purchase_status", "resend_purchase_notification"
                    -> "purchase:handoff";
            default -> "catalog:read";
        });
    }

    private record Entry(String owner, String connection, String tool, String arguments, Instant expiresAt) {
    }
}
