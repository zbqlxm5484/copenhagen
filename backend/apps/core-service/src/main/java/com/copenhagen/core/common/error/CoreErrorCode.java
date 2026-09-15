package com.copenhagen.core.common.error;

import org.springframework.http.HttpStatus;

public enum CoreErrorCode {

    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "요청 값이 올바르지 않습니다.",
            "CHECK_REQUEST"),
    AUTH_LOGIN_ID_DUPLICATED(
            HttpStatus.CONFLICT,
            "AUTH_LOGIN_ID_DUPLICATED",
            "이미 사용 중인 아이디입니다.",
            "USE_DIFFERENT_LOGIN_ID"),
    AUTH_EMAIL_DUPLICATED(
            HttpStatus.CONFLICT,
            "AUTH_EMAIL_DUPLICATED",
            "이미 사용 중인 이메일입니다.",
            "USE_DIFFERENT_EMAIL"),
    REQUEST_METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "REQUEST_METHOD_NOT_ALLOWED",
            "허용되지 않은 요청 메서드입니다.",
            "CHECK_REQUEST_METHOD"),
    REQUEST_MEDIA_TYPE_UNSUPPORTED(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "REQUEST_MEDIA_TYPE_UNSUPPORTED",
            "지원하지 않는 Content-Type입니다.",
            "CHECK_CONTENT_TYPE"),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "요청을 처리하는 중 오류가 발생했습니다.",
            "RETRY_LATER");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;
    private final String defaultNextAction;

    CoreErrorCode(
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
