package com.copenhagen.core.payment;

import java.time.Instant;
import java.util.List;

/**
 * {@code POST /internal/v1/payment-approvals}(pay-service {@code AuthorizationSessionController})
 * 계약. 필드는 그 컨트롤러의 {@code CreateAuthorizationSessionRequest}/{@code ApprovalCreatedResponse}와
 * 1:1로 대응한다(이슈 410, technical-flow.md §5).
 */
public final class PaymentApprovalMessages {

    private PaymentApprovalMessages() {
    }

    public record CreateApprovalRequest(
            String purchaseRequestId,
            String userId,
            String merchantId,
            String checkoutId,
            String checkoutHash,
            String checkoutJwt,
            DisplaySnapshot display,
            long amountMinor,
            String currency) {
    }

    public record DisplaySnapshot(String merchantName, List<SummaryItem> summary) {
    }

    public record SummaryItem(String name, String option, int quantity, long amountMinor) {
    }

    public record ApprovalCreatedResponse(
            String approvalId,
            String status,
            String requiredAction,
            Instant expiresAt,
            String nextAction) {
    }
}
