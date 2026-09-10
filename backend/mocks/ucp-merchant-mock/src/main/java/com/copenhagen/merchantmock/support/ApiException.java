package com.copenhagen.merchantmock.support;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드를 달고 나가는 예외. 코드 목록은 DESIGN.md 10절이 소유한다.
 *
 * <p>응답 형식은 Core 확정본의 {@code {code, message, requestId}} 다. 설계서 20장의 여섯 필드와
 * 어긋나 있고 Pay 가 2026-09-09 에 절충안을 냈다. 확정되면 {@link ErrorResponse} 만 고친다(11절).
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final HttpStatus status;

    public ApiException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static ApiException productNotFound(String productId) {
        return new ApiException("PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND, "상품을 찾을 수 없다: " + productId);
    }

    public static ApiException checkoutNotFound(String checkoutId) {
        return new ApiException("CHECKOUT_NOT_FOUND", HttpStatus.NOT_FOUND, "체크아웃을 찾을 수 없다: " + checkoutId);
    }

    public static ApiException checkoutNotModifiable(String checkoutId, Enum<?> status) {
        return new ApiException("CHECKOUT_NOT_MODIFIABLE", HttpStatus.CONFLICT,
                "이 상태에서는 체크아웃을 수정할 수 없다: " + checkoutId + " (" + status + ")");
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
