package com.copenhagen.pgmock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pgmock.PgMockTokenizationController.ErrorResponse;
import com.copenhagen.pgmock.PgMockTokenizationController.TokenizationRequest;
import com.copenhagen.pgmock.PgMockTokenizationController.TokenizationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PgMockTokenizationControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T00:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void tokenize_withFirstCardPassword_returnsProviderToken() {
        PgMockTokenizationController controller = controller();

        var response = controller.tokenize("tokenize-1", request("1234"));

        assertEquals(200, response.getStatusCode().value());
        TokenizationResponse body = (TokenizationResponse) response.getBody();
        assertNotNull(body);
        assertEquals("mock-pg", body.provider());
        assertTrue(body.providerTokenRef().startsWith("tok_mock_"));
        assertEquals("VISA", body.brand());
        assertEquals("1111", body.last4());
        assertEquals(12, body.expiryMonth());
        assertEquals(2030, body.expiryYear());
    }

    @Test
    void tokenize_withoutCardPassword_isAcceptedForLaterCardRegistration() {
        PgMockTokenizationController controller = controller();

        var response = controller.tokenize("tokenize-2", request(null));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void tokenize_withInvalidCardData_returnsSanitizedError() {
        PgMockTokenizationController controller = controller();
        TokenizationRequest invalid = new TokenizationRequest(
                "4111111111111112",
                12,
                2030,
                "123",
                "1234");

        var response = controller.tokenize("tokenize-3", invalid);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse("INVALID_CARD_DATA", "카드 정보가 올바르지 않습니다."),
                response.getBody());
    }

    @Test
    void tokenize_withInvalidCardPassword_returnsBadRequest() {
        PgMockTokenizationController controller = controller();

        var response = controller.tokenize("tokenize-4", request("123"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void tokenize_withExpiredCard_returnsBadRequest() {
        PgMockTokenizationController controller = controller();
        TokenizationRequest expired = new TokenizationRequest(
                "4111111111111111",
                8,
                2026,
                "123",
                null);

        var response = controller.tokenize("tokenize-5", expired);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void tokenize_sameKeyAndRequest_reusesFirstResult() {
        PgMockTokenizationController controller = controller();
        TokenizationRequest request = request("1234");

        TokenizationResponse first = (TokenizationResponse) controller
                .tokenize("tokenize-6", request)
                .getBody();
        TokenizationResponse replay = (TokenizationResponse) controller
                .tokenize("tokenize-6", request)
                .getBody();

        assertEquals(first, replay);
    }

    @Test
    void tokenize_sameKeyAndDifferentRequest_returnsConflict() {
        PgMockTokenizationController controller = controller();
        controller.tokenize("tokenize-7", request("1234"));

        var response = controller.tokenize(
                "tokenize-7",
                new TokenizationRequest("4242424242424242", 12, 2030, "123", null));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse(
                        "IDEMPOTENCY_CONFLICT",
                        "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다."),
                response.getBody());
    }

    @Test
    void tokenize_withoutIdempotencyKey_returnsBadRequest() {
        var response = controller().tokenize(null, request("1234"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse(
                        "IDEMPOTENCY_KEY_REQUIRED",
                        "유효한 Idempotency-Key가 필요합니다."),
                response.getBody());
    }

    @Test
    void tokenize_concurrentSameRequest_createsOneTokenizationResult() {
        PgMockTokenizationController controller = controller();

        List<TokenizationResponse> responses = IntStream.range(0, 16)
                .parallel()
                .mapToObj(index -> controller.tokenize("tokenize-8", request("1234")))
                .map(response -> (TokenizationResponse) response.getBody())
                .toList();

        Set<TokenizationResponse> uniqueResponses = Set.copyOf(responses);
        assertEquals(1, uniqueResponses.size());
    }

    @Test
    void toString_redactsCardSecretsAndProviderToken() {
        TokenizationRequest request = request("1234");
        TokenizationResponse response = (TokenizationResponse) controller()
                .tokenize("tokenize-9", request)
                .getBody();

        assertFalse(request.toString().contains(request.cardNumber()));
        assertFalse(request.toString().contains(request.cvc()));
        assertFalse(request.toString().contains(request.cardPassword()));
        assertFalse(response.toString().contains(response.providerTokenRef()));
    }

    private PgMockTokenizationController controller() {
        return new PgMockTokenizationController(new PgMockTokenizationService(CLOCK));
    }

    private TokenizationRequest request(String cardPassword) {
        return new TokenizationRequest(
                "4111111111111111",
                12,
                2030,
                "123",
                cardPassword);
    }
}
