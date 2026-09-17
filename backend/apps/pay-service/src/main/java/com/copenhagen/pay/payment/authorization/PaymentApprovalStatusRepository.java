package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Instant;
import java.util.Optional;

public interface PaymentApprovalStatusRepository {

    Optional<PaymentApprovalStatusView> findByApprovalId(String approvalId);

    record PaymentApprovalStatusView(
            String approvalId,
            AuthorizationSessionStatus approvalStatus,
            String paymentId,
            PaymentStatus paymentStatus,
            long amountMinor,
            String currency,
            String credentialId,
            Instant credentialExpiresAt) {
    }
}
