package io.github.ibcmanager.security;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WindowsDpapiCredentialStore implements CredentialStore {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final String UTF8_CONSOLE =
            "[Console]::InputEncoding=[Text.UTF8Encoding]::new($false);"
                    + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);";
    private final AppPaths paths;
    private final CommandExecutor executor;
    private final OperatingSystem operatingSystem;

    public WindowsDpapiCredentialStore(AppPaths paths) {
        this(paths, new DefaultCommandExecutor(), OperatingSystem.current());
    }

    public WindowsDpapiCredentialStore(AppPaths paths, CommandExecutor executor, OperatingSystem operatingSystem) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    }

    @Override
    public boolean isAvailable() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    @Override
    public void save(UUID profileId, char[] password) throws CredentialStoreException {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(password, "password");
        ensureAvailable();
        String plaintext = new String(password);
        try {
            CommandResult result = executor.execute(powerShellCommand(protectScript(profileId)), plaintext, COMMAND_TIMEOUT);
            verify(result, "encrypt");
            String ciphertext = result.stdout().trim();
            validateBase64(ciphertext, "encrypted data");
            Path target = paths.credentialFile(profileId);
            FilePermissionHardener.hardenDirectory(target.getParent());
            AtomicFileWriter.write(target, (ciphertext + "\n").getBytes(StandardCharsets.US_ASCII), false);
            FilePermissionHardener.hardenFile(target);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CredentialStoreException("Could not encrypt the password using Windows DPAPI", ex);
        } finally {
            plaintext = null;
        }
    }

    @Override
    public SecureChars load(UUID profileId) throws CredentialStoreException {
        Objects.requireNonNull(profileId, "profileId");
        ensureAvailable();
        Path source = paths.credentialFile(profileId);
        if (!Files.isRegularFile(source)) throw new CredentialStoreException("No stored password exists for this profile");
        byte[] plaintextBytes = null;
        char[] plaintextChars = null;
        try {
            String ciphertext = Files.readString(source, StandardCharsets.US_ASCII).trim();
            validateBase64(ciphertext, "encrypted data");
            CommandResult result = executor.execute(powerShellCommand(unprotectScript(profileId)), ciphertext, COMMAND_TIMEOUT);
            verify(result, "decrypt");
            plaintextBytes = decodeBase64(result.stdout().trim(), "decrypted password");
            plaintextChars = decodeUtf8(plaintextBytes);
            return new SecureChars(plaintextChars);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CredentialStoreException("Could not decrypt the password using Windows DPAPI", ex);
        } finally {
            if (plaintextBytes != null) Arrays.fill(plaintextBytes, (byte) 0);
            if (plaintextChars != null) Arrays.fill(plaintextChars, '\0');
        }
    }

    @Override
    public boolean exists(UUID profileId) {
        return Files.isRegularFile(paths.credentialFile(profileId));
    }

    @Override
    public void delete(UUID profileId) throws CredentialStoreException {
        try {
            Files.deleteIfExists(paths.credentialFile(profileId));
        } catch (IOException ex) {
            throw new CredentialStoreException("Could not delete the stored password", ex);
        }
    }

    private void ensureAvailable() throws CredentialStoreException {
        if (!isAvailable()) throw new CredentialStoreException("Windows DPAPI credential storage is only available on Windows");
    }

    private static void verify(CommandResult result, String operation) throws CredentialStoreException {
        if (result.timedOut()) throw new CredentialStoreException("Windows DPAPI " + operation + " operation timed out");
        if (result.exitCode() != 0) {
            throw new CredentialStoreException("Windows DPAPI " + operation + " operation failed: "
                    + SecretRedactor.redact(result.stderr()).trim());
        }
    }

    private static void validateBase64(String value, String description) throws CredentialStoreException {
        byte[] decoded = decodeBase64(value, description);
        Arrays.fill(decoded, (byte) 0);
    }

    private static byte[] decodeBase64(String value, String description) throws CredentialStoreException {
        if (value == null || value.isBlank()) {
            throw new CredentialStoreException("Windows DPAPI returned empty " + description);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new CredentialStoreException("Windows DPAPI returned invalid " + description, ex);
        }
    }

    private static char[] decodeUtf8(byte[] value) throws CredentialStoreException {
        CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } catch (CharacterCodingException ex) {
            throw new CredentialStoreException("Windows DPAPI returned a password that is not valid UTF-8", ex);
        } finally {
            if (decoded != null && decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        }
    }

    private static List<String> powerShellCommand(String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
    }

    private static String protectScript(UUID profileId) {
        String entropy = "IBCManager:v1:" + profileId;
        return UTF8_CONSOLE
                + "$ErrorActionPreference='Stop';"
                + "Add-Type -AssemblyName System.Security;"
                + "$p=[Console]::In.ReadToEnd();"
                + "$b=[Text.Encoding]::UTF8.GetBytes($p);"
                + "$e=[Text.Encoding]::UTF8.GetBytes('" + entropy + "');"
                + "$c=[Security.Cryptography.ProtectedData]::Protect($b,$e,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Console]::Out.Write([Convert]::ToBase64String($c));";
    }

    private static String unprotectScript(UUID profileId) {
        String entropy = "IBCManager:v1:" + profileId;
        return UTF8_CONSOLE
                + "$ErrorActionPreference='Stop';"
                + "Add-Type -AssemblyName System.Security;"
                + "$c=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim());"
                + "$e=[Text.Encoding]::UTF8.GetBytes('" + entropy + "');"
                + "$b=[Security.Cryptography.ProtectedData]::Unprotect($c,$e,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Console]::Out.Write([Convert]::ToBase64String($b));";
    }
}
