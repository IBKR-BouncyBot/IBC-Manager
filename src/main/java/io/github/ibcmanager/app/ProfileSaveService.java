package io.github.ibcmanager.app;

import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.storage.ProfileRepository;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class ProfileSaveService {
    private final ProfileRepository repository;
    private final CredentialStore credentialStore;
    private final ManagedConfigService configService;

    public ProfileSaveService(ProfileRepository repository, CredentialStore credentialStore,
            ManagedConfigService configService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    public void save(Profile previous, Profile updated, char[] newPassword)
            throws IOException, CredentialStoreException {
        Objects.requireNonNull(updated, "updated");
        if (previous != null && !previous.id().equals(updated.id())) {
            throw new IllegalArgumentException("An existing profile cannot be saved under a different profile ID");
        }
        char[] supplied = newPassword == null ? new char[0] : Arrays.copyOf(newPassword, newPassword.length);
        if (updated.credentialMode() == CredentialMode.ENCRYPTED
                && TextSafety.containsConfigBreakingControl(supplied)) {
            Arrays.fill(supplied, '\0');
            throw new CredentialStoreException(
                    "Passwords containing line breaks, Unicode line separators, or NUL characters are unsupported");
        }
        SecureChars previousSecret = null;
        boolean previousCredentialExisted = credentialStore.exists(updated.id());
        try {
            if (previousCredentialExisted && credentialStore.isAvailable()) {
                previousSecret = credentialStore.load(updated.id());
            }
            if (updated.credentialMode() == CredentialMode.ENCRYPTED) {
                if (!credentialStore.isAvailable()) {
                    throw new CredentialStoreException("Encrypted credential storage is unavailable");
                }
                if (supplied.length > 0) credentialStore.save(updated.id(), supplied);
                else if (!previousCredentialExisted) {
                    throw new CredentialStoreException("Enter a password before enabling encrypted credential mode");
                }
            }

            repository.save(updated);
            if (updated.credentialMode() != CredentialMode.EXISTING_CONFIG) {
                configService.refreshManagedSettings(updated);
            }
            if (updated.credentialMode() != CredentialMode.ENCRYPTED && previousCredentialExisted) {
                credentialStore.delete(updated.id());
            }
        } catch (IOException | CredentialStoreException | RuntimeException failure) {
            rollbackProfile(previous, updated);
            rollbackCredential(updated, previousCredentialExisted, previousSecret);
            throw failure;
        } finally {
            Arrays.fill(supplied, '\0');
            if (previousSecret != null) previousSecret.close();
        }
    }

    private void rollbackProfile(Profile previous, Profile updated) {
        try {
            if (previous == null) repository.delete(updated.id());
            else repository.save(previous);
        } catch (IOException rollbackFailure) {
            // The original exception remains primary; AtomicFileWriter also retains a .bak copy.
        }
    }

    private void rollbackCredential(Profile updated, boolean existed, SecureChars previousSecret) {
        try {
            if (existed && previousSecret != null) {
                char[] copy = previousSecret.copy();
                try { credentialStore.save(updated.id(), copy); }
                finally { Arrays.fill(copy, '\0'); }
            } else if (!existed && credentialStore.exists(updated.id())) {
                credentialStore.delete(updated.id());
            }
        } catch (CredentialStoreException | IllegalStateException rollbackFailure) {
            // The caller receives the primary failure. Diagnostics and the profile backup assist recovery.
        }
    }
}
