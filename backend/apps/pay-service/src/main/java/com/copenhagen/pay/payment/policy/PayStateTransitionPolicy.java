package com.copenhagen.pay.payment.policy;

import java.util.Map;
import java.util.Set;

public final class PayStateTransitionPolicy {

    private static final Map<AuthorizationSessionStatus, Set<AuthorizationSessionStatus>>
            AUTHORIZATION_TRANSITIONS = Map.of(
                    AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED,
                    Set.of(
                            AuthorizationSessionStatus.PENDING,
                            AuthorizationSessionStatus.REJECTED,
                            AuthorizationSessionStatus.EXPIRED,
                            AuthorizationSessionStatus.INVALIDATED),
                    AuthorizationSessionStatus.PENDING,
                    Set.of(
                            AuthorizationSessionStatus.APPROVED,
                            AuthorizationSessionStatus.REJECTED,
                            AuthorizationSessionStatus.EXPIRED,
                            AuthorizationSessionStatus.INVALIDATED),
                    AuthorizationSessionStatus.APPROVED,
                    Set.of(
                            AuthorizationSessionStatus.COMPLETED,
                            AuthorizationSessionStatus.EXPIRED,
                            AuthorizationSessionStatus.INVALIDATED));

    private static final Map<PaymentStatus, Set<PaymentStatus>> PAYMENT_TRANSITIONS = Map.of(
            PaymentStatus.READY,
            Set.of(PaymentStatus.PROCESSING),
            PaymentStatus.PROCESSING,
            Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.UNKNOWN),
            PaymentStatus.UNKNOWN,
            Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED));

    private static final Map<ReconciliationJobStatus, Set<ReconciliationJobStatus>>
            RECONCILIATION_TRANSITIONS = Map.of(
                    ReconciliationJobStatus.PENDING,
                    Set.of(ReconciliationJobStatus.RUNNING, ReconciliationJobStatus.CANCELLED),
                    ReconciliationJobStatus.RUNNING,
                    Set.of(
                            ReconciliationJobStatus.PENDING,
                            ReconciliationJobStatus.SUCCEEDED,
                            ReconciliationJobStatus.FAILED,
                            ReconciliationJobStatus.EXHAUSTED,
                            ReconciliationJobStatus.CANCELLED),
                    ReconciliationJobStatus.FAILED,
                    Set.of(
                            ReconciliationJobStatus.PENDING,
                            ReconciliationJobStatus.RUNNING,
                            ReconciliationJobStatus.EXHAUSTED));

    private PayStateTransitionPolicy() {
    }

    public static boolean canTransition(
            AuthorizationSessionStatus from,
            AuthorizationSessionStatus to) {
        return AUTHORIZATION_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return PAYMENT_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(
            ReconciliationJobStatus from,
            ReconciliationJobStatus to) {
        return RECONCILIATION_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
