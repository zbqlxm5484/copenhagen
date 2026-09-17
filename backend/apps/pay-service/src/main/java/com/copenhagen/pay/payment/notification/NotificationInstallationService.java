package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationInstallationService {

    private static final int MAX_FID_LENGTH = 255;

    private final NotificationInstallationRepository repository;
    private final Clock clock;

    public NotificationInstallationService(
            NotificationInstallationRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void register(String userId, String fid) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(fid, "fid", MAX_FID_LENGTH);
        repository.registerActive(userId, fid, clock.instant());
    }

    @Transactional
    public void unregister(String userId, String fid) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(fid, "fid", MAX_FID_LENGTH);
        // The user predicate makes this privacy-safe for another user's FID.
        int deactivated = repository.deactivate(userId, fid, clock.instant());
        if (deactivated == 0 && repository.existsActiveForAnotherUser(userId, fid)) {
            throw new PayException(PayErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
