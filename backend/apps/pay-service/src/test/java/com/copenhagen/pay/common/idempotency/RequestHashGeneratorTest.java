package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RequestHashGeneratorTest {

    private final RequestHashGenerator generator = new RequestHashGenerator(new ObjectMapper());

    @Test
    void objectPropertyOrderDoesNotChangeHash() {
        String first = generator.generate(
                "POST",
                "/api/v1/payment-approvals",
                Map.of("status", List.of("PENDING")),
                Map.of("amountMinor", 10000, "currency", "KRW"));
        String second = generator.generate(
                "post",
                "/api/v1/payment-approvals",
                Map.of("status", List.of("PENDING")),
                Map.of("currency", "KRW", "amountMinor", 10000));

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void methodPathQueryOrBodyChangeChangesHash() {
        String first = generator.generate(
                "POST",
                "/api/v1/payment-approvals",
                Map.of(),
                Map.of("amountMinor", 10000));
        String changed = generator.generate(
                "POST",
                "/api/v1/payment-approvals",
                Map.of(),
                Map.of("amountMinor", 20000));

        assertNotEquals(first, changed);
    }
}
