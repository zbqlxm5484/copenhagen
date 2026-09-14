package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 체크아웃 저장 경계. 구현은 {@link JdbcCheckoutRepository}. 테스트는 인메모리 구현으로 돌린다.
 */
public interface CheckoutRepository {

    /**
     * 새 행을 넣는다. 같은 id 가 이미 있으면 false. 멱등 재시도가 이 경로로 들어온다(DESIGN.md 8.1절).
     */
    boolean insert(CheckoutSession session);

    Optional<CheckoutSession> findById(String id);

    /**
     * 조건부 UPDATE. {@code WHERE id = ? AND version = ?} 이 맞아야 1행이 바뀐다.
     * 0행이면 사이에 다른 요청이 바꿨거나 사라진 것이다. 성공하면 version 이 1 오른다.
     */
    boolean update(CheckoutSession session, long expectedVersion);

    /**
     * 취소 전이. status 가 취소 가능한 셋 중 하나일 때만 1행이 바뀐다(DESIGN.md 7.1절).
     *
     * <p>version 이 아니라 status 자체가 조건이다. 사이에 Complete 가 집어 갔거나 이미 취소됐으면 false 다.
     */
    boolean cancel(String id);

    /**
     * 만료 스윕. {@code ready_for_complete} 이면서 {@code expires_at} 이 지난 행을 {@code canceled} 로 바꾸고
     * 바뀐 건수를 돌려준다(DESIGN.md 7.1절). 인덱스 {@code ix_checkout_expiry} 를 탄다.
     *
     * @param limit 한 번에 닫는 최대 건수
     */
    int expireReady(OffsetDateTime now, int limit);
}
