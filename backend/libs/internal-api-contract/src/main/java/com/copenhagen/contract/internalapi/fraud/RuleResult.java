package com.copenhagen.contract.internalapi.fraud;

import java.util.Objects;

/** Result data only; this record does not execute a rule. */
public record RuleResult(
        String ruleId,
        Boolean passed,
        RuleSeverity severity,
        String reason) {

    public RuleResult {
        FraudSchemaValidation.requiredText(ruleId, "ruleId");
        Objects.requireNonNull(passed, "passed");
        Objects.requireNonNull(severity, "severity");
        FraudSchemaValidation.requiredText(reason, "reason");
    }
}
