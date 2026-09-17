package com.copenhagen.core.order;

import java.time.Instant;
import java.util.UUID;

public interface PurchaseRequestRepository {

    PurchaseRequest insertCreatingCheckouts(
            UUID publicId,
            long userId,
            long cartId,
            String requestChannel,
            String policyVersion,
            Instant now);

    /** 검증이 반려된 구매 요청을 닫는다. 실패한 검증 결과는 재사용하지 않는다(ORDER-03). */
    void markFailed(long id, Instant now);
}
