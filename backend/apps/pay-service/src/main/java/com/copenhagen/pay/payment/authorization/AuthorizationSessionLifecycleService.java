package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PayStateTransitionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationSessionLifecycleService {

    private final AuthorizationSessionRepository repository;

    public AuthorizationSessionLifecycleService(AuthorizationSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void completeAfterPaymentSuccess(String authorizationSessionId) {
        transition(
                authorizationSessionId,
                AuthorizationSessionStatus.APPROVED,
                AuthorizationSessionStatus.COMPLETED);
    }

    @Transactional
    public void invalidateAfterDefinitivePaymentFailure(String authorizationSessionId) {
        transition(
                authorizationSessionId,
                AuthorizationSessionStatus.APPROVED,
                AuthorizationSessionStatus.INVALIDATED);
    }

    @Transactional
    public void expire(String authorizationSessionId) {
        AuthorizationSessionStatus currentStatus = findRequiredStatus(authorizationSessionId);
        if (currentStatus == AuthorizationSessionStatus.EXPIRED
                || currentStatus.isTerminal()) {
            return;
        }
        transition(authorizationSessionId, currentStatus, AuthorizationSessionStatus.EXPIRED);
    }

    private void transition(
            String authorizationSessionId,
            AuthorizationSessionStatus expectedStatus,
            AuthorizationSessionStatus targetStatus) {
        if (!PayStateTransitionPolicy.canTransition(expectedStatus, targetStatus)) {
            throw stateConflict(expectedStatus);
        }
        if (!repository.transitionStatus(authorizationSessionId, expectedStatus, targetStatus)) {
            throw stateConflict(findRequiredStatus(authorizationSessionId));
        }
    }

    private AuthorizationSessionStatus findRequiredStatus(String authorizationSessionId) {
        return repository.findStatus(authorizationSessionId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private PayException stateConflict(AuthorizationSessionStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }
}
