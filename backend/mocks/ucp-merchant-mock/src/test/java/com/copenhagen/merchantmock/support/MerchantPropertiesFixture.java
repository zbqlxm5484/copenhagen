package com.copenhagen.merchantmock.support;

import java.time.Duration;
import java.util.List;

/** 테스트에서 쓰는 merchant.* 설정 기본값. 실제 application.yml 과 같은 모양으로 맞춘다. */
public final class MerchantPropertiesFixture {

    /** 몰 셋(393). 첫 몰 merchant-a 가 V3 fixture 5건의 몰이고 옛 경로의 별칭이다. */
    public static final String MERCHANT_A = "merchant-a";
    public static final String MERCHANT_B = "merchant-b";
    public static final String MERCHANT_C = "merchant-c";
    public static final List<MerchantProperties.Mall> MALLS = List.of(
            new MerchantProperties.Mall(MERCHANT_A, "한강마켓"),
            new MerchantProperties.Mall(MERCHANT_B, "노을상점"),
            new MerchantProperties.Mall(MERCHANT_C, "별빛몰"));

    /**
     * 도서산간 추가금 구간(427). 판정 규칙은 application.yml 과 같은 모양이고 낱말 목록만 줄였다.
     *
     * <p>기본 배송비는 여기서 3000 이다. 실제 설정은 0(일반 지역 무료)이지만 픽스처까지 0 으로 두면
     * "기본 + 추가금" 덧셈이 어느 테스트에서도 안 드러난다. 0 인 설정은 그 자체로 시험한다
     * ({@code CheckoutServiceTest.create_freeShippingConfig_chargesOnlyRemoteAreaSurcharge}).
     */
    public static final List<MerchantProperties.Fulfillment.RemoteArea> REMOTE_AREAS = List.of(
            new MerchantProperties.Fulfillment.RemoteArea("도서산간", 5000L, List.of("402"),
                    List.of("울릉군", "옹진군", "추자"), List.of("영흥면")),
            new MerchantProperties.Fulfillment.RemoteArea("제주", 3000L, List.of("63"),
                    List.of(), List.of()));

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
                MALLS,
                "2026-08-25",
                "KRW",
                "http://ucp-merchant-mock:8080",
                new MerchantProperties.Catalog(List.of()),
                new MerchantProperties.Checkout(Duration.ofMinutes(5), Duration.ofSeconds(5)),
                new MerchantProperties.Fulfillment(3000L, REMOTE_AREAS),
                signing,
                new MerchantProperties.Capabilities(
                        new MerchantProperties.Capabilities.Ap2Like(false, "com.copenhagen.payment.ap2_like")),
                new MerchantProperties.PaymentHandler("com.copenhagen.pay", "copenhagen-pay-handler", "1"),
                List.of(new MerchantProperties.TrustedPlatform(
                        "core", "http://core-service:8080/.well-known/ucp")),
                new MerchantProperties.Scenario("NORMAL"),
                new MerchantProperties.Webhook(Duration.ofSeconds(10), 8, Duration.ofSeconds(5)));
    }

    /** 시연 카탈로그 시드 경로만 바꾼 설정. 로더 테스트가 쓴다(DESIGN.md 5.7절). */
    public static MerchantProperties withCatalogSeedFile(String seedFile) {
        return withCatalogSeedFiles(List.of(seedFile));
    }

    public static MerchantProperties withCatalogSeedFiles(List<String> seedFiles) {
        MerchantProperties base = defaults();
        return new MerchantProperties(
                base.malls(),
                base.ucpVersion(),
                base.currency(),
                base.publicBaseUrl(),
                new MerchantProperties.Catalog(seedFiles),
                base.checkout(),
                base.fulfillment(),
                base.signing(),
                base.capabilities(),
                base.paymentHandler(),
                base.trustedPlatforms(),
                base.scenario(),
                base.webhook());
    }

    public static MerchantProperties withAp2LikeEnabled() {
        MerchantProperties base = defaults();
        return new MerchantProperties(
                base.malls(),
                base.ucpVersion(),
                base.currency(),
                base.publicBaseUrl(),
                base.catalog(),
                base.checkout(),
                base.fulfillment(),
                base.signing(),
                new MerchantProperties.Capabilities(
                        new MerchantProperties.Capabilities.Ap2Like(true, "com.copenhagen.payment.ap2_like")),
                base.paymentHandler(),
                base.trustedPlatforms(),
                base.scenario(),
                base.webhook());
    }
}
