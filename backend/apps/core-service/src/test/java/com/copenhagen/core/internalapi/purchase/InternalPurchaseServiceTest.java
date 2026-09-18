package com.copenhagen.core.internalapi.purchase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartItem;
import com.copenhagen.core.cart.FakeCartRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.config.InternalApiProperties;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.internalapi.idempotency.FakeIdempotencyRepository;
import com.copenhagen.core.internalapi.idempotency.IdempotencyStore;
import com.copenhagen.core.order.FakePurchaseRequestRepository;
import com.copenhagen.core.order.PurchaseRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 설계서 §4.7 — 구매 인계는 PENDING_ADDRESS 행과 앱 링크만 만든다(merchant 주문서는 만들지 않는다). */
class InternalPurchaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private static final long USER_ID = 42L;
    private static final long CONNECTION_ID = 7L;
    private static final String PATH = "/internal/v1/purchase-requests";
    private static final String APP_LINK_BASE = "https://j15a207.p.ssafy.io/app/purchases/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeCartRepository cartRepository = new FakeCartRepository();
    private final FakePurchaseRequestRepository purchaseRequestRepository = new FakePurchaseRequestRepository();

    private Cart cart;
    private InternalPurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        cart = cartRepository.insertActiveCart(UUID.randomUUID(), USER_ID, NOW);
        purchaseService = service(APP_LINK_BASE);
    }

    @Test
    @DisplayName("PENDING_ADDRESS 행을 만들고 설정값 + id로 앱 링크를 만든다")
    void create_insertsPendingAddressRow() {
        addItem();

        InternalPurchaseResponse response = parse(purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertEquals(PurchaseRequest.STATUS_PENDING_ADDRESS, response.status());
        assertEquals(InternalPurchaseResponse.NEXT_ACTION_SELECT_ADDRESS, response.nextAction());
        assertEquals(cart.publicId().toString(), response.cartId());
        assertEquals(cart.version(), response.cartVersion());
        assertEquals(APP_LINK_BASE + response.purchaseRequestId(), response.appLink());
        assertEquals(NOW.plus(Duration.ofMinutes(30)).toString(), response.expiresAt());

        assertEquals(1, purchaseRequestRepository.rows.size());
        FakePurchaseRequestRepository.Row row = purchaseRequestRepository.rows.get(0);
        assertEquals(PurchaseRequest.STATUS_PENDING_ADDRESS, row.status());
        assertEquals(PurchaseRequest.CHANNEL_MCP, row.requestChannel());
        assertEquals(CONNECTION_ID, row.agentConnectionId());
        assertEquals(cart.version(), row.cartVersion());
        assertEquals(cart.id(), row.cartId());
    }

    @Test
    @DisplayName("만료는 mcp-server가 거부하는 31분보다 이르다")
    void create_expiresBeforeMcpLimit() {
        addItem();

        InternalPurchaseResponse response = parse(purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertTrue(Instant.parse(response.expiresAt()).isBefore(NOW.plusSeconds(1860)));
    }

    @Test
    @DisplayName("같은 키 재전송은 새 구매 요청을 만들지 않는다")
    void create_sameKeyTwice_insertsOnce() {
        addItem();
        UUID key = UUID.randomUUID();

        String first = purchaseService.create(context(key), PATH, cart.publicId(), cart.version());
        String second = purchaseService.create(context(key), PATH, cart.publicId(), cart.version());

        assertEquals(parse(first), parse(second));
        assertEquals(1, purchaseRequestRepository.rows.size());
    }

    @Test
    @DisplayName("빈 장바구니는 CART_EMPTY")
    void create_emptyCart_isCartEmpty() {
        CoreException exception = assertThrows(CoreException.class, () -> purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertEquals(CoreErrorCode.ORDER_CART_EMPTY, exception.errorCode());
        assertEquals(0, purchaseRequestRepository.rows.size());
    }

    @Test
    @DisplayName("cartId가 다르면 CART_VERSION_CONFLICT")
    void create_otherCartId_isConflict() {
        addItem();

        CoreException exception = assertThrows(CoreException.class, () -> purchaseService.create(
                context(UUID.randomUUID()), PATH, UUID.randomUUID(), cart.version()));

        assertEquals(CoreErrorCode.CART_VERSION_CONFLICT, exception.errorCode());
    }

    @Test
    @DisplayName("버전이 다르면 CART_VERSION_CONFLICT")
    void create_staleVersion_isConflict() {
        addItem();

        CoreException exception = assertThrows(CoreException.class, () -> purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version() + 1));

        assertEquals(CoreErrorCode.CART_VERSION_CONFLICT, exception.errorCode());
    }

    @Test
    @DisplayName("app-link-base가 없으면 이 엔드포인트만 503으로 막는다")
    void create_withoutAppLinkBase_isServiceUnavailable() {
        addItem();
        InternalPurchaseService unconfigured = service("");

        InternalApiException exception = assertThrows(InternalApiException.class, () -> unconfigured.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertEquals(InternalErrorCode.CORE_SERVICE_ERROR, exception.errorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.httpStatus());
        assertEquals(0, purchaseRequestRepository.rows.size());
    }

    @Test
    @DisplayName("끝에 슬래시가 없는 설정값도 mcp-server와 같은 형태로 맞춘다")
    void create_normalizesAppLinkBase() {
        addItem();
        InternalPurchaseService withoutSlash = service("https://j15a207.p.ssafy.io/app/purchases");

        InternalPurchaseResponse response = parse(withoutSlash.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertEquals(APP_LINK_BASE + response.purchaseRequestId(), response.appLink());
    }

    private InternalPurchaseService service(String appLinkBase) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new InternalPurchaseService(
                cartRepository,
                purchaseRequestRepository,
                new IdempotencyStore(new FakeIdempotencyRepository(), objectMapper, clock),
                new InternalApiProperties(appLinkBase, Duration.ofMinutes(30), 10),
                clock);
    }

    private void addItem() {
        cartRepository.insertItem(UUID.randomUUID(), new CartItem(
                0L, null, cart.id(), 1L, "prod-1001", "sku-black", "갤럭시 S25",
                "http://merchant:8080/products/prod-1001", null, "[]", 1, 1200000L, "KRW"), NOW);
    }

    private InternalRequestContext context(UUID idempotencyKey) {
        return new InternalRequestContext(
                USER_ID, UUID.randomUUID(), CONNECTION_ID, Set.of(), idempotencyKey);
    }

    private InternalPurchaseResponse parse(String body) {
        return objectMapper.readValue(body, InternalPurchaseResponse.class);
    }
}
