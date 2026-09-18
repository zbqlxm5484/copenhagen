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
        core.start();
        String base = "http://127.0.0.1:" + core.getAddress().getPort();
        application = new SpringApplicationBuilder(McpServerApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off", "--mcp.integration.enabled=true",
                        "--mcp.ui.enabled=true", "--mcp.integration.app-link-base=https://app.example/purchase/",
                        "--mcp.auth.jwt.enabled=true", "--mcp.auth.jwt.jwks-uri=" + base + "/jwks",
                        "--mcp.auth.issuer=https://core.example", "--mcp.auth.resource=" + RESOURCE,
                        "--mcp.auth.required-scopes=catalog:read", "--services.core.base-url=" + base,
                        "--services.core.auth.enabled=true", "--services.core.auth.token-uri=" + base + "/token",
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
            assertEquals(Set.of("search_products", "get_product_detail", "get_cart", "add_cart_item",
                    "update_cart_item", "delete_cart_item", "prepare_purchase_handoff", "open_copenhagen",
                    "display_products", "get_purchase_status", "resend_purchase_notification"),
                    tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet()));
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("open_copenhagen")
                    .arguments(Map.of("query", "삼성")).build());
            assertFalse(result.isError());
            assertEquals("Bearer " + token, delegated.get());
            assertEquals("Bearer test-service-token", service.get());
            assertFalse(result.structuredContent().toString().contains(token));
            var resources = client.listResources().resources();
            assertEquals(1, resources.size());
            var html = (McpSchema.TextResourceContents) client.readResource(
                    new McpSchema.ReadResourceRequest(resources.getFirst().uri())).contents().getFirst();
            assertTrue(html.text().contains("https://app.example/purchase/"));
            assertFalse(html.text().contains("로컬 Mock 안내 시나리오"));
        }
    }

    @Test
    void insufficientScope_returnsOAuthChallengeWithoutCallingCore() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application), token("catalog:read"))) {
            client.initialize();
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("get_cart")
                    .arguments(Map.of()).build());
            assertTrue(result.isError());
            assertTrue(result.meta().get("mcp/www_authenticate") instanceof List<?>);
            assertTrue(result.meta().toString().contains("cart:read"));
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
