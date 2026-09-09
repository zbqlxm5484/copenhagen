package com.copenhagen.contract.internalapi.fraud;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral snapshot of Sift signals. score uses the API scale [0, 1],
 * not the console scale [0, 100]. riskLevel is nullable until local policy maps the score.
 * An unavailable result has no score, risk level or workflow decision.
 * latencyMs is nullable when no request was measured; rawReferenceId is a reference, not raw JSON.
 */
public record SiftFraudResult(
        Boolean available,
        BigDecimal score,
        RiskLevel riskLevel,
        List<String> reasons,
        String workflowDecision,
        String rawReferenceId,
        Long latencyMs) {

    public SiftFraudResult {
        Objects.requireNonNull(available, "available");
        reasons = FraudSchemaValidation.texts(reasons, "reasons");
        FraudSchemaValidation.optionalText(workflowDecision, "workflowDecision");
        FraudSchemaValidation.optionalText(rawReferenceId, "rawReferenceId");
        if (available && score == null) {
            throw new IllegalArgumentException("score is required when Sift is available");
        }
        if (!available && (score != null || riskLevel != null || workflowDecision != null)) {
            throw new IllegalArgumentException("unavailable Sift results cannot contain risk signals");
        }
        if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        if (latencyMs != null && latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}
