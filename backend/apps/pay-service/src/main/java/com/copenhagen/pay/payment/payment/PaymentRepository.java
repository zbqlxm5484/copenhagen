package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;

public interface PaymentRepository {

    Optional<PaymentStatus> findStatus(String paymentId);

    Optional<String> findAuthorizationSessionId(String paymentId);

    boolean existsLiveByPurchaseRequest(String merchantAccountId, String purchaseRequestId);

    boolean transitionStatus(String paymentId, PaymentStatus expectedStatus, PaymentStatus targetStatus);
}
