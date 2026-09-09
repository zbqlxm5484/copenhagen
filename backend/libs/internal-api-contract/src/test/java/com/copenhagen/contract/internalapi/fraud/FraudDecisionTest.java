package com.copenhagen.contract.internalapi.fraud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FraudDecisionTest {

    @Test
    void construct_combinedEvidence_keepsImmutableResults() {
        IntentAnalysisResult analysis = FraudFixtures.analysis();
        SiftFraudResult sift = FraudFixtures.sift();
        List<RuleResult> rules = new ArrayList<>(List.of(FraudFixtures.rule()));
        List<String> reasons = new ArrayList<>(List.of("CHECKS_COMPLETED"));
        FraudDecision result = new FraudDecision(
                FraudDecisionType.ALLOW, RiskLevel.LOW, sift, analysis, rules, reasons,
                "검증 완료", FraudFixtures.CREATED_AT, "trace-test");

        rules.clear();
        reasons.clear();

        assertSame(analysis, result.intentAnalysis());
        assertSame(sift, result.sift());
        assertEquals(List.of(FraudFixtures.rule()), result.rules());
        assertEquals(List.of("CHECKS_COMPLETED"), result.reasonCodes());
        assertThrows(UnsupportedOperationException.class, () -> result.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.reasonCodes().clear());
    }

    @Test
    void construct_missingAnalysis_preservesExplicitAbsence() {
        FraudDecision result = new FraudDecision(
                FraudDecisionType.DENY, RiskLevel.HIGH, FraudFixtures.unavailableSift(), null,
                null, List.of("EVIDENCE_MISSING"), "평가 증거 없음", FraudFixtures.CREATED_AT, "trace-test");
        assertNull(result.intentAnalysis());
        assertEquals(List.of(), result.rules());
    }

    @Test
    void construct_missingDecisionOrEvidence_rejectsInput() {
        assertThrows(NullPointerException.class, () -> decision(null, RiskLevel.LOW, FraudFixtures.sift()));
        assertThrows(NullPointerException.class, () -> decision(FraudDecisionType.ALLOW, null, FraudFixtures.sift()));
        assertThrows(NullPointerException.class, () -> decision(FraudDecisionType.ALLOW, RiskLevel.LOW, null));
    }

    @Test
    void construct_missingAuditIdentity_rejectsInput() {
        assertThrows(IllegalArgumentException.class, () -> new FraudDecision(
                FraudDecisionType.DENY, RiskLevel.HIGH, FraudFixtures.unavailableSift(), null, null,
                null, "reason", FraudFixtures.CREATED_AT, ""));
        assertThrows(NullPointerException.class, () -> new FraudDecision(
                FraudDecisionType.DENY, RiskLevel.HIGH, FraudFixtures.unavailableSift(), null, null,
                null, "reason", null, "trace-test"));
        assertThrows(IllegalArgumentException.class, () -> new FraudDecision(
                FraudDecisionType.DENY, RiskLevel.HIGH, FraudFixtures.unavailableSift(), null, null,
                null, "", FraudFixtures.CREATED_AT, "trace-test"));
    }

    @Test
    void construct_nullRuleEntry_rejectsInput() {
        assertThrows(NullPointerException.class, () -> new FraudDecision(
                FraudDecisionType.DENY, RiskLevel.HIGH, FraudFixtures.unavailableSift(), null,
                Arrays.asList(FraudFixtures.rule(), null), null, "reason", FraudFixtures.CREATED_AT, "trace-test"));
    }

    private FraudDecision decision(FraudDecisionType type, RiskLevel level, SiftFraudResult sift) {
        return new FraudDecision(type, level, sift, null, null, null, "reason", FraudFixtures.CREATED_AT, "trace-test");
    }
}
