package com.copenhagen.contract.internalapi.fraud;

import java.util.List;
import java.util.Objects;

/**
 * Completed semantic analysis. Missing analysis is represented by a null reference in
 * FraudDecision, never by an invented match or LOW risk.
 */
public record IntentAnalysisResult(
        Boolean intentMatch,
        RiskLevel semanticRisk,
        List<String> violations,
        List<String> warnings,
        List<String> reasonCodes,
        String summary) {

    public IntentAnalysisResult {
        Objects.requireNonNull(intentMatch, "intentMatch");
        Objects.requireNonNull(semanticRisk, "semanticRisk");
        violations = FraudSchemaValidation.texts(violations, "violations");
        warnings = FraudSchemaValidation.texts(warnings, "warnings");
        reasonCodes = FraudSchemaValidation.texts(reasonCodes, "reasonCodes");
        FraudSchemaValidation.requiredText(summary, "summary");
    }
}
