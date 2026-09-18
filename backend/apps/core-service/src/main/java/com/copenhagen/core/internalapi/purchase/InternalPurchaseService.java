package com.copenhagen.core.internalapi.purchase;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.config.InternalApiProperties;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.internalapi.idempotency.IdempotencyStore;
import com.copenhagen.core.order.PurchaseRequest;
import com.copenhagen.core.order.PurchaseRequestRepository;

/**
 * MCP 구매 인계(설계서 §4.7). 장바구니를 확정 스냅샷으로 넘기는 것이 아니라, <b>배송지 선택 전</b>
 * 단계의 구매 요청을 만들고 앱으로 이어갈 링크를 돌려준다.
 *
 * <p>merchant 주문서는 만들지 않는다. 배송지 선택 후의 주문 검증은 앱의
 * {@code POST /api/core/v1/orders/validations}가 계속 담당한다.
 */
@Service
public class InternalPurchaseService {

    /** 앱 경로(순서 27)와 같은 정책 버전을 쓴다. */
    private static final String POLICY_VERSION = "v1";

    private final CartRepository cartRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final IdempotencyStore idempotencyStore;
    private final InternalApiProperties properties;
    private final Clock clock;

    public InternalPurchaseService(
            CartRepository cartRepository,
            PurchaseRequestRepository purchaseRequestRepository,
            IdempotencyStore idempotencyStore,
            InternalApiProperties properties,
            Clock coreClock) {
        this.cartRepository = cartRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.idempotencyStore = idempotencyStore;
        this.properties = properties;
        this.clock = coreClock;
    }

    @Transactional
    public String create(InternalRequestContext context, String path, UUID cartId, int cartVersion) {
        // App Link origin이 확정되지 않은 배포에서는 잘못된 링크를 만드는 대신 이 엔드포인트만 막는다.
        // mcp-server는 자기 설정과 문자 단위로 다른 appLink를 받으면 어차피 응답 전체를 버린다.
        if (!properties.hasAppLinkBase()) {
            throw new InternalApiException(
                    InternalErrorCode.CORE_SERVICE_ERROR,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "구매 인계 링크 설정이 없습니다.");
        }

        String canonicalRequest = "cartId=" + cartId + "&cartVersion=" + cartVersion;
        return idempotencyStore.execute(context, "POST", path, canonicalRequest, () -> {
            Cart cart = cartRepository.findActiveByUserId(context.userId())
                    .orElseThrow(() -> new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));
            if (!cart.publicId().equals(cartId) || cart.version() != cartVersion) {
                throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
            }
            if (cartRepository.findItemsByCartId(cart.id()).isEmpty()) {
                throw new CoreException(CoreErrorCode.ORDER_CART_EMPTY);
            }

            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.handoffTtl());
            UUID publicId = UUID.randomUUID();
            purchaseRequestRepository.insertPendingAddress(
                    publicId,
                    context.userId(),
                    cart.id(),
                    context.connectionId(),
                    POLICY_VERSION,
                    cartVersion,
                    expiresAt,
                    now);

            return new InternalPurchaseResponse(
                    publicId.toString(),
                    PurchaseRequest.STATUS_PENDING_ADDRESS,
                    cartId.toString(),
                    cartVersion,
                    expiresAt.toString(),
                    properties.appLinkBase() + publicId,
                    InternalPurchaseResponse.NEXT_ACTION_SELECT_ADDRESS);
        });
    }
}
