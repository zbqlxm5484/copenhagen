package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.catalog.CatalogSchemas;
import com.copenhagen.mcp.catalog.CoreProductCatalog;
import com.copenhagen.mcp.catalog.CoreProductDetails;
import com.copenhagen.mcp.catalog.DetailSchemas;
import com.copenhagen.mcp.catalog.ProductDetailRequest;
import com.copenhagen.mcp.catalog.ProductSearchRequest;
import com.copenhagen.mcp.core.CoreClient;
import com.copenhagen.mcp.core.CoreProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** loopback HTTP로도 Core 경계를 통과하는 QA 어댑터. /fixture 경로는 실제 Core 계약이 아니다. */
final class QaCoreServer implements AutoCloseable {

    private final HttpServer server;
    private final HttpClient http;
    private final CoreClient client;

    QaCoreServer(QaCatalogStore store) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture/catalog/search", exchange -> search(exchange, store));
        server.createContext("/fixture/catalog/detail", exchange -> search(exchange, store));
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        client = new CoreClient(http, new CoreProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 131072),
                McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator());
    }

    CoreProductCatalog adapter() {
        return (identity, request) -> client.post("/fixture/catalog/search", body(identity, request.arguments()),
                CatalogSchemas.CORE_SEARCH);
    }

    CoreProductDetails detailsAdapter() {
        return (identity, request) -> client.post("/fixture/catalog/detail", body(identity, request.arguments()),
                DetailSchemas.CORE);
    }

    private static Map<String, Object> body(McpIdentity identity, Map<String, Object> arguments) {
        return Map.of("publicUserRef", identity.publicUserRef(), "connectionRef", identity.connectionRef(),
                "arguments", arguments);
    }

    @SuppressWarnings("unchecked")
    private void search(HttpExchange exchange, QaCatalogStore store) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("POST")
                    || !(path.equals("/fixture/catalog/search") || path.equals("/fixture/catalog/detail"))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(8193);
            if (bytes.length > 8192) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            Map<String, Object> input = McpJsonDefaults.getMapper().readValue(bytes, new TypeRef<>() { });
            Map<String, Object> arguments = (Map<String, Object>) input.get("arguments");
            Map<String, Object> data;
            try {
                data = path.endsWith("/search") ? store.search(ProductSearchRequest.parse(arguments,
                        McpJsonDefaults.getSchemaValidator())) : store.detail(ProductDetailRequest.parse(arguments,
                        McpJsonDefaults.getSchemaValidator()));
            } catch (RuntimeException exception) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] result = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of(
                    "publicUserRef", input.get("publicUserRef"), "connectionRef", input.get("connectionRef"),
                    "data", data));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        http.close();
    }
}
