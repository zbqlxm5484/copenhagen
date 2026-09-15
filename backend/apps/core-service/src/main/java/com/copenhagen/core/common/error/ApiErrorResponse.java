package com.copenhagen.core.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiErrorResponse(
        String errorCode,
        String message,
        String currentState,
        String nextAction,
        Long retryAfterSeconds) {

    public static ApiErrorResponse from(CoreErrorCode errorCode) {
        return new ApiErrorResponse(
                errorCode.code(),
                errorCode.defaultMessage(),
                null,
                errorCode.defaultNextAction(),
                null);
    }
}
