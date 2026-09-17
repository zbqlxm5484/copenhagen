package com.copenhagen.merchantmock.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 merchant.* 설정. 구조는 DESIGN.md 3.4절이 소유한다.
 *
 * <p>컨테이너 하나가 몰 셋을 서빙한다(393, ADR 0008). 몰은 {@code merchant.malls[]} 목록이고 경로의 두 번째
 * 세그먼트가 어느 몰인지 말한다. 서명키·통화·UCP 버전은 몰 셋이 공유한다 — 인스턴스가 하나라 키 공급자도 하나다.
 *
 * <p>개인키는 설정 파일에 저장하지 않는다(설계서 18.5). privateKeyLocation 과 privateKeyPem 은
 * 환경변수로만 채우고 기본값은 빈 문자열이다. publicKeyJwk 는 공개키라 파일에 그대로 둔다.
 */
@ConfigurationProperties(prefix = "merchant")
public record MerchantProperties(
        List<Mall> malls,
        String ucpVersion,
        String currency,
        String publicBaseUrl,
        Catalog catalog,
        Checkout checkout,
        Fulfillment fulfillment,
        Signing signing,
        Capabilities capabilities,
        PaymentHandler paymentHandler,
        List<TrustedPlatform> trustedPlatforms,
        Scenario scenario,
        Webhook webhook) {

    /** 다른 서비스(/api/core, /api/pay)와 맞춘 접두어. 몰 세그먼트가 그 뒤에 온다(DESIGN.md 3.3절). */
    public static final String API_PREFIX = "/api/ucp/v1";

    public MerchantProperties {
        if (malls == null || malls.isEmpty()) {
            throw new IllegalArgumentException("merchant.malls 가 비어 있다. 몰이 최소 하나 있어야 한다");
        }
        malls = List.copyOf(malls);
        catalog = catalog == null ? new Catalog(List.of()) : catalog;
    }

    /** 몰 하나의 UCP REST base endpoint. Core 는 이 값을 그 몰의 Profile 에서 읽어 간다. */
    public String restEndpoint(String merchantId) {
        return publicBaseUrl + API_PREFIX + "/" + merchantId;
    }

    public Optional<Mall> mall(String merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return malls.stream().filter(mall -> mall.id().equals(merchantId)).findFirst();
    }

    /** 경로 세그먼트가 가리키는 몰. 없으면 404 다(DESIGN.md 3.3절). */
    public Mall requireMall(String merchantId) {
        return mall(merchantId).orElseThrow(() -> ApiException.merchantNotFound(merchantId));
    }

    /** 목록의 첫 몰. 옛 경로 {@code /ucp/v1/**} 가 이 몰의 별칭이다({@link LegacyPathFilter}). */
    public Mall defaultMall() {
        return malls.get(0);
    }

    /**
     * 몰 하나. {@code id} 는 Core {@code services.merchants[].id}, Pay 토큰 JSON 의 키, 경로 세그먼트와 같은
     * 문자열이어야 한다(DESIGN.md 19.5절). {@code sellerName} 은 상호다.
     */
    public record Mall(String id, String sellerName) {
        public Mall {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("merchant.malls[].id 가 비어 있다");
            }
            sellerName = sellerName == null || sellerName.isBlank() ? id : sellerName.trim();
        }
    }

    /**
     * 시연 카탈로그 시드(DESIGN.md 5.7절). {@code seedFiles} 가 비어 있으면 로더가 아무 일도 하지 않고
     * V3 fixture 5건만 남는다. 기본값이 비어 있어야 테스트와 CI 가 fixture 구성을 그대로 본다.
     *
     * <p>파일마다 {@code mall} 필드가 어느 몰의 시드인지 말한다. 한 DB 에 몰 셋의 파일을 다 넣는다(393).
     * 환경변수 하나에 쉼표로 이어 준다 — 목록을 항목별 환경변수로 덮으면 Spring 이 나머지 항목을 지운다.
     */
    public record Catalog(List<String> seedFiles) {
        public Catalog {
            List<String> cleaned = new ArrayList<>();
            if (seedFiles != null) {
                for (String file : seedFiles) {
                    if (file != null && !file.isBlank()) {
                        cleaned.add(file.trim());
                    }
                }
            }
            seedFiles = List.copyOf(cleaned);
        }

        public boolean hasSeedFiles() {
            return !seedFiles.isEmpty();
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
