package io.github.ibcmanager.app;

import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.config.RuntimeConfigLocation;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.storage.ProfileRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Transactional deletion of a stopped profile, runtime state, and stored credential. */
public final class ProfileDeletionService {
    private static final Logger LOG = io.github.ibcmanager.logging.AppLog.get(ProfileDeletionService.class);
    private static final String METADATA = "transaction.properties";
    private static final int MAX_METADATA_BYTES = 4096;
    private static final String PREPARED = "PREPARED";
    private static final String COMMITTED = "COMMITTED";
    private final AppPaths paths;
    private final ProfileRepository repository;
    private final CredentialStore credentialStore;

    public ProfileDeletionService(AppPaths paths, ProfileRepository repository, CredentialStore credentialStore) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
    }

    public synchronized void delete(Profile profile) throws IOException, CredentialStoreException {
        Objects.requireNonNull(profile, "profile");
        FilePermissionHardener.hardenDirectory(paths.deletions());
        Path transaction = paths.deletions().resolve(profile.id() + "-" + UUID.randomUUID());
        FilePermissionHardener.hardenDirectory(transaction);
        writeMetadata(transaction, profile.id(), PREPARED);
        Path stagedProfile = transaction.resolve("profile");
        Path stagedRuntime = transaction.resolve("runtime");
        boolean profileMoved = false;
        boolean runtimeMoved = false;
        boolean credentialDeleted = false;
        boolean committed = false;
        boolean credentialExisted = credentialStore.exists(profile.id());
        SecureChars previousSecret = null;
        try {
            if (credentialExisted) {
                if (!credentialStore.isAvailable()) {
                    throw new CredentialStoreException(
                            "Stored credentials cannot be removed because the credential store is unavailable");
                }
                previousSecret = credentialStore.load(profile.id());
            }

            scrubRuntimeConfiguration(profile);
            profileMoved = repository.stageDelete(profile.id(), stagedProfile);
            if (!profileMoved) throw new IOException("Profile directory does not exist: " + profile.id());

            Path runtime = paths.runtimeDirectory(profile.id());
            if (Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) {
                SecureFileOperations.moveDirectory(runtime, stagedRuntime);
                runtimeMoved = true;
            }

            writeMetadata(transaction, profile.id(), COMMITTED);
            committed = true;
            if (credentialExisted) {
                credentialStore.delete(profile.id());
                credentialDeleted = true;
            }
        } catch (IOException | CredentialStoreException | RuntimeException failure) {
            boolean rollbackIsDurable = true;
            if (committed) {
                try { writeMetadata(transaction, profile.id(), PREPARED); }
                catch (IOException metadataFailure) {
                    failure.addSuppressed(metadataFailure);
                    rollbackIsDurable = false;
                }
            }
            if (rollbackIsDurable) {
                rollback(profile.id(), stagedProfile, profileMoved, stagedRuntime, runtimeMoved,
                        credentialExisted, credentialDeleted, previousSecret, failure);
            } else {
                LOG.log(Level.SEVERE,
                        "Profile deletion could not switch its durable transaction back to PREPARED; "
                                + "the COMMITTED tombstone was retained for safe startup completion",
                        failure);
            }
            throw failure;
        } finally {
            if (previousSecret != null) previousSecret.close();
        }

        try {
            SecureFileOperations.deleteTree(transaction);
        } catch (IOException cleanupFailure) {
            LOG.log(Level.WARNING,
                    "Profile deletion committed but its private tombstone could not be removed", cleanupFailure);
        }
    }

    public synchronized void cleanupStaleTransactions() {
        Path deletions = paths.deletions();
        if (!SecureFileOperations.isDirectory(deletions)) return;
        try (var entries = Files.newDirectoryStream(deletions)) {
            for (Path transaction : entries) {
                if (!SecureFileOperations.isDirectory(transaction)) {
                    LOG.warning("Ignored unsafe profile-deletion transaction entry: " + transaction);
                    continue;
                }
                try {
                    TransactionMetadata metadata = readMetadata(transaction);
                    validateTransactionName(transaction, metadata.profileId());
                    if (COMMITTED.equals(metadata.state())) {
                        finishCommitted(transaction, metadata.profileId());
                    } else if (PREPARED.equals(metadata.state())) {
                        restorePrepared(transaction, metadata.profileId());
                    } else {
                        throw new IOException("Unknown profile-deletion transaction state");
                    }
                } catch (IOException | CredentialStoreException | RuntimeException recoveryFailure) {
                    LOG.log(Level.WARNING, "Could not recover a stale profile-deletion transaction", recoveryFailure);
                }
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not inspect stale profile-deletion transactions", ex);
        }
    }

    private void finishCommitted(Path transaction, UUID profileId)
            throws IOException, CredentialStoreException {
        if (credentialStore.exists(profileId)) {
            if (!credentialStore.isAvailable()) {
                throw new CredentialStoreException("Credential store is unavailable while completing profile deletion");
            }
            credentialStore.delete(profileId);
        }
        SecureFileOperations.deleteTree(transaction);
    }

    private void restorePrepared(Path transaction, UUID profileId) throws IOException {
        Path stagedProfile = transaction.resolve("profile");
        Path stagedRuntime = transaction.resolve("runtime");
        Path profileDestination = paths.profileDirectory(profileId);
        Path runtimeDestination = paths.runtimeDirectory(profileId);
        if (SecureFileOperations.exists(stagedProfile)) {
            if (SecureFileOperations.exists(profileDestination)) {
                throw new IOException("Cannot restore staged profile because its destination already exists");
            }
            repository.restoreStagedDelete(profileId, stagedProfile);
        }
        if (SecureFileOperations.exists(stagedRuntime)) {
            if (SecureFileOperations.exists(runtimeDestination)) {
                throw new IOException("Cannot restore staged runtime state because its destination already exists");
            }
            SecureFileOperations.moveDirectory(stagedRuntime, runtimeDestination);
        }
        SecureFileOperations.deleteTree(transaction);
    }

    private void scrubRuntimeConfiguration(Profile profile) throws IOException {
        IOException failure = null;
        for (Path runtimeConfig : RuntimeConfigLocation.cleanupCandidates(paths, profile)) {
            if (!Files.exists(runtimeConfig, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                new RuntimeConfigLease(runtimeConfig).close();
            } catch (IOException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }

    private void rollback(UUID profileId, Path stagedProfile, boolean profileMoved,
            Path stagedRuntime, boolean runtimeMoved, boolean credentialExisted,
            boolean credentialDeleted, SecureChars previousSecret, Throwable primary) {
        boolean complete = true;
        if (credentialExisted && credentialDeleted && previousSecret != null) {
            char[] copy = previousSecret.copy();
            try { credentialStore.save(profileId, copy); }
            catch (CredentialStoreException | IllegalStateException rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
                complete = false;
            } finally { Arrays.fill(copy, '\0'); }
        }
        if (runtimeMoved) {
            try { SecureFileOperations.moveDirectory(stagedRuntime, paths.runtimeDirectory(profileId)); }
            catch (IOException rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
                complete = false;
            }
        }
        if (profileMoved) {
            try { repository.restoreStagedDelete(profileId, stagedProfile); }
            catch (IOException rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
                complete = false;
            }
        }
        if (complete) {
            try { SecureFileOperations.deleteTree(stagedProfile.getParent()); }
            catch (IOException cleanupFailure) { primary.addSuppressed(cleanupFailure); }
        } else {
            LOG.log(Level.SEVERE,
                    "Profile deletion rollback was incomplete; its PREPARED transaction was retained for recovery",
                    primary);
        }
    }


    private static void validateTransactionName(Path transaction, UUID profileId) throws IOException {
        Path fileName = transaction.getFileName();
        if (fileName == null) throw new IOException("Profile-deletion transaction has no file name");
        String prefix = profileId + "-";
        String value = fileName.toString();
        if (!value.startsWith(prefix)) {
            throw new IOException("Profile-deletion transaction name does not match its profile ID");
        }
        try {
            UUID.fromString(value.substring(prefix.length()));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Profile-deletion transaction name has an invalid transaction ID", ex);
        }
    }

    private static void writeMetadata(Path transaction, UUID profileId, String state) throws IOException {
        String text = "profileId=" + profileId + "\nstate=" + state + "\n";
        Path file = transaction.resolve(METADATA);
        AtomicFileWriter.write(file, text.getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(file);
    }

    private static TransactionMetadata readMetadata(Path transaction) throws IOException {
        String text = BoundedFileReader.readString(transaction.resolve(METADATA), StandardCharsets.UTF_8,
                MAX_METADATA_BYTES, "Profile-deletion transaction metadata");
        UUID profileId = null;
        String state = null;
        for (String line : text.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if ("profileId".equals(key)) profileId = UUID.fromString(value);
            if ("state".equals(key)) state = value;
        }
        if (profileId == null || state == null) throw new IOException("Profile-deletion metadata is incomplete");
        return new TransactionMetadata(profileId, state);
    }

    private record TransactionMetadata(UUID profileId, String state) { }
}
