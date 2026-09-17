package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class JdbcNotificationEventPublisher implements NotificationEventPublisher {

    private final NotificationOutboxRepository repository;
    private final Clock clock;

    public JdbcNotificationEventPublisher(
            NotificationOutboxRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void publishApprovalRequested(AuthorizationSession session) {
        repository.insertApprovalRequested(session.id(), session.userId(), clock.instant());
    }

    @Override
    public void publishPaymentResult(
            String paymentId,
            String authorizationSessionId,
            PaymentStatus status) {
        repository.insertPaymentResult(
                paymentId,
                authorizationSessionId,
                status,
                clock.instant());
    }
}
