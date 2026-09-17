package com.copenhagen.core.order;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.signup.SignupService;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.FulfillmentResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Item;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.LineItem;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Message;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Total;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.ProductService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 순서 27(Merchant Checkout 생성 요청) — 실 PostgreSQL로 로그인→담기→배송지 등록→주문 검증 흐름을 확인한다.
 *
 * <p>merchant 호출만 대역으로 끊는다(HTTP 의존 제거). Flyway·인증·제약조건·트랜잭션은 전부 실제로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class OrderValidationApiIntegrationTest {

    private static final String PASSWORD = "Password1!";
    private static final String MERCHANT_ID = "merchant-a";
    private static final String JWS = "eyJhbGciOiJFUzI1NiJ9.eyJhIjoxfQ.sig";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private Map<String, MerchantCheckoutClient> merchantCheckoutClients;

    private MerchantCheckoutClient checkoutClient;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("""
                TRUNCATE users, carts, cart_items, addresses, purchase_requests, merchant_checkouts,
                    checkout_snapshots, checkout_snapshot_items, checkout_fulfillment_option_snapshots
                RESTART IDENTITY CASCADE
                """);
        // merchants 는 V7 시드다. 다른 통합 테스트가 TRUNCATE 하고 갈 수 있어 멱등하게 되살린다.
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (1, 'Copenhagen Demo Store', 'http://ucp-merchant-mock:8080/.well-known/ucp',
                        'http://ucp-merchant-mock:8080', '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);

        checkoutClient = mock(MerchantCheckoutClient.class);
        when(merchantCheckoutClients.get(MERCHANT_ID)).thenReturn(checkoutClient);
        when(productService.captureForCart(eq(MERCHANT_ID), eq("p-1"), any()))
                .thenReturn(new CartProductSnapshot(
                        "p-1", "sku-1", "옵션상품", "http://ucp-merchant-mock:8080/products/p-1",
                        null, 5000L, "KRW",
                        List.of(new CartProductSnapshot.SelectedOption("sku-1", "색상", "화이트"))));
    }

    @Test
    @DisplayName("검증에 성공하면 구매 요청과 merchant 주문서가 저장되고 201이 내려간다")
    void validate_success_persistsPurchaseRequestAndMerchantCheckout() throws Exception {
        String accessToken = signupAndLogin("ordervalid1", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());

        String response = mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkouts[0].merchantId").value(MERCHANT_ID))
                .andExpect(jsonPath("$.checkouts[0].status").value("READY"))
                .andExpect(jsonPath("$.checkouts[0].amount.total").value(13000))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String validationId = JsonPath.read(response, "$.validationId");
        assertEquals("CREATING_CHECKOUTS", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests WHERE public_id = ?::uuid", String.class, validationId));
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_checkouts mc
                JOIN purchase_requests pr ON pr.id = mc.purchase_request_id
                WHERE pr.public_id = ?::uuid AND mc.status = 'CREATING'
                  AND LENGTH(TRIM(mc.checkout_digest)) = 64
                """, Integer.class, validationId));
    }

    @Test
    @DisplayName("검증에 성공하면 주문서 스냅샷과 항목·배송 스냅샷이 함께 저장된다")
    void validate_success_persistsSnapshotRows() throws Exception {
        String accessToken = signupAndLogin("ordersnap1", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());

        String response = mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkouts[0].orderSnapshotId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String orderSnapshotId = JsonPath.read(response, "$.checkouts[0].orderSnapshotId");

        // 응답의 orderSnapshotId로 스냅샷을 찾을 수 있고, 금액·통화·PII·payload가 모두 채워져 있다.
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM checkout_snapshots
                WHERE public_id = ?::uuid AND total_amount = 13000 AND shipping_fee_amount = 3000
                  AND subtotal_amount = 10000 AND discount_amount = 0 AND tax_amount = 0
                  AND currency = 'KRW' AND expires_at IS NOT NULL
                  AND recipient_name_encrypted IS NOT NULL AND recipient_phone_encrypted IS NOT NULL
                  AND delivery_request_encrypted IS NOT NULL
                  AND merchant_checkout_payload ->> 'id' = 'checkout-1'
                """, Integer.class, orderSnapshotId));

        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM checkout_snapshot_items i
                JOIN checkout_snapshots s ON s.id = i.checkout_snapshot_id
                WHERE s.public_id = ?::uuid AND i.merchant_product_id = 'p-1'
                  AND i.merchant_variant_id = 'sku-1' AND i.quantity = 2
                  AND i.unit_price_amount = 5000 AND i.line_total_amount = 10000
                  AND i.selected_options @> '[{"optionValueId":"sku-1"}]'::jsonb
                """, Integer.class, orderSnapshotId));

        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM checkout_fulfillment_option_snapshots f
                JOIN checkout_snapshots s ON s.id = f.checkout_snapshot_id
                WHERE s.public_id = ?::uuid AND f.delivery_method = 'STANDARD'
                  AND f.shipping_fee_amount = 3000 AND f.fulfillment_group_key = 'checkout-1'
                """, Integer.class, orderSnapshotId));
    }

    @Test
    @DisplayName("반려되면 409와 항목 사유가 내려가고 구매 요청은 FAILED, 주문서는 남지 않는다")
    void validate_rejected_marksFailedAndKeepsNoCheckout() throws Exception {
        String accessToken = signupAndLogin("ordervalid2", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(new Checkout(
                "checkout-2", "incomplete", "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "옵션상품", 5000L), 2)),
                null,
                List.of(new Total("subtotal", 10000L), new Total("total", 10000L)),
                List.of(new Message("CHECKOUT_STOCK_INSUFFICIENT", "error", "재고가 부족하다", "line_items[0]")),
                null,
                null));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.issues[0].code").value("CHECKOUT_STOCK_INSUFFICIENT"));

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests", String.class));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkout_snapshots", Integer.class));
        // 검증이 반려돼도 장바구니는 그대로다(명세 비고).
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_items", Integer.class));
    }

    @Test
    @DisplayName("merchant를 부르지 못하면 503이고 구매 요청은 FAILED로 닫힌다")
    void validate_merchantUnavailable_returns503() throws Exception {
        String accessToken = signupAndLogin("ordervalid3", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("MERCHANT_CHECKOUT_UNAVAILABLE"));

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests", String.class));
    }

    @Test
    @DisplayName("빈 장바구니는 409 ORDER_CART_EMPTY이고 구매 요청도 만들지 않는다")
    void validate_emptyCart_returns409() throws Exception {
        String accessToken = signupAndLogin("ordervalid4", "홍길동");
        String addressId = registerAddress(accessToken);
        addCartItem(accessToken);
        removeAllCartItems();

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_CART_EMPTY"));

        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_requests", Integer.class));
    }

    @Test
    @DisplayName("인증 없는 호출은 401이다")
    void validate_withoutToken_returns401() throws Exception {
        String body = """
                {"cartId":"%s","cartVersion":1,"addressId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    private static Checkout readyCheckout() {
        return new Checkout(
                "checkout-1",
                "ready_for_complete",
                "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "옵션상품", 5000L), 2)),
                new FulfillmentResult("shipping", "available", 3000L),
                List.of(
                        new Total("subtotal", 10000L),
                        new Total("fulfillment", 3000L),
                        new Total("total", 13000L)),
                null,
                "2026-01-01T00:15:00Z",
                JWS);
    }

    private String validationBody(int cartVersion, String addressId) {
        String cartId = jdbcTemplate.queryForObject(
                "SELECT public_id FROM carts WHERE status = 'ACTIVE'", String.class);
        return """
                {"cartId":"%s","cartVersion":%d,"addressId":"%s"}
                """.formatted(cartId, cartVersion, addressId);
    }

    private void addCartItem(String accessToken) throws Exception {
        mockMvc.perform(post("/api/core/v1/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":0,"productId":"p-1","merchantId":"merchant-a",
                                 "optionValueIds":["sku-1"],"quantity":2}
                                """))
                .andExpect(status().isCreated());
    }

    private void removeAllCartItems() {
        jdbcTemplate.update("DELETE FROM cart_items");
    }

    private String registerAddress(String accessToken) throws Exception {
        String response = mockMvc.perform(post("/api/core/v1/addresses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"홍길동","recipientPhone":"01012345678",
                                 "postalCode":"06236","addressLine1":"서울시 강남구 테헤란로 1",
                                 "addressLine2":"5층","deliveryRequest":"문 앞에 놓아주세요"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.addressId");
    }

    private String signupAndLogin(String loginId, String name) throws Exception {
        signupService.signup(loginId, PASSWORD, loginId + "@example.com", name);
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s","deviceId":"device-1","deviceName":"iPhone 15"}
                                """.formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
