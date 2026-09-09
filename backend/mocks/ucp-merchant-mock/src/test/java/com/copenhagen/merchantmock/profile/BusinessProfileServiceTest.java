package com.copenhagen.merchantmock.profile;

import java.util.List;
import java.util.Map;

import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 필수 테스트 1·2·3번의 단위 수준 검증(DESIGN.md 12절).
 *
 * <p>1 Profile Discovery, 2 Payment Handler 광고, 3 AP2-like Capability 광고.
 */
class BusinessProfileServiceTest {

    private static BusinessProfileResponse build(MerchantProperties properties) {
        return new BusinessProfileService(properties, new SigningKeyProvider(properties)).build();
    }

    @Test
    void 버전과_REST_엔드포인트를_광고한다() {
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        assertEquals("2026-08-25", profile.ucp().version());
        List<BusinessProfileResponse.Service> shopping = profile.ucp().services().get("dev.ucp.shopping");
        assertEquals(1, shopping.size());
        assertEquals("rest", shopping.get(0).transport());
        assertEquals("http://ucp-merchant-mock:8080/ucp/v1", shopping.get(0).endpoint());
        assertEquals("2026-08-25", shopping.get(0).version());
    }

    @Test
    void Capability_다섯개를_광고하고_Cart는_광고하지_않는다() {
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        Map<String, List<BusinessProfileResponse.Capability>> capabilities = profile.ucp().capabilities();

        assertEquals(5, capabilities.size());
        assertTrue(capabilities.containsKey("dev.ucp.shopping.catalog.search"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.catalog.lookup"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.checkout"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.order"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.fulfillment"));
        assertFalse(capabilities.containsKey("dev.ucp.shopping.cart"), "Cart 는 광고하지 않기로 합의했다");
    }

    @Test
    void Payment_Handler를_광고한다() {
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        List<BusinessProfileResponse.PaymentHandler> handlers =
                profile.ucp().paymentHandlers().get("com.copenhagen.pay");

        assertEquals(1, handlers.size());
        assertEquals("copenhagen-pay-handler", handlers.get(0).id());
        assertEquals("1", handlers.get(0).version());
    }

    @Test
    void AP2_like_Capability는_기본으로_꺼져_있다() {
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        assertFalse(profile.ucp().capabilities().containsKey("com.copenhagen.payment.ap2_like"));
    }

    @Test
    void AP2_like_Capability를_켜면_광고한다() {
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.withAp2LikeEnabled());

        assertTrue(profile.ucp().capabilities().containsKey("com.copenhagen.payment.ap2_like"));
        assertEquals(6, profile.ucp().capabilities().size());
    }

    @Test
    void keys에_공개키가_실린다() {
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        assertEquals(1, profile.keys().size());
        assertEquals("merchant-a-es256-1", profile.keys().get(0).get("kid"));
    }

    @Test
    void 공개키가_없으면_keys는_빈_배열이다() {
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing("merchant-a-es256-1", "ES256", "", "", ""));

        BusinessProfileResponse profile = build(properties);

        assertTrue(profile.keys().isEmpty());
    }
}
