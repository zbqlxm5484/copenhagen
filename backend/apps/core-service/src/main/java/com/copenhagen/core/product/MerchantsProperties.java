package com.copenhagen.core.product;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code services.merchants} 목록. 다중 merchant를 순회하도록 리스트로 둔다(현재는 1개만 배포돼 있어도 됨).
 * 객체 리스트는 {@code @Value}로 바인딩할 수 없어 core-service에서 이번에 처음
 * {@code @ConfigurationProperties}를 예외적으로 사용한다.
 */
@ConfigurationProperties("services")
public record MerchantsProperties(List<MerchantConfig> merchants) {

    public record MerchantConfig(
            String id, String displayName, URI baseUrl, Duration connectTimeout, Duration requestTimeout) {
    }
}
