package com.copenhagen.pay.payment.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchService {

    private static final int MAX_DELIVERY_ATTEMPTS = 5;
    private static final String DELIVERY_FAILED = "FCM_DELIVERY_FAILED";

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationInstallationRepository installationRepository;
    private final NotificationSender sender;
    private final Clock clock;
    private final PayNotificationProperties properties;

    public NotificationDispatchService(
            NotificationOutboxRepository outboxRepository,
            NotificationInstallationRepository installationRepository,
            NotificationSender sender,
            Clock clock,
            PayNotificationProperties properties) {
        this.outboxRepository = outboxRepository;
        this.installationRepository = installationRepository;
        this.sender = sender;
        this.clock = clock;
        this.properties = properties;
    }

    public boolean processNext() {
        Instant now = clock.instant();
        Optional<NotificationOutboxEvent> claimed = outboxRepository.claimNext(
                now,
                now.plus(Duration.ofSeconds(properties.worker().leaseDurationSeconds())));
        if (claimed.isEmpty()) {
            return false;
        }

        NotificationOutboxEvent event = claimed.orElseThrow();
        List<String> fids = installationRepository.findActiveFidsByUserId(event.userId());
        outboxRepository.ensureDeliveries(event.eventId(), fids, now);
        outboxRepository.pruneInactiveDeliveries(event.eventId());
        dispatchDeliveries(event);
        completeEvent(event);
        return true;
    }

    private void dispatchDeliveries(NotificationOutboxEvent event) {
        while (!Thread.currentThread().isInterrupted()) {
            Instant now = clock.instant();
            Optional<NotificationDelivery> claimed = outboxRepository.claimNextDelivery(
                    event.eventId(),
                    now,
                    now.plus(Duration.ofSeconds(properties.worker().leaseDurationSeconds())));
            if (claimed.isEmpty()) {
                return;
            }

            NotificationDelivery delivery = claimed.orElseThrow();
            try {
                sender.send(delivery.fid(), toMessage(event));
                if (!outboxRepository.markDeliverySent(delivery, clock.instant())) {
                    return;
                }
            } catch (NotificationDeliveryException exception) {
                handleFailure(event, delivery, exception.errorCode(), exception.invalidInstallation());
            } catch (RuntimeException exception) {
                handleFailure(event, delivery, DELIVERY_FAILED, false);
            }
        }
    }

    private void handleFailure(
            NotificationOutboxEvent event,
            NotificationDelivery delivery,
            String errorCode,
            boolean invalidInstallation) {
        if (invalidInstallation) {
            installationRepository.deactivate(event.userId(), delivery.fid(), clock.instant());
        }
        boolean exhausted = invalidInstallation || delivery.attemptCount() >= MAX_DELIVERY_ATTEMPTS;
        Instant nextRunAt = clock.instant().plusSeconds(properties.worker().retryDelaySeconds());
        outboxRepository.markDeliveryFailed(delivery, nextRunAt, errorCode, exhausted);
    }

    private void completeEvent(NotificationOutboxEvent event) {
        NotificationOutboxRepository.DeliverySummary summary =
                outboxRepository.summarizeDeliveries(event.eventId());
        if (!summary.hasRetryableDeliveries()) {
            outboxRepository.markEventSent(event, clock.instant());
            return;
        }
        Instant nextRunAt = summary.nextRunAt() == null
                ? clock.instant().plusSeconds(properties.worker().retryDelaySeconds())
                : summary.nextRunAt();
        outboxRepository.markEventRetry(event, nextRunAt, DELIVERY_FAILED);
    }

    private NotificationMessage toMessage(NotificationOutboxEvent event) {
        String status = event.paymentStatus() == null ? null : event.paymentStatus().name();
        String nextAction = event.eventType() == NotificationEventType.APPROVAL_REQUESTED
                ? "OPEN_APPROVAL"
                : NotificationMessage.nextAction(event.paymentStatus());
        return new NotificationMessage(
                event.eventId(),
                event.eventType(),
                event.approvalId(),
                event.paymentId(),
                status,
                nextAction);
    }
}
