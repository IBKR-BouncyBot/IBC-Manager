package io.github.ibcmanager.diagnostics;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.security.SecretRedactor;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.validation.ProfileValidator;
import io.github.ibcmanager.validation.ValidationResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DiagnosticBundleService {
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AppPaths paths;
    private final ManagedConfigService configService;
    private final ProfileValidator validator;
    private final Clock clock;

    public DiagnosticBundleService(AppPaths paths, ManagedConfigService configService,
            ProfileValidator validator, Clock clock) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path create(Profile profile, ProfileStatus status) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(status, "status");
        Files.createDirectories(paths.diagnostics());
        FilePermissionHardener.hardenDirectory(paths.diagnostics());
        Instant generated = clock.instant();
        String stem = "IBC-Manager-Diagnostics-" + safeName(profile.name()) + "-" + FILE_TIME.format(generated);
        Path temporary = Files.createTempFile(paths.diagnostics(), ".diagnostics-", ".tmp");
        Path target = null;
        boolean completed = false;
        try {
            try (OutputStream stream = Files.newOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
                putText(zip, "manifest.txt", manifest(profile, status, generated));
                putText(zip, "profile.txt", profileSummary(profile));
                ValidationResult result = validator.validate(profile, true);
                putText(zip, "validation.txt", result.issues().isEmpty()
                        ? "No validation issues.\n"
                        : result.issues().stream()
                                .map(issue -> issue.severity() + " [" + issue.field() + "] " + issue.message())
                                .reduce("", (left, right) -> left + right + "\n"));
                try {
                    IbcConfigDocument document = profile.credentialMode().name().equals("EXISTING_CONFIG")
                            ? configService.loadRuntimeBase(profile)
                            : configService.loadManagedConfig(profile);
                    putText(zip, "config-redacted.ini", document.renderRedacted());
                } catch (IOException ex) {
                    putText(zip, "config-error.txt", "Could not read configuration: "
                            + SecretRedactor.redact(ex.getMessage()) + "\n");
                }
                addTail(zip, paths.profileLog(profile.id()), "profile-log-tail.txt");
                addTail(zip, paths.appLog(), "manager-log-tail.txt");
            }

            target = reserveUniqueTarget(paths.diagnostics(), stem);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            FilePermissionHardener.hardenFile(target);
            completed = true;
            return target;
        } finally {
            Files.deleteIfExists(temporary);
            if (!completed && target != null) Files.deleteIfExists(target);
        }
    }

    private static Path reserveUniqueTarget(Path directory, String stem) throws IOException {
        for (int sequence = 1; sequence <= 10_000; sequence++) {
            String suffix = sequence == 1 ? "" : "-" + sequence;
            Path candidate = directory.resolve(stem + suffix + ".zip");
            try {
                Files.createFile(candidate);
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                // Choose another deterministic suffix without overwriting an earlier diagnostic bundle.
            }
        }
        throw new IOException("Could not allocate a unique diagnostic bundle name");
    }

    private static String manifest(Profile profile, ProfileStatus status, Instant generated) {
        return "IBC Manager diagnostics\n"
                + "Generated UTC: " + generated + "\n"
                + "Manager version: " + Version.VERSION + "\n"
                + "IBC baseline: " + Version.IBC_BASELINE + "\n"
                + "Java: " + System.getProperty("java.version", "unknown") + "\n"
                + "OS: " + System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "") + "\n"
                + "Profile ID: " + profile.id() + "\n"
                + "Runtime state: " + status.state() + "\n"
                + "Process alive: " + status.processAlive() + "\n"
                + "Command port open: " + status.commandPortOpen() + "\n"
                + "API TCP port open: " + status.apiPortOpen() + "\n"
                + "PID: " + status.pid() + "\n"
                + "Status message: " + SecretRedactor.redact(status.message()) + "\n";
    }

    private static String profileSummary(Profile profile) {
        return "Name: " + profile.name() + "\n"
                + "Enabled: " + profile.enabled() + "\n"
                + "Target: " + profile.targetType() + "\n"
                + "Trading mode: " + profile.tradingMode() + "\n"
                + "TWS/Gateway version: " + profile.twsMajorVersion() + "\n"
                + "IBC path: " + profile.ibcPath() + "\n"
                + "TWS path: " + profile.twsPath() + "\n"
                + "Settings path: " + profile.twsSettingsPath() + "\n"
                + "API port: " + profile.apiPort() + "\n"
                + "Command-server port: " + profile.commandServerPort() + "\n"
                + "Bind address: " + profile.bindAddress() + "\n"
                + "Username: " + redactUsername(profile.username()) + "\n"
                + "Credential mode: " + profile.credentialMode() + "\n"
                + "Auto-start: " + profile.autoStart() + "\n";
    }

    private static String redactUsername(String username) {
        if (username == null || username.isBlank()) return "";
        if (username.length() <= 2) return "**";
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }

    private static void addTail(ZipOutputStream zip, Path file, String entryName) throws IOException {
        if (!Files.isRegularFile(file)) {
            putText(zip, entryName, "Log file does not exist.\n");
            return;
        }
        long size = Files.size(file);
        long start = Math.max(0, size - MAX_LOG_BYTES);
        byte[] data;
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            channel.position(start);
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(MAX_LOG_BYTES, size));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            buffer.flip();
            data = new byte[buffer.remaining()];
            buffer.get(data);
        }
        String text = new String(data, StandardCharsets.UTF_8);
        putText(zip, entryName, SecretRedactor.redact(text));
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safeName(String value) {
        String safe = value == null ? "profile" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        safe = safe.replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "profile" : safe;
    }
}
