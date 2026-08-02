package io.github.ibcmanager.config;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimeConfigFactory implements RuntimeConfigProvider {
    private final AppPaths paths;
    private final ManagedConfigService managedConfigService;

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
        ManagedConfigService.applyProfile(document, profile);

        if (profile.credentialMode() == CredentialMode.ENCRYPTED) {
            if (password == null || password.isEmpty()) throw new IOException("The stored password is empty");
            document.set("IbLoginId", profile.username());
            document.set("IbPassword", password.revealAsString());
        } else if (profile.credentialMode() == CredentialMode.MANUAL) {
            document.set("IbLoginId", profile.username());
            document.set("IbPassword", "");
        }

        Path directory = paths.runtimeDirectory(profile.id());
        FilePermissionHardener.hardenDirectory(directory);
        Path target = directory.resolve("config.ini");
        AtomicFileWriter.write(target, document.render().getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(target);
        return new RuntimeConfigLease(target);
    }

    public void cleanStale(Profile profile) throws IOException {
        Path path = paths.runtimeDirectory(profile.id()).resolve("config.ini");
        if (Files.exists(path)) new RuntimeConfigLease(path).close();
    }
}
