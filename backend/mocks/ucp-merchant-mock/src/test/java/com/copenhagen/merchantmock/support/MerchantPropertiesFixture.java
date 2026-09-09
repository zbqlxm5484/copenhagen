package com.copenhagen.merchantmock.support;

import java.time.Duration;
import java.util.List;

/** 테스트에서 쓰는 merchant.* 설정 기본값. 실제 application.yml 과 같은 모양으로 맞춘다. */
public final class MerchantPropertiesFixture {

    public static final String PUBLIC_JWK = """
            {"kty":"EC","crv":"P-256",\
            "x":"Kv4fWw_TEzEQ2EOMOgsbIyH9314FhRdQknrvaPY5Q3Q",\
            "y":"d989gH2qhu_9cnv2pmrZs_rlT3kso94QTC_VXvkzEmc",\
            "use":"sig","alg":"ES256","kid":"merchant-a-es256-1"}""";

    private MerchantPropertiesFixture() {
    }

    public static MerchantProperties defaults() {
        return withSigning(new MerchantProperties.Signing(
                "merchant-a-es256-1", "ES256", "", "", PUBLIC_JWK));
    }

    public static MerchantProperties withSigning(MerchantProperties.Signing signing) {
        return new MerchantProperties(
                "merchant-a",
                "2026-08-25",
                "KRW",
                "http://ucp-merchant-mock:8080",
                "Copenhagen Demo Store",
                new MerchantProperties.Checkout(Duration.ofMinutes(5)),
                new MerchantProperties.Fulfillment(3000L),
                signing,
                new MerchantProperties.Capabilities(
                        new MerchantProperties.Capabilities.Ap2Like(false, "com.copenhagen.payment.ap2_like")),
                new MerchantProperties.PaymentHandler("com.copenhagen.pay", "copenhagen-pay-handler", "1"),
                List.of(new MerchantProperties.TrustedPlatform(
                        "core", "http://core-service:8080/.well-known/ucp")),
                new MerchantProperties.Scenario("NORMAL"));
    }

    public static MerchantProperties withAp2LikeEnabled() {
        MerchantProperties base = defaults();
        return new MerchantProperties(
                base.id(),
                base.ucpVersion(),
                base.currency(),
                base.publicBaseUrl(),
                base.sellerName(),
                base.checkout(),
                base.fulfillment(),
                base.signing(),
                new MerchantProperties.Capabilities(
                        new MerchantProperties.Capabilities.Ap2Like(true, "com.copenhagen.payment.ap2_like")),
                base.paymentHandler(),
                base.trustedPlatforms(),
                base.scenario());
    }
}
