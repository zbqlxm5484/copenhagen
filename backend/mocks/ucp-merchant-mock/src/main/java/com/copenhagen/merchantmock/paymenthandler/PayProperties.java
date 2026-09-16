package com.copenhagen.merchantmock.paymenthandler;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 services.pay.* 설정. 구조는 DESIGN.md 3.4절이 소유한다.
 *
 * <p>recheck* 는 UNKNOWN 재조회 워커(183)의 정책이다. merchant.* 가 아니라 여기 두는 이유는 Pay 를 부르는
 * 정책이기 때문이다. merchant.payment-handler.* 는 Profile 광고용이라 이름만 비슷하고 다른 것이다.
 *
 * <p>bearerToken 은 Pay 가 merchant-a 에 매핑해 둔 정적 토큰이다(DESIGN.md 19.5절). 비어 있어도 기동은 된다.
 * 로컬에서 Pay 없이 다른 기능을 돌릴 수 있어야 하기 때문이다. 그 경우 첫 청구에서 Pay 가 401 을 돌려주고
 * 결제는 FAILED 로 남는다.
 */
@ConfigurationProperties(prefix = "services.pay")
public record PayProperties(
        String baseUrl,
        String bearerToken,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration recheckInitialDelay,
        int recheckMaxAttempts) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_RECHECK_INITIAL_DELAY = Duration.ofSeconds(30);
    public static final int DEFAULT_RECHECK_MAX_ATTEMPTS = 10;

    public PayProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("services.pay.base-url 이 비어 있다");
        }
        bearerToken = bearerToken == null ? "" : bearerToken.trim();
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        recheckInitialDelay = recheckInitialDelay == null
                ? DEFAULT_RECHECK_INITIAL_DELAY : recheckInitialDelay;
        recheckMaxAttempts = recheckMaxAttempts <= 0
                ? DEFAULT_RECHECK_MAX_ATTEMPTS : recheckMaxAttempts;
    }

    /** {@code initial × 2^(attempt-1)}. 1회 실패 뒤 30초, 2회 1분, 3회 2분(DESIGN.md 8.8절). */
    public Duration recheckBackoff(int attempt) {
        return recheckInitialDelay.multipliedBy(1L << Math.max(0, Math.min(attempt - 1, 20)));
    }
}
