package com.copenhagen.pay.common.error;

import org.springframework.http.HttpStatus;

public enum PayErrorCode {

    AUTHENTICATION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "인증이 필요합니다.",
            "PROVIDE_ACCESS_TOKEN"),
    INVALID_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "INVALID_TOKEN",
            "유효하지 않은 인증 토큰입니다.",
            "REAUTHENTICATE"),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "이 요청을 수행할 권한이 없습니다.",
            "CHECK_PERMISSION"),
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "요청 형식이 올바르지 않습니다.",
            "CHECK_REQUEST"),
    RESOURCE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_FOUND",
            "요청한 결제 리소스를 찾을 수 없습니다.",
            "CHECK_RESOURCE_ID"),
    STATE_CONFLICT(
            HttpStatus.CONFLICT,
            "STATE_CONFLICT",
            "현재 상태에서는 요청을 처리할 수 없습니다.",
            "REFRESH_CURRENT_STATE"),
    IDEMPOTENCY_KEY_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "IDEMPOTENCY_KEY_REQUIRED",
            "상태를 변경하는 요청에는 Idempotency-Key가 필요합니다.",
            "PROVIDE_IDEMPOTENCY_KEY"),
    IDEMPOTENCY_KEY_INVALID(
            HttpStatus.BAD_REQUEST,
            "IDEMPOTENCY_KEY_INVALID",
            "Idempotency-Key 형식이 올바르지 않습니다.",
            "PROVIDE_VALID_IDEMPOTENCY_KEY"),
    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_CONFLICT",
            "동일한 멱등성 키로 다른 요청이 전달되었습니다.",
            "USE_NEW_IDEMPOTENCY_KEY"),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "요청을 처리하는 중 오류가 발생했습니다.",
            "RETRY_LATER");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;
    private final String defaultNextAction;

    PayErrorCode(
            HttpStatus httpStatus,
            String code,
            String defaultMessage,
            String defaultNextAction) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.defaultNextAction = defaultNextAction;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public String defaultNextAction() {
        return defaultNextAction;
    }
}
