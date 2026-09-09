package com.copenhagen.contract.internalapi.fraud;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Shared evaluation result, not Pay's executable authorization or persistence model.
 * intentAnalysis may be absent; Sift absence must be explicit via available=false.
 * Policy aggregation, missing-evidence handling and approval binding belong to the future engines.
 */
public record FraudDecision(
        FraudDecisionType decision,
        RiskLevel riskLevel,
        SiftFraudResult sift,
        IntentAnalysisResult intentAnalysis,
        List<RuleResult> rules,
        List<String> reasonCodes,
        String humanReadableReason,
        Instant evaluatedAt,
        String traceId) {

    public FraudDecision {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(sift, "sift");
        rules = rules == null ? List.of() : List.copyOf(rules);
        reasonCodes = FraudSchemaValidation.texts(reasonCodes, "reasonCodes");
        FraudSchemaValidation.requiredText(humanReadableReason, "humanReadableReason");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        FraudSchemaValidation.requiredText(traceId, "traceId");
    }
}
