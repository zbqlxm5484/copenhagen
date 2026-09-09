package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuthorizationSessionStore {

    Optional<AuthorizationSession> findByMerchantAndIdempotencyKey(
            String merchantAccountId,
            String idempotencyKey);

    Optional<AuthorizationSession> findActiveByMerchantAndPurchaseRequest(
            String merchantAccountId,
            String purchaseRequestId);

    boolean insert(AuthorizationSession session);

    List<AuthorizationSession> findByUserId(
            String userId,
            AuthorizationSessionStatus status);

    Optional<AuthorizationSession> findByIdAndUserId(
            String authorizationSessionId,
            String userId);

    int expireOverdueForUser(String userId, Instant now);

    int expireOverdueByIdAndUser(
            String authorizationSessionId,
            String userId,
            Instant now);
}
