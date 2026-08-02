package io.github.ibcmanager.runtime;

public final class RuntimeControllerException extends Exception {
    private static final long serialVersionUID = 1L;
    public RuntimeControllerException(String message) {
        super(message);
    }

    public RuntimeControllerException(String message, Throwable cause) {
        super(message, cause);
    }
}
