package com.copenhagen.merchantmock.paymenthandler;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 services.pay.* 설정. 구조는 DESIGN.md 3.4절이 소유한다.
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
        Duration requestTimeout) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(3);

    public PayProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("services.pay.base-url 이 비어 있다");
        }
        bearerToken = bearerToken == null ? "" : bearerToken.trim();
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }
}
