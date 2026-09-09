package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Instant;

public record AuthorizationSession(
        String id,
        String purchaseRequestId,
        String userId,
        String merchantAccountId,
        String merchantId,
        String merchantName,
        String externalCheckoutId,
        String checkoutDigest,
        String displaySnapshotJson,
        long amountMinor,
        String currency,
        String selectedPaymentMethodId,
        AuthorizationSessionStatus status,
        String idempotencyKey,
        String requestHash,
        String requiredAction,
        Instant expiresAt,
        Instant approvedAt,
        String authMethod,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
