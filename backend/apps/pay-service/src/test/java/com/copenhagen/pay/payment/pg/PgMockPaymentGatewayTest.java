package com.copenhagen.pay.payment.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeRequest;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class PgMockPaymentGatewayTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void charge_successMapsMockResponse() {
        registerResponse(
                200,
                "{\"status\":\"SUCCESS\",\"pgTransactionId\":\"pg-tx-1\","
                        + "\"responseCode\":\"APPROVED\"}");

        PaymentGateway gateway = gateway(1_000);

        PaymentGateway.ChargeResult result = gateway.charge(request());

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals("pg-tx-1", result.pgTransactionId());
        assertEquals("APPROVED", result.responseCode());
    }

    @Test
    void charge_failedMapsDeclineResponse() {
        registerResponse(
                200,
                "{\"status\":\"FAILED\",\"pgTransactionId\":null,"
                        + "\"responseCode\":\"DECLINED\"}");

        PaymentGateway gateway = gateway(1_000);

        PaymentGateway.ChargeResult result = gateway.charge(request());

        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("DECLINED", result.responseCode());
    }

    @Test
    void charge_timeoutMapsToUnknownWithoutRetry() {
        server.createContext("/internal/v1/charges", exchange -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        PaymentGateway gateway = gateway(30);

        PaymentGateway.ChargeResult result = gateway.charge(request());

        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals("PG_TIMEOUT", result.responseCode());
    }

    @Test
    void charge_serverErrorMapsToUnknown() {
        registerResponse(503, "service unavailable");

        PaymentGateway gateway = gateway(1_000);

        PaymentGateway.ChargeResult result = gateway.charge(request());

        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals("PG_HTTP_503", result.responseCode());
    }

    @Test
    void lookup_usesGetAndPrefersTransactionId() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/internal/v1/charges", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"status\":\"SUCCESS\",\"pgTransactionId\":\"tx /+1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        PaymentGateway.StatusResult result = gateway(1_000).lookup(
                new PaymentGateway.StatusRequest("tx /+1", "request-1"));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals("GET", method.get());
        assertEquals("pgTransactionId=tx+%2F%2B1", query.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 500, 503})
    void lookup_httpErrorsNeverMeanDefinitiveFailure(int status) {
        registerResponse(status, "sensitive upstream error");

        PaymentGateway.StatusResult result = gateway(1_000).lookup(
                new PaymentGateway.StatusRequest(null, "request-1"));

        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals("PG_LOOKUP_HTTP_" + status, result.responseCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "bad-json", "{\"status\":\"SUCCESS\"}",
            "{\"status\":\"NEW_STATUS\"}"})
    void lookup_invalidResultsStayUnknown(String body) {
        registerResponse(200, body);
        assertEquals(Outcome.UNKNOWN, gateway(1_000).lookup(
                new PaymentGateway.StatusRequest(null, "request-1")).outcome());
    }

    @Test
    void lookup_mapsDefinitiveDeclineWithoutPersistingUpstreamMessage() {
        registerResponse(200, "{\"status\":\"FAILED\",\"responseCode\":\"sensitive upstream text\"}");
        PaymentGateway.StatusResult result = gateway(1_000).lookup(
                new PaymentGateway.StatusRequest(null, "request-1"));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("PG_DECLINED", result.responseCode());
    }

    private void registerResponse(int status, String responseBody) {
        server.createContext("/internal/v1/charges", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
    }

    private PaymentGateway gateway(long requestTimeoutMillis) {
        PgProperties properties = new PgProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1_000,
                requestTimeoutMillis);
        return new PgMockPaymentGateway(properties, new ObjectMapper());
    }

    private ChargeRequest request() {
        return new ChargeRequest(
                "payment-1",
                "pg-charge-1",
                "provider-token-1",
                87_000,
                "KRW");
    }
}
