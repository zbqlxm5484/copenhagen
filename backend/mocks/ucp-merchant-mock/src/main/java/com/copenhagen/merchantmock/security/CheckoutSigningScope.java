package com.copenhagen.merchantmock.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서명 대상 범위를 정한다. 최상위 {@code com.*} 확장 키를 뺀다(DESIGN.md 6.2절).
 *
 * <p>빼는 이유는 순환이다. 서명 결과가
 * {@code com.copenhagen.payment.ap2_like.merchant_authorization} 에 들어가므로
 * 그 키를 포함해 서명하면 자기 자신을 포함하는 값을 서명하게 된다.
 *
 * <p>중첩된 객체 안의 {@code com.*} 는 건드리지 않는다. 최상위만이다.
 *
 * <p><b>이 범위는 아직 Core 와 확정되지 않았다(11절).</b> 설계서 11.1 은 전체 Checkout 을
 * 기준으로 읽히고 이 모듈은 {@code com.*} 제외를 기준으로 한다. 뒤집히면 이 클래스만 바뀐다.
 * 정규화와 다이제스트 계산은 범위와 무관하게 그대로다.
 */
public final class CheckoutSigningScope {

    /** UCP 확장 키의 접두어. 역방향 도메인이라 최상위에서 이것으로 시작하면 확장이다. */
    private static final String EXTENSION_PREFIX = "com.";

    private CheckoutSigningScope() {
    }

    /**
     * 최상위 {@code com.*} 키를 뺀 사본을 준다. 입력은 바꾸지 않는다.
     *
     * <p>입력 순서를 유지한다. JCS 가 어차피 정렬하므로 결과에는 영향이 없지만
     * 중간값을 로그로 볼 때 원본과 같은 순서로 보이는 편이 읽기 쉽다.
     */
    public static Map<String, Object> withoutExtensions(Map<String, Object> checkout) {
        if (checkout == null) {
            throw new IllegalArgumentException("서명 대상 Checkout 이 없다");
        }
        Map<String, Object> scoped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : checkout.entrySet()) {
            if (!isExtension(entry.getKey())) {
                scoped.put(entry.getKey(), entry.getValue());
            }
        }
        return scoped;
    }

    private static boolean isExtension(String key) {
        return key != null && key.startsWith(EXTENSION_PREFIX);
    }
}
