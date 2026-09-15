package com.copenhagen.contract.internalapi;

public record PaymentApprovalStatusResponse(
        String approvalId,
        ApprovalStatus approvalStatus,
        String paymentId,
        PaymentStatus paymentStatus,
        long amountMinor,
        String currency,
        PaymentApprovalNextAction nextAction) {
}
