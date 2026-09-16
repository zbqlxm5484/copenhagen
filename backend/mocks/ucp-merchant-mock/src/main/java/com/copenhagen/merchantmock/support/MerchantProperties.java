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
        Catalog catalog,
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

    /**
     * 시연 카탈로그 시드(DESIGN.md 5.7절). seedFile 이 비어 있으면 로더가 아무 일도 하지 않고
     * V3 fixture 5건만 남는다. 기본값이 비어 있어야 테스트와 CI 가 fixture 구성을 그대로 본다.
     *
     * <p>경로를 설정으로 받는 이유는 몰 여러 개다(11절). 같은 이미지를 셋 띄울 때 몰마다 다른
     * 시드 파일을 주면 서로 다른 상품을 파는 몰 셋이 코드 변경 없이 나온다.
     */
    public record Catalog(String seedFile) {

        public Catalog {
            seedFile = seedFile == null ? "" : seedFile.trim();
        }

        public boolean hasSeedFile() {
            return !seedFile.isEmpty();
        }
    }

    /**
     * @param ttl           ready_for_complete 판정 시각 + 이 값이 expires_at 이다(설계서 D-03)
     * @param scenarioDelay COMPLETE_TIMEOUT_BEFORE_PROCESS·AFTER_ORDER 가 켜졌을 때 Complete 가 기다리는 시간(9.3절).
     *                      Core 의 요청 타임아웃보다 길어야 응답 유실이 재현된다. 시나리오가 꺼져 있으면 읽지 않는다
     */
    public record Checkout(Duration ttl, Duration scenarioDelay) {

        public Checkout {
            scenarioDelay = scenarioDelay == null ? Duration.ofSeconds(10) : scenarioDelay;
        }
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
     *
     * <p>{@code scenarioDelay} 는 시나리오 {@code WEBHOOK_DELAYED} 가 켜졌을 때 보내기 전에 기다리는 시간이다(9.3절).
     * 시나리오가 꺼져 있으면 읽지 않는다.
     */
    public record Webhook(Duration retryInitialDelay, int retryMaxAttempts, Duration scenarioDelay) {

        public Webhook {
            retryInitialDelay = retryInitialDelay == null ? Duration.ofSeconds(10) : retryInitialDelay;
            retryMaxAttempts = retryMaxAttempts <= 0 ? 8 : retryMaxAttempts;
            scenarioDelay = scenarioDelay == null ? Duration.ofSeconds(5) : scenarioDelay;
        }
    }
}
