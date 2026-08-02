package io.github.ibcmanager.security;

import java.util.UUID;

public interface CredentialStore {
    boolean isAvailable();
    void save(UUID profileId, char[] password) throws CredentialStoreException;
    SecureChars load(UUID profileId) throws CredentialStoreException;
    boolean exists(UUID profileId);
    void delete(UUID profileId) throws CredentialStoreException;
}
