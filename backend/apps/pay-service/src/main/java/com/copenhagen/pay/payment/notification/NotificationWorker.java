package com.copenhagen.pay.payment.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "pay.notification.worker",
        name = "enabled",
        havingValue = "true")
public class NotificationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationDispatchService service;
    private final int batchSize;

    public NotificationWorker(
            NotificationDispatchService service,
            PayNotificationProperties properties) {
        this.service = service;
        this.batchSize = properties.worker().batchSize();
    }

    @Scheduled(
            fixedDelayString = "${pay.notification.worker.poll-delay-millis:5000}",
            initialDelayString = "${pay.notification.worker.poll-delay-millis:5000}")
    public void runBatch() {
        for (int i = 0; i < batchSize; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                if (!service.processNext()) {
                    return;
                }
            } catch (RuntimeException exception) {
                // Delivery state remains leased and is recoverable after lease expiry.
                LOGGER.error("Pay notification processing failed; pending events recover after lease expiry");
            }
        }
    }
}
