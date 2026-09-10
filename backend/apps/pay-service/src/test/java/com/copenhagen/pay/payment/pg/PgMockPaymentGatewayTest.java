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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
