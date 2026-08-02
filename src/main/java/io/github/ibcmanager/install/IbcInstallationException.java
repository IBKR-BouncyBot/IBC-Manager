package io.github.ibcmanager.install;

public final class IbcInstallationException extends Exception {
    private static final long serialVersionUID = 1L;

    public IbcInstallationException(String message) {
        super(message);
    }

    public IbcInstallationException(String message, Throwable cause) {
        super(message, cause);
    }
}
