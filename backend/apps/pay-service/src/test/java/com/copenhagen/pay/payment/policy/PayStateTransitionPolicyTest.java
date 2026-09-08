package com.copenhagen.pay.payment.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PayStateTransitionPolicyTest {

    @Test
    void approvedSessionCompletesOnlyAfterSuccessfulPayment() {
        assertTrue(PayStateTransitionPolicy.canTransition(
                AuthorizationSessionStatus.APPROVED,
                AuthorizationSessionStatus.COMPLETED));
        assertTrue(PayStateTransitionPolicy.canTransition(
                AuthorizationSessionStatus.APPROVED,
                AuthorizationSessionStatus.INVALIDATED));
        assertFalse(PayStateTransitionPolicy.canTransition(
                AuthorizationSessionStatus.COMPLETED,
                AuthorizationSessionStatus.APPROVED));
    }

    @Test
    void unknownPaymentCanOnlyBeResolved() {
        assertTrue(PayStateTransitionPolicy.canTransition(
                PaymentStatus.PROCESSING,
                PaymentStatus.UNKNOWN));
        assertTrue(PayStateTransitionPolicy.canTransition(
                PaymentStatus.UNKNOWN,
                PaymentStatus.SUCCESS));
        assertTrue(PayStateTransitionPolicy.canTransition(
                PaymentStatus.UNKNOWN,
                PaymentStatus.FAILED));
        assertFalse(PayStateTransitionPolicy.canTransition(
                PaymentStatus.UNKNOWN,
                PaymentStatus.PROCESSING));
    }

    @Test
    void reconciliationLeaseCanReturnRunningJobToPending() {
        assertTrue(PayStateTransitionPolicy.canTransition(
                ReconciliationJobStatus.RUNNING,
                ReconciliationJobStatus.PENDING));
        assertTrue(PayStateTransitionPolicy.canTransition(
                ReconciliationJobStatus.FAILED,
                ReconciliationJobStatus.RUNNING));
        assertTrue(PayStateTransitionPolicy.canTransition(
                ReconciliationJobStatus.FAILED,
                ReconciliationJobStatus.EXHAUSTED));
        assertFalse(PayStateTransitionPolicy.canTransition(
                ReconciliationJobStatus.EXHAUSTED,
                ReconciliationJobStatus.PENDING));
    }
}
