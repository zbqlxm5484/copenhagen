package com.copenhagen.pay.payment.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        prefix = "pay.notification.fcm",
        name = "enabled",
        havingValue = "true")
public class FirebaseNotificationConfiguration {

    private static final String APP_NAME = "pay-fcm";

    @Bean(name = "payFirebaseApp", destroyMethod = "")
    public FirebaseApp payFirebaseApp(PayNotificationProperties properties) {
        PayNotificationProperties.Fcm fcm = properties.fcm();
        if (fcm.projectId().isBlank() || fcm.serviceAccountJson().isBlank()) {
            throw new IllegalStateException(
                    "PAY_FCM_PROJECT_ID and PAY_FCM_SERVICE_ACCOUNT_JSON are required");
        }
        try {
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(
                            fcm.serviceAccountJson().getBytes(StandardCharsets.UTF_8)));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(fcm.projectId())
                    .build();
            try {
                return FirebaseApp.getInstance(APP_NAME);
            } catch (IllegalStateException ignored) {
                return FirebaseApp.initializeApp(options, APP_NAME);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Firebase service account configuration is invalid", exception);
        }
    }

    @Bean
    public FirebaseMessaging payFirebaseMessaging(FirebaseApp payFirebaseApp) {
        return FirebaseMessaging.getInstance(payFirebaseApp);
    }
}
