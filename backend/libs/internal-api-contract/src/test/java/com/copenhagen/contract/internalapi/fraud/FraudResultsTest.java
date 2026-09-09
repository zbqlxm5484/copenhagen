package com.copenhagen.contract.internalapi.fraud;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FraudResultsTest {

    @Test
    void construct_analysisAndSiftLists_takesDefensiveCopies() {
        List<String> reasons = new ArrayList<>(List.of("MISMATCH"));
        IntentAnalysisResult analysis = new IntentAnalysisResult(
                false, RiskLevel.HIGH, reasons, reasons, reasons, "상품 불일치");
        SiftFraudResult sift = new SiftFraudResult(
                true, BigDecimal.ONE, RiskLevel.CRITICAL, reasons, "custom-workflow", "reference-test", 0L);
        reasons.clear();

        assertEquals(List.of("MISMATCH"), analysis.violations());
        assertEquals(List.of("MISMATCH"), analysis.warnings());
        assertEquals(List.of("MISMATCH"), analysis.reasonCodes());
        assertEquals(List.of("MISMATCH"), sift.reasons());
        assertThrows(UnsupportedOperationException.class, () -> analysis.violations().clear());
        assertThrows(UnsupportedOperationException.class, () -> analysis.warnings().clear());
        assertThrows(UnsupportedOperationException.class, () -> analysis.reasonCodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> sift.reasons().clear());
    }

    @Test
    void construct_incompleteAnalysis_rejectsInventedDefaults() {
        assertThrows(NullPointerException.class, () -> new IntentAnalysisResult(
                null, RiskLevel.LOW, null, null, null, "summary"));
        assertThrows(NullPointerException.class, () -> new IntentAnalysisResult(
                true, null, null, null, null, "summary"));
        assertThrows(IllegalArgumentException.class, () -> new IntentAnalysisResult(
                true, RiskLevel.LOW, null, null, null, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.5", "1"})
    void construct_validSiftScore_preservesApiScale(String score) {
        BigDecimal value = new BigDecimal(score);
        SiftFraudResult result = new SiftFraudResult(true, value, null, null, null, null, 1L);
        assertEquals(value, result.score());
        assertNull(result.riskLevel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "50"})
    void construct_invalidSiftScore_rejectsInput(String score) {
        assertThrows(IllegalArgumentException.class, () -> new SiftFraudResult(
                true, new BigDecimal(score), null, null, null, null, 1L));
    }

    @Test
    void construct_unavailableSift_doesNotBecomeZeroRisk() {
        SiftFraudResult result = FraudFixtures.unavailableSift();
        assertFalse(result.available());
        assertNull(result.score());
        assertNull(result.riskLevel());
        assertNull(result.workflowDecision());
        assertNull(result.latencyMs());
        assertEquals(List.of("SIFT_UNAVAILABLE"), result.reasons());
    }

    @Test
    void construct_inconsistentSiftAvailability_rejectsInput() {
        assertThrows(NullPointerException.class, () -> new SiftFraudResult(
                null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SiftFraudResult(
                true, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SiftFraudResult(
                false, BigDecimal.ZERO, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SiftFraudResult(
                false, null, RiskLevel.LOW, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SiftFraudResult(
                false, null, null, null, "accept", null, null));
    }

    @Test
    void construct_negativeLatency_rejectsInput() {
        assertThrows(IllegalArgumentException.class, () -> new SiftFraudResult(
                false, null, null, null, null, null, -1L));
    }

    @Test
    void construct_ruleResult_preservesPassedAndSeveritySeparately() {
        RuleResult passedRule = FraudFixtures.rule();
        RuleResult failedRule = new RuleResult(
                passedRule.ruleId(), false, passedRule.severity(), "승인 금액 초과");
        assertFalse(failedRule.passed());
        assertEquals(RuleSeverity.CRITICAL, failedRule.severity());
        assertThrows(IllegalArgumentException.class, () -> new RuleResult("", true, RuleSeverity.LOW, "reason"));
        assertThrows(NullPointerException.class, () -> new RuleResult("rule", null, RuleSeverity.LOW, "reason"));
        assertThrows(NullPointerException.class, () -> new RuleResult("rule", true, null, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new RuleResult("rule", true, RuleSeverity.LOW, ""));
    }
}
