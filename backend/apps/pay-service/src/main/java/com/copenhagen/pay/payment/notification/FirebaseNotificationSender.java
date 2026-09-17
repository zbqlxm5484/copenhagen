package com.copenhagen.pay.payment.notification;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class FirebaseNotificationSender implements NotificationSender {

    private final ObjectProvider<FirebaseMessaging> firebaseMessaging;
    private final PayNotificationProperties properties;

    public FirebaseNotificationSender(
            ObjectProvider<FirebaseMessaging> firebaseMessaging,
            PayNotificationProperties properties) {
        this.firebaseMessaging = firebaseMessaging;
        this.properties = properties;
    }

    @Override
    public void send(String fid, NotificationMessage message) {
        if (!properties.fcm().enabled()) {
            throw new NotificationDeliveryException("FCM_DISABLED", false, null);
        }
        FirebaseMessaging messaging = firebaseMessaging.getIfAvailable();
        if (messaging == null) {
            throw new NotificationDeliveryException("FCM_NOT_INITIALIZED", false, null);
        }
        Message.Builder builder = Message.builder()
                .setFid(fid)
                .putAllData(data(message));
        try {
            messaging.send(builder.build());
        } catch (FirebaseMessagingException exception) {
            boolean invalidInstallation = exception.getMessagingErrorCode()
                    == MessagingErrorCode.UNREGISTERED;
            throw new NotificationDeliveryException(
                    invalidInstallation ? "FCM_FID_UNREGISTERED" : "FCM_SEND_FAILED",
                    invalidInstallation,
                    exception);
        }
    }

    private Map<String, String> data(NotificationMessage message) {
        return Map.of(
                "eventId", message.eventId(),
                "type", message.type().name(),
                "approvalId", message.approvalId(),
                "paymentId", valueOrEmpty(message.paymentId()),
                "status", valueOrEmpty(message.status()),
                "nextAction", message.nextAction());
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
