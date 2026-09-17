package com.copenhagen.contract.internalapi;

import java.time.Instant;

public record PaymentApprovalStatusResponse(
        String approvalId,
        ApprovalStatus approvalStatus,
        String paymentId,
        PaymentStatus paymentStatus,
        long amountMinor,
        String currency,
        String credentialId,
        Instant credentialExpiresAt,
        String paymentLabel,
        PaymentApprovalNextAction nextAction) {
}
