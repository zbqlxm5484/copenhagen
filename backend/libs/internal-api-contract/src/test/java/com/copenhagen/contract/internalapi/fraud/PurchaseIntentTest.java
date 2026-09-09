package com.copenhagen.contract.internalapi.fraud;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseIntentTest {

    @Test
    void construct_unspecifiedConstraints_preservesAbsence() {
        PurchaseIntent intent = new PurchaseIntent(
                "intent-test", "user-test", null, null, null, null, null, null,
                null, null, null, FraudFixtures.CREATED_AT, null);

        assertNull(intent.quantity());
        assertNull(intent.maxTotalAmount());
        assertNull(intent.currency());
        assertNull(intent.expiresAt());
        assertTrue(intent.productKeywords().isEmpty());
        assertTrue(intent.allowedMerchants().isEmpty());
        assertTrue(intent.constraints().isEmpty());
    }

    @Test
    void construct_mutableLists_takesDefensiveCopies() {
        List<String> keywords = new ArrayList<>(List.of("keyboard"));
        List<String> merchants = new ArrayList<>(List.of("merchant-test"));
        List<String> constraints = new ArrayList<>(List.of("wireless"));
        PurchaseIntent intent = new PurchaseIntent(
                "intent-test", "user-test", keywords, null, null, 1, 100000L, "KRW",
                null, merchants, constraints, FraudFixtures.CREATED_AT, null);

        keywords.clear();
        merchants.clear();
        constraints.clear();

        assertEquals(List.of("keyboard"), intent.productKeywords());
        assertEquals(List.of("merchant-test"), intent.allowedMerchants());
        assertEquals(List.of("wireless"), intent.constraints());
        assertThrows(UnsupportedOperationException.class, () -> intent.productKeywords().add("mouse"));
        assertThrows(UnsupportedOperationException.class, () -> intent.allowedMerchants().clear());
        assertThrows(UnsupportedOperationException.class, () -> intent.constraints().clear());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void construct_nonPositiveQuantity_rejectsInput(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> intent(quantity, 100L, "KRW", null));
    }

    @Test
    void construct_amountBoundaries_preservesMinorUnits() {
        assertEquals(0L, intent(1, 0L, "KRW", null).maxTotalAmount());
        assertEquals(Long.MAX_VALUE, intent(1, Long.MAX_VALUE, "USD", null).maxTotalAmount());
        assertThrows(IllegalArgumentException.class, () -> intent(1, -1L, "KRW", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"krw", "ZZZ", "KR", "", " "})
    void construct_invalidCurrency_rejectsInput(String currency) {
        assertThrows(IllegalArgumentException.class, () -> intent(1, 100L, currency, null));
    }

    @Test
    void construct_amountWithoutCurrency_rejectsInput() {
        assertThrows(IllegalArgumentException.class, () -> intent(1, 100L, null, null));
    }

    @Test
    void construct_expiryBoundary_requiresStrictlyLaterExpiry() {
        Instant createdAt = FraudFixtures.CREATED_AT;
        assertThrows(IllegalArgumentException.class, () -> intent(1, 100L, "KRW", createdAt));
        assertThrows(IllegalArgumentException.class, () -> intent(1, 100L, "KRW", createdAt.minusNanos(1)));
        assertEquals(createdAt.plusNanos(1), intent(1, 100L, "KRW", createdAt.plusNanos(1)).expiresAt());
    }

    @Test
    void construct_missingIdentityOrCreationTime_rejectsInput() {
        assertThrows(IllegalArgumentException.class, () -> new PurchaseIntent(
                " ", "user-test", null, null, null, null, null, null,
                null, null, null, FraudFixtures.CREATED_AT, null));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseIntent(
                "intent-test", null, null, null, null, null, null, null,
                null, null, null, FraudFixtures.CREATED_AT, null));
        assertThrows(NullPointerException.class, () -> new PurchaseIntent(
                "intent-test", "user-test", null, null, null, null, null, null,
                null, null, null, null, null));
    }

    @Test
    void construct_blankConstraint_rejectsInput() {
        assertThrows(IllegalArgumentException.class, () -> new PurchaseIntent(
                "intent-test", "user-test", List.of(""), null, null, null, null, null,
                null, null, null, FraudFixtures.CREATED_AT, null));
    }

    private PurchaseIntent intent(Integer quantity, Long amount, String currency, Instant expiresAt) {
        return new PurchaseIntent(
                "intent-test", "user-test", List.of("keyboard"), null, null, quantity, amount, currency,
                null, List.of(), List.of(), FraudFixtures.CREATED_AT, expiresAt);
    }
}
