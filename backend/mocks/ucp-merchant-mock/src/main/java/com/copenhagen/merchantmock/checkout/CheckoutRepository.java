package com.copenhagen.merchantmock.checkout;

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
}
