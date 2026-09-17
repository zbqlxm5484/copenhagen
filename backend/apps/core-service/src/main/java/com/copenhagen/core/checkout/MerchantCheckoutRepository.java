package com.copenhagen.core.checkout;

import java.time.Instant;
import java.util.List;

public interface MerchantCheckoutRepository {

    /**
     * 한 구매 요청의 merchant 주문서와 승인 대상 스냅샷을 한 트랜잭션으로 넣는다. 일부만 남으면 승인
     * 대상이 반쪽이 되므로 merchant가 여러 곳이어도 전부 성공하거나 전부 실패해야 한다.
     */
    void insertAll(List<MerchantCheckoutRecord> records, Instant now);
}
