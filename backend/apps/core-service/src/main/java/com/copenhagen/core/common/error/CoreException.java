package com.copenhagen.core.common.error;

public class CoreException extends RuntimeException {

    private final CoreErrorCode errorCode;
    private final String currentState;
    private final String nextAction;

    public CoreException(CoreErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, errorCode.defaultNextAction(), null);
    }

    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction) {
        this(errorCode, message, currentState, nextAction, null);
    }

    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.currentState = currentState;
        this.nextAction = nextAction;
    }

    public CoreErrorCode errorCode() {
        return errorCode;
    }

    public String currentState() {
        return currentState;
    }

    public String nextAction() {
        return nextAction;
    }
}
