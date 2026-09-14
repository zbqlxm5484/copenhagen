package com.copenhagen.merchantmock.checkout;

import java.util.Optional;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.CancelResult;
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

    /**
     * 취소(Jira 182). 이미 취소된 건은 오류가 아니라 재생이다(DESIGN.md 7.1절).
     *
     * <p>멱등 키가 {@code checkout-cancel-{checkoutId}} 로 id 에서 나오므로 같은 키의 재시도가 곧 재취소다.
     * 그것을 409 로 막으면 Core 가 재시도할 방법이 없어진다.
     */
    public CancelResult cancel(String checkoutId) {
        CheckoutSession current = repository.findById(checkoutId)
                .orElseThrow(() -> ApiException.checkoutNotFound(checkoutId));
        if (current.status() == CheckoutStatus.CANCELED) {
            LOG.info("체크아웃 취소 재시도. 상태 재생. id={}", checkoutId);
            return canceled(checkoutId);
        }
        if (!current.status().modifiable()) {
            throw ApiException.checkoutNotModifiable(checkoutId, current.status());
        }
        if (!repository.cancel(checkoutId)) {
            return resolveCancelRace(checkoutId);
        }
        LOG.info("체크아웃 취소. id={} {} -> CANCELED", checkoutId, current.status());
        return canceled(checkoutId);
    }

    /**
     * 조건부 UPDATE 가 0행이면 읽은 뒤에 상태가 바뀐 것이다. 다시 읽어 판단한다.
     *
     * <p>다른 요청이 먼저 취소했으면 재생, Complete 가 집어 갔으면 409 다.
     */
    private CancelResult resolveCancelRace(String checkoutId) {
        CheckoutSession latest = repository.findById(checkoutId)
                .orElseThrow(() -> ApiException.checkoutNotFound(checkoutId));
        if (latest.status() == CheckoutStatus.CANCELED) {
            LOG.info("체크아웃 취소 경합. 이미 취소됨. id={}", checkoutId);
            return canceled(checkoutId);
        }
        throw ApiException.checkoutNotModifiable(checkoutId, latest.status());
    }

    private static CancelResult canceled(String checkoutId) {
        return new CancelResult(checkoutId, CheckoutStatus.CANCELED.apiValue());
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

    /**
     * 저장된 스냅샷이 응답이다. 다시 계산하지 않는다.
     *
     * <p>상태만 예외다. 취소·만료는 status 컬럼만 바꾸고 스냅샷 문자열은 건드리지 않으므로(DESIGN.md 7.1절)
     * 나갈 때 현재 값으로 덮는다. 저장된 것은 서명 대상이고 나가는 것은 현재 상태다.
     */
    private Checkout snapshotOf(CheckoutSession session) {
        Checkout stored = objectMapper.readValue(session.canonicalCheckout(), Checkout.class);
        String live = session.status().apiValue();
        return live.equals(stored.status()) ? stored : stored.withStatus(live);
    }
}
