package com.copenhagen.merchantmock.checkout;

import java.util.Optional;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.support.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 체크아웃 생성·조회·수정(Jira 172). 판정은 {@link CheckoutEvaluator}, 저장은 {@link CheckoutRepository}.
 *
 * <p>생성 멱등은 id 로 흡수한다. Idempotency-Key 에서 UUIDv5 를 만들어 id 로 쓰고 INSERT 가 PK 에 걸리면
 * 기존 행을 돌려준다(DESIGN.md 8.1절). 수정은 전체 교체라 본래 멱등이다.
 */
@Service
public class CheckoutService {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);

    private final CheckoutRepository repository;
    private final CheckoutEvaluator evaluator;
    private final ObjectMapper objectMapper;

    public CheckoutService(CheckoutRepository repository, CheckoutEvaluator evaluator, ObjectMapper objectMapper) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
    }

    public Checkout create(CheckoutRequest request, String idempotencyKey) {
        String id = CheckoutIds.fromIdempotencyKey(idempotencyKey);
        Optional<CheckoutSession> existing = repository.findById(id);
        if (existing.isPresent()) {
            LOG.info("체크아웃 생성 재시도. 기존 행 반환. id={}", id);
            return snapshotOf(existing.get());
        }

        CheckoutEvaluator.Evaluation evaluation = evaluator.evaluate(id, request, Optional.empty());
        CheckoutSession session = toSession(id, request, evaluation, 0L);
        if (!repository.insert(session)) {
            LOG.info("체크아웃 생성 경합. 기존 행 반환. id={}", id);
            return repository.findById(id).map(this::snapshotOf)
                    .orElseThrow(() -> ApiException.checkoutNotFound(id));
        }
        LOG.info("체크아웃 생성. id={} status={}", id, evaluation.status());
        return evaluation.checkout();
    }

    public Checkout get(String checkoutId) {
        return repository.findById(checkoutId)
                .map(this::snapshotOf)
                .orElseThrow(() -> ApiException.checkoutNotFound(checkoutId));
    }

    public Checkout update(String checkoutId, CheckoutRequest request) {
        CheckoutSession current = repository.findById(checkoutId)
                .orElseThrow(() -> ApiException.checkoutNotFound(checkoutId));
        if (!current.status().modifiable()) {
            throw ApiException.checkoutNotModifiable(checkoutId, current.status());
        }

        Checkout previous = snapshotOf(current);
        CheckoutEvaluator.Evaluation evaluation = evaluator.evaluate(checkoutId, request, Optional.of(previous));
        CheckoutSession next = toSession(checkoutId, request, evaluation, current.version());
        if (!repository.update(next, current.version())) {
            throw ApiException.checkoutNotModifiable(checkoutId, current.status());
        }
        LOG.info("체크아웃 수정. id={} {} -> {}", checkoutId, current.status(), evaluation.status());
        return evaluation.checkout();
    }

    private CheckoutSession toSession(String id, CheckoutRequest request,
            CheckoutEvaluator.Evaluation evaluation, long version) {
        Checkout checkout = evaluation.checkout();
        long total = checkout.totals().stream()
                .filter(t -> "total".equals(t.type()))
                .mapToLong(CheckoutMessages.Total::amount)
                .findFirst()
                .orElse(0L);
        boolean hasAddress = request != null && request.fulfillment() != null
                && request.fulfillment().destination() != null;
        String address = hasAddress ? objectMapper.writeValueAsString(request.fulfillment().destination()) : null;
        String messages = checkout.messages() == null ? null : objectMapper.writeValueAsString(checkout.messages());
        return new CheckoutSession(
                id,
                evaluation.status(),
                checkout.currency(),
                total,
                address,
                messages,
                objectMapper.writeValueAsString(checkout),
                evaluation.expiresAt(),
                version);
    }

    /** 저장된 스냅샷이 응답이다. 다시 계산하지 않는다. */
    private Checkout snapshotOf(CheckoutSession session) {
        return objectMapper.readValue(session.canonicalCheckout(), Checkout.class);
    }
}
