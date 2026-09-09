package com.copenhagen.contract.internalapi.fraud;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class FraudFixtures {

    static final Instant CREATED_AT = Instant.parse("2026-09-09T01:00:00Z");

    private FraudFixtures() {
    }

    static PurchaseIntent intent() {
        return new PurchaseIntent(
                "intent-test", "user-test", List.of("keyboard"), "electronics", null,
                1, 100000L, "KRW", "office", List.of("merchant-test"),
                List.of("wireless"), CREATED_AT, CREATED_AT.plusSeconds(300));
    }

    static IntentAnalysisResult analysis() {
        return new IntentAnalysisResult(
                true, RiskLevel.LOW, List.of(), List.of(), List.of("INTENT_MATCH"), "요청과 일치");
    }

    static SiftFraudResult sift() {
        return new SiftFraudResult(
                true, new BigDecimal("0.15"), RiskLevel.LOW, List.of("KNOWN_USER"),
                null, "reference-test", 120L);
    }

    static SiftFraudResult unavailableSift() {
        return new SiftFraudResult(false, null, null, List.of("SIFT_UNAVAILABLE"), null, null, null);
    }

    static RuleResult rule() {
        return new RuleResult("AMOUNT_LIMIT", true, RuleSeverity.CRITICAL, "승인 금액 이내");
    }
}
