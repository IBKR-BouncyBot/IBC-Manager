package io.github.ibcmanager.config;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public final class RuntimeConfigFactory implements RuntimeConfigProvider {
    private final AppPaths paths;
    private final ManagedConfigService managedConfigService;
    private final ConfigValueValidator configValueValidator = new ConfigValueValidator();

    public RuntimeConfigFactory(AppPaths paths, ManagedConfigService managedConfigService) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.managedConfigService = Objects.requireNonNull(managedConfigService, "managedConfigService");
    }

    public RuntimeConfigLease create(Profile profile, SecureChars password) throws IOException {
        Objects.requireNonNull(profile, "profile");
        if (TextSafety.containsConfigBreakingControl(profile.username())) {
            throw new IOException("The IBKR username contains a character that cannot be written safely to config.ini");
        }
        if (password != null && password.containsConfigBreakingControl()) {
            throw new IOException("The stored password contains a character that cannot be written safely to config.ini");
        }
        IbcConfigDocument document = managedConfigService.loadRuntimeBase(profile).copy();
        if (!document.formattingMatchesIbcSemantics()) {
            // Runtime files are ephemeral. Preserve IBC's authoritative full-file Properties
            // semantics rather than reproducing an ambiguous formatting scan from the source.
            document = document.canonicalizedCopy();
        }
        if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
            ManagedConfigService.applyProfile(document, profile);
        } else {
            // Current profiles generate every value in memory from the profile.
            // The legacy reader is retained solely for upgrade compatibility/tests.
            // Re-apply structured fields; no current on-disk INI is read here.
            ManagedConfigService.applyProfileControlled(document, profile);
        }

        if (profile.credentialMode() == CredentialMode.ENCRYPTED) {
            if (password == null || password.isEmpty()) throw new IOException("The stored password is empty");
            document.set("IbLoginId", profile.username());
            document.set("IbPassword", password.revealAsString());
        } else if (profile.credentialMode() == CredentialMode.MANUAL) {
            document.set("IbLoginId", profile.username());
            document.set("IbPassword", "");
        }

        IbcCompatibilityPolicy.applySafeRuntimeDefaults(document);
        String errors = java.util.stream.Stream.concat(
                        configValueValidator.validateRuntimeConfig(document).stream(),
                        IbcCompatibilityPolicy.validateForProfile(profile, document.activeSettings()).stream())
                .filter(issue -> issue.severity() == Severity.ERROR)
                .map(issue -> issue.field() + ": " + issue.message())
                .collect(java.util.stream.Collectors.joining("; "));
        if (!errors.isEmpty()) throw new IOException("Runtime IBC configuration is invalid: " + errors);

        Path target = RuntimeConfigLocation.path(profile);
        Path directory = target.getParent();
        if (directory == null) throw new IOException("Runtime configuration has no parent directory");
        FilePermissionHardener.hardenDirectory(directory);
        byte[] rendered = document.toIbcBytes();
        try {
            if (rendered.length > ManagedConfigService.MAX_CONFIG_BYTES) {
                throw new IOException("Runtime IBC configuration exceeds the "
                        + ManagedConfigService.MAX_CONFIG_BYTES + " byte safety limit");
            }
            AtomicFileWriter.write(target, rendered, false);
        } finally {
            Arrays.fill(rendered, (byte) 0);
        }
        FilePermissionHardener.hardenFile(target);
        return new RuntimeConfigLease(target);
    }

    public void cleanStale(Profile profile) throws IOException {
        IOException failure = null;
        for (Path path : RuntimeConfigLocation.cleanupCandidates(paths, profile)) {
            if (!SecureFileOperations.exists(path)) continue;
            try {
                new RuntimeConfigLease(path).close();
            } catch (IOException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }
}
