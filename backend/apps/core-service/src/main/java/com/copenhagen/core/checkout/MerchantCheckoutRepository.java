package com.copenhagen.core.checkout;

import java.time.Instant;
import java.util.List;

public interface MerchantCheckoutRepository {

    /**
     * 한 구매 요청의 merchant 주문서와 승인 대상 스냅샷을 한 트랜잭션으로 넣는다. 일부만 남으면 승인
     * 대상이 반쪽이 되므로 merchant가 여러 곳이어도 전부 성공하거나 전부 실패해야 한다.
     *
     * @return 생성된 {@code merchant_checkouts.id} 목록. {@code records}와 같은 순서다(이슈 410 — 승인
     *     세션 생성을 이 id로 잇는다).
     */
    List<Long> insertAll(List<MerchantCheckoutRecord> records, Instant now);

    /** 승인 세션 생성 성공. {@code CREATING -> AWAITING_APPROVAL}. */
    void markAwaitingApproval(long id, String paymentApprovalId, Instant paymentApprovalExpiresAt, Instant now);

    /** 폴링 대상. {@code AWAITING_APPROVAL} 행만, 승인 대상 스냅샷 금액·통화까지 한 번에 읽는다. */
    List<AwaitingApprovalCheckout> findAwaitingApproval();

    /**
     * 조건부 전이 {@code AWAITING_APPROVAL -> COMPLETING}. 워커 여러 인스턴스가 같은 행을 동시에 집어도
     * 하나만 true를 받는다(동시성 방어).
     */
    boolean beginCompleting(long id, Instant now);

    /** Merchant Complete 응답이 {@code completed}. */
    void markCompleted(long id, Instant now);

    /** Pay 승인이 거부됐다. */
    void markCancelled(long id, String failureCode, Instant now);

    /** Pay 승인이 만료·무효화됐거나 승인 세션 만료 시각이 지났다. */
    void markExpired(long id, Instant now);

    /** Merchant Complete 응답이 {@code ready_for_complete}(결제 실패로 되돌림) 또는 그 밖의 처리 실패. */
    void markFailed(long id, String failureCode, Instant now);

    /**
     * 폴링·Complete 호출에 필요한 최소 정보. {@code checkout_snapshots}와 {@code purchase_requests}를
     * 조인해 한 번에 읽는다.
     */
    record AwaitingApprovalCheckout(
            long id,
            long purchaseRequestId,
            long userId,
            long merchantRefId,
            String merchantCheckoutId,
            String paymentApprovalId,
            Instant paymentApprovalExpiresAt,
            long checkoutSnapshotId,
            long totalAmountMinor,
            String currency) {
    }
}
