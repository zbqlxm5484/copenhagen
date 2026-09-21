package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreIntegrationTransportTest {
    private static final String RESOURCE = "https://mcp.example/mcp";
    private static HttpServer core;
    private static ECKey key;
    private static Path privateKey;
    private static ConfigurableApplicationContext application;
    private static final AtomicReference<String> delegated = new AtomicReference<>();
    private static final AtomicReference<String> service = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).keyID("integration-test").generate();
        privateKey = Files.createTempFile("mcp-test-key-", ".jwk");
        Files.writeString(privateKey, key.toJSONString());
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/jwks", exchange -> reply(exchange, new JWKSet(key.toPublicJWK()).toString()));
        core.createContext("/token", exchange -> reply(exchange,
                "{\"access_token\":\"test-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        core.createContext("/internal/v1/catalog/products", exchange -> {
            delegated.set(exchange.getRequestHeaders().getFirst("X-Agent-Authorization"));
            service.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, """
                    {"products":[],"filters":{"categories":[],"merchants":[]},
                     "nextCursor":null,"failedMerchants":[],"checkedAt":"2026-09-17T00:00:00Z"}
                    """);
        });
        core.createContext("/internal/v1/purchase-requests", exchange -> reply(exchange, """
                {"purchaseRequestId":"request1","status":"PENDING_ADDRESS","cartId":"cart1",
                 "cartVersion":12,"expiresAt":"%s","nextAction":"SELECT_SHIPPING_ADDRESS"}
                """.formatted(Instant.now().plusSeconds(1800))));
        core.createContext("/internal/v1/carts/current", exchange -> reply(exchange, """
                {"cartId":"cart1","version":1,"items":[],"merchantSummaries":[],
                 "summary":{"currency":"KRW","itemSubtotalAmount":0,"discountAmount":null,
                 "shippingAmount":null,"totalAmount":null,"status":"INCOMPLETE"}}
                """));
        core.start();
        String base = "http://127.0.0.1:" + core.getAddress().getPort();
        application = new SpringApplicationBuilder(McpServerApplication.class)
                .profiles("core-integration")
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--mcp.server.allowed-hosts=127.0.0.1:*", "--mcp.auth.jwt.jwks-uri=" + base + "/jwks",
                        "--mcp.auth.issuer=https://core.example", "--mcp.auth.resource=" + RESOURCE,
                        "--services.core.base-url=" + base, "--services.core.auth.token-uri=" + base + "/token",
                        "--services.core.auth.private-jwk-file=" + privateKey);
    }

    @AfterAll
    static void stop() throws Exception {
        if (application != null) {
            application.close();
        }
        if (core != null) {
            core.stop(0);
        }
        if (privateKey != null) {
            Files.deleteIfExists(privateKey);
        }
    }

    @Test
    void authenticatedTransport_exposesOnlyCoreToolsAndDelegatesSignedAgent() throws Exception {
        String token = token("catalog:read cart:read cart:write purchase:handoff");
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application), token)) {
            client.initialize();
            var tools = client.listTools().tools();
            var selectedArgs = Map.of("merchantId", "merchant-c", "merchantProductId", "onion-1");
            var prepared = client.callTool(McpSchema.CallToolRequest.builder().name("prepare_widget_action")
                    .arguments(Map.of("tool", "get_product_detail", "arguments", selectedArgs)).build());
            assertFalse(prepared.isError());
            Map<?, ?> referenceData = (Map<?, ?>) ((Map<?, ?>) prepared.structuredContent()).get("data");
            var resolved = client.callTool(McpSchema.CallToolRequest.builder().name("resolve_widget_action")
                    .arguments(Map.of("reference", referenceData.get("reference"))).build());
            assertFalse(resolved.isError());
            assertEquals(Map.of("tool", "get_product_detail", "arguments", selectedArgs),
                    ((Map<?, ?>) resolved.structuredContent()).get("data"));
            assertEquals(Set.of("search_products", "get_product_detail", "get_cart", "add_cart_item",
                    "update_cart_item", "delete_cart_item", "prepare_purchase_handoff", "open_copenhagen",
                    "display_products", "get_purchase_status", "resend_purchase_notification", "prepare_cart",
                    "prepare_widget_action", "resolve_widget_action", "search_products_batch",
                    "display_search_batch", "get_product_details_batch"),
                    tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet()));
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("open_copenhagen")
                    .arguments(Map.of("query", "삼성")).build());
            assertFalse(result.isError());
            Map<?, ?> diagnostics = (Map<?, ?>) result.meta().get("copenhagen/diagnostics");
            java.util.UUID.fromString((String) diagnostics.get("requestId"));
            assertTrue(((Number) diagnostics.get("serverMillis")).longValue() >= 0);
            assertFalse(diagnostics.toString().contains(token));
            assertEquals("Bearer " + token, delegated.get());
            assertEquals("Bearer test-service-token", service.get());
            assertFalse(result.structuredContent().toString().contains(token));
            var resources = client.listResources().resources();
            assertEquals(4, resources.size());
            for (var resource : resources) {
                var html = (McpSchema.TextResourceContents) client.readResource(
                        new McpSchema.ReadResourceRequest(resource.uri())).contents().getFirst();
                assertFalse(html.text().contains("__VIEW__"));
                assertFalse(html.text().contains("__HANDOFF_LINK_BASE__"));
                assertFalse(html.meta().toString().contains("redirect_domains"));
                assertFalse(html.text().contains("로컬 Mock 안내 시나리오"));
            }
            for (var tool : tools) {
                if (Set.of("search_products", "prepare_widget_action", "resolve_widget_action",
                        "search_products_batch", "get_product_details_batch").contains(tool.name())) {
                    assertFalse(tool.meta().containsKey("openai/outputTemplate"));
                    if (tool.meta().get("ui") instanceof Map<?, ?> ui) {
                        assertFalse(ui.containsKey("resourceUri"));
                    }
                    continue;
                }
                String view = switch (tool.name()) {
                    case "get_product_detail" -> "detail";
                    case "get_cart", "prepare_cart", "add_cart_item", "update_cart_item", "delete_cart_item"
                            -> "cart";
                    case "prepare_purchase_handoff", "get_purchase_status", "resend_purchase_notification"
                            -> "purchase";
                    default -> "search";
                };
                String uri = (String) tool.meta().get("openai/outputTemplate");
                assertTrue(uri.startsWith("ui://copenhagen/" + view + "-"));
                assertEquals(uri, ((Map<?, ?>) tool.meta().get("ui")).get("resourceUri"));
                assertTrue(resources.stream().anyMatch(resource -> resource.uri().equals(uri)));
            }
        }
    }

    @Test
    void shoppingIdentity_canReadStaticCartTemplate() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write"))) {
            client.initialize();
            var resource = client.listResources().resources().stream()
                    .filter(value -> value.uri().startsWith("ui://copenhagen/cart-")).findFirst().orElseThrow();
            var html = (McpSchema.TextResourceContents) client.readResource(
                    new McpSchema.ReadResourceRequest(resource.uri())).contents().getFirst();
            assertTrue(html.text().contains("data-view=\"cart\""));
        }
    }

    @Test
    void purchase_withoutAppLink_returnsReceiptForPushApproval() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write purchase:handoff"))) {
            client.initialize();
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("prepare_purchase_handoff")
                    .arguments(Map.of("cartId", "cart1", "cartVersion", 12,
                            "idempotencyKey", java.util.UUID.randomUUID().toString())).build());
            assertFalse(result.isError());
            assertTrue(result.structuredContent().toString().contains("request1"));
            assertFalse(result.structuredContent().toString().contains("appLink"));
        }
    }

    @Test
    void prepareCart_fullGrant_readsWithoutChallenge() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write"))) {
            client.initialize();
            for (String name : List.of("prepare_cart", "get_cart")) {
                var result = client.callTool(McpSchema.CallToolRequest.builder().name(name)
                        .arguments(Map.of()).build());
                assertFalse(result.isError());
                assertFalse(result.meta().containsKey("mcp/www_authenticate"));
                assertTrue(result.structuredContent().toString().contains("cart.view"));
            }
        }
    }

    @Test
    void shoppingConnection_rejectsPartialGrantWithCompleteConsentScopes() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String scopes : List.of("catalog:read", "catalog:read cart:read")) {
                var denied = http.send(HttpRequest.newBuilder(URI.create(McpTestSupport.baseUrl(application) + "/mcp"))
                        .header("Authorization", "Bearer " + token(scopes))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, denied.statusCode());
                assertTrue(denied.body().contains("INSUFFICIENT_SCOPE"));
                String challenge = denied.headers().firstValue("WWW-Authenticate").orElseThrow();
                assertTrue(challenge.contains("scope=\"cart:read cart:write catalog:read\""));
                assertFalse(challenge.contains("purchase:handoff"));
            }
        }
    }

    @Test
    void discoveryAndUnauthenticatedRequest_advertiseCanonicalHttpsResource() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            String base = McpTestSupport.baseUrl(application);
            var discovery = http.send(HttpRequest.newBuilder(URI.create(base
                    + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, discovery.statusCode());
            assertTrue(discovery.body().contains(RESOURCE));
            assertTrue(discovery.body().contains("purchase:handoff"));
            var denied = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow()
                    .contains("scope=\"cart:read cart:write catalog:read\""));
            assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow()
                    .contains("https://mcp.example/.well-known/oauth-protected-resource/mcp"));
        }
    }

    private static String token(String scopes) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("https://core.example").audience(RESOURCE).subject("test-user")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                        .claim("connection_id", "test-connection").claim("scope", scopes).build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static void reply(HttpExchange exchange, String json) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
