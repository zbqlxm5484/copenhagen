package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionLifecycleService;
import com.copenhagen.pay.payment.notification.NotificationEventPublisher;
import com.copenhagen.pay.payment.policy.PayStateTransitionPolicy;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentLifecycleService {

    private final PaymentRepository paymentRepository;
    private final AuthorizationSessionLifecycleService authorizationSessionService;
    private NotificationEventPublisher notificationEventPublisher = NotificationEventPublisher.noop();

    public PaymentLifecycleService(
            PaymentRepository paymentRepository,
            AuthorizationSessionLifecycleService authorizationSessionService) {
        this.paymentRepository = paymentRepository;
        this.authorizationSessionService = authorizationSessionService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNotificationEventPublisher(NotificationEventPublisher notificationEventPublisher) {
        if (notificationEventPublisher != null) {
            this.notificationEventPublisher = notificationEventPublisher;
        }
    }

    @Transactional
    public void markProcessing(String paymentId) {
        transition(paymentId, PaymentStatus.READY, PaymentStatus.PROCESSING);
    }

    @Transactional
    public void markUnknown(String paymentId) {
        transition(paymentId, PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN);
        notificationEventPublisher.publishPaymentResult(
                paymentId,
                findAuthorizationSessionId(paymentId),
                PaymentStatus.UNKNOWN);
    }

    @Transactional
    public void resolveSuccess(String paymentId) {
        PaymentStatus currentStatus = findRequiredStatus(paymentId);
        if (currentStatus == PaymentStatus.SUCCESS) {
            return;
        }
        if (!PayStateTransitionPolicy.canTransition(currentStatus, PaymentStatus.SUCCESS)) {
            throw stateConflict(currentStatus);
        }
        if (!paymentRepository.transitionStatus(paymentId, currentStatus, PaymentStatus.SUCCESS)) {
            PaymentStatus actualStatus = findRequiredStatus(paymentId);
            if (actualStatus == PaymentStatus.SUCCESS) {
                return;
            }
            throw stateConflict(actualStatus);
        }
        String authorizationSessionId = findAuthorizationSessionId(paymentId);
        authorizationSessionService.completeAfterPaymentSuccess(authorizationSessionId);
        notificationEventPublisher.publishPaymentResult(
                paymentId,
                authorizationSessionId,
                PaymentStatus.SUCCESS);
    }

    @Transactional
    public void resolveDefinitiveFailure(String paymentId) {
        PaymentStatus currentStatus = findRequiredStatus(paymentId);
        if (currentStatus == PaymentStatus.FAILED) {
            return;
        }
        if (!PayStateTransitionPolicy.canTransition(currentStatus, PaymentStatus.FAILED)) {
            throw stateConflict(currentStatus);
        }
        if (!paymentRepository.transitionStatus(paymentId, currentStatus, PaymentStatus.FAILED)) {
            PaymentStatus actualStatus = findRequiredStatus(paymentId);
            if (actualStatus == PaymentStatus.FAILED) {
                return;
            }
            throw stateConflict(actualStatus);
        }
        String authorizationSessionId = findAuthorizationSessionId(paymentId);
        authorizationSessionService.invalidateAfterDefinitivePaymentFailure(authorizationSessionId);
        notificationEventPublisher.publishPaymentResult(
                paymentId,
                authorizationSessionId,
                PaymentStatus.FAILED);
    }

    private void transition(String paymentId, PaymentStatus expectedStatus, PaymentStatus targetStatus) {
        if (!PayStateTransitionPolicy.canTransition(expectedStatus, targetStatus)) {
            throw stateConflict(expectedStatus);
        }
        if (!paymentRepository.transitionStatus(paymentId, expectedStatus, targetStatus)) {
            throw stateConflict(findRequiredStatus(paymentId));
        }
    }

    private PaymentStatus findRequiredStatus(String paymentId) {
        return paymentRepository.findStatus(paymentId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private String findAuthorizationSessionId(String paymentId) {
        return paymentRepository.findAuthorizationSessionId(paymentId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private PayException stateConflict(PaymentStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }
}
