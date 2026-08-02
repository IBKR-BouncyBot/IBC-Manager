package io.github.ibcmanager.security;

public final class CredentialStoreException extends Exception {
    private static final long serialVersionUID = 1L;
    public CredentialStoreException(String message) {
        super(message);
    }

    public CredentialStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
