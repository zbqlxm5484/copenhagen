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
    AUTH_INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "AUTH_INVALID_CREDENTIALS",
            "아이디 또는 비밀번호가 올바르지 않습니다.",
            "CHECK_CREDENTIALS"),
    AUTH_ACCOUNT_UNAVAILABLE(
            HttpStatus.FORBIDDEN,
            "AUTH_ACCOUNT_UNAVAILABLE",
            "이용할 수 없는 계정입니다.",
            "CONTACT_SUPPORT"),
    AUTH_LOGIN_LOCKED(
            HttpStatus.LOCKED,
            "AUTH_LOGIN_LOCKED",
            "로그인이 일시적으로 제한되었습니다.",
            "TRY_AGAIN_LATER"),
    AUTH_TOKEN_MISSING(
            HttpStatus.UNAUTHORIZED,
            "AUTH_TOKEN_MISSING",
            "인증 토큰이 필요합니다.",
            "LOGIN_REQUIRED"),
    AUTH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_TOKEN_INVALID",
            "유효하지 않은 인증 토큰입니다.",
            "LOGIN_REQUIRED"),
    AUTH_REFRESH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_REFRESH_TOKEN_INVALID",
            "유효하지 않은 Refresh Token입니다.",
            "LOGIN_REQUIRED"),
    AUTH_REFRESH_TOKEN_REUSED(
            HttpStatus.UNAUTHORIZED,
            "AUTH_REFRESH_TOKEN_REUSED",
            "이미 사용된 Refresh Token입니다. 보안을 위해 모든 기기에서 로그아웃되었습니다.",
            "LOGIN_REQUIRED"),
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
