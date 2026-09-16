package com.copenhagen.merchantmock.support;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 merchant.* 설정. 구조는 DESIGN.md 3.4절이 소유한다.
 *
 * <p>개인키는 설정 파일에 저장하지 않는다(설계서 18.5). privateKeyLocation 과 privateKeyPem 은
 * 환경변수로만 채우고 기본값은 빈 문자열이다. publicKeyJwk 는 공개키라 파일에 그대로 둔다.
 */
@ConfigurationProperties(prefix = "merchant")
public record MerchantProperties(
        String id,
        String ucpVersion,
        String currency,
        String publicBaseUrl,
        String sellerName,
        Checkout checkout,
        Fulfillment fulfillment,
        Signing signing,
        Capabilities capabilities,
        PaymentHandler paymentHandler,
        List<TrustedPlatform> trustedPlatforms,
        Scenario scenario,
        Webhook webhook) {

    /** UCP REST base endpoint. Core 는 이 값을 Profile 에서 읽어 간다. */
    public String restEndpoint() {
        return publicBaseUrl + "/ucp/v1";
    }

    public record Checkout(Duration ttl) {
    }

    public record Fulfillment(long flatFee) {
    }

    public record Signing(
            String kid,
            String algorithm,
            String privateKeyLocation,
            String privateKeyPem,
            String publicKeyJwk) {
    }

    public record Capabilities(Ap2Like ap2Like) {

        /**
         * AP2-like Capability 광고 여부. 네임스페이스가 Core·Pay 와 합의되기 전까지 끈다.
         * 합의되면 enabled 만 true 로 바꾸면 된다(DESIGN.md 11절).
         */
        public record Ap2Like(boolean enabled, String namespace) {
        }
    }

    public record PaymentHandler(String namespace, String id, String version) {
    }

    public record TrustedPlatform(String id, String profileUrl) {
    }

    public record Scenario(String initial) {
    }

    /**
     * 웹훅 재시도 정책(DESIGN.md 7.4절). 실패 n회 뒤 다음 시도까지 {@code retryInitialDelay × 2^(n-1)}.
     * {@code retryMaxAttempts} 를 넘기면 184 워커가 FAILED 로 닫는다.
     */
    public record Webhook(Duration retryInitialDelay, int retryMaxAttempts) {

        public Webhook {
            retryInitialDelay = retryInitialDelay == null ? Duration.ofSeconds(10) : retryInitialDelay;
            retryMaxAttempts = retryMaxAttempts <= 0 ? 8 : retryMaxAttempts;
        }
    }
}
