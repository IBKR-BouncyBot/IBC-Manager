package io.github.ibcmanager.security;

import java.util.UUID;

public final class UnavailableCredentialStore implements CredentialStore {
    private final String reason;

    public UnavailableCredentialStore(String reason) {
        this.reason = reason == null ? "Credential storage is unavailable" : reason;
    }

    @Override public boolean isAvailable() { return false; }
    @Override public void save(UUID profileId, char[] password) throws CredentialStoreException { throw new CredentialStoreException(reason); }
    @Override public SecureChars load(UUID profileId) throws CredentialStoreException { throw new CredentialStoreException(reason); }
    @Override public boolean exists(UUID profileId) { return false; }
    @Override public void delete(UUID profileId) { }
}
