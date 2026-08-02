package io.github.ibcmanager.security;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

public final class SecurityTests implements TestSuite {
    @Override public String name() { return "Security and credential handling"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("redacts IBC password lines", this::redactConfig),
                new NamedTest("redacts launcher password arguments", this::redactCommand),
                new NamedTest("redacts exact runtime secrets", this::redactExact),
                new NamedTest("does not alter ordinary text", this::redactOrdinary),
                new NamedTest("SecureChars returns defensive copies", this::secureCopy),
                new NamedTest("SecureChars detects config-breaking control characters", this::secureControls),
                new NamedTest("SecureChars rejects access after close", this::secureClose),
                new NamedTest("unavailable credential store fails closed", this::unavailableStore),
                new NamedTest("DPAPI save passes plaintext only through stdin", this::dpapiSave),
                new NamedTest("DPAPI load returns protected characters", this::dpapiLoad),
                new NamedTest("DPAPI operations are scoped to profile ID", this::dpapiEntropy),
                new NamedTest("DPAPI uses explicit UTF-8 and ASCII-safe plaintext transport", this::dpapiUnicodeTransport),
                new NamedTest("DPAPI rejects invalid encrypted output", this::dpapiInvalidBase64),
                new NamedTest("DPAPI rejects blank encrypted output", this::dpapiBlankBase64),
                new NamedTest("DPAPI reports command failures without leaking secret", this::dpapiFailure),
                new NamedTest("DPAPI reports command timeout", this::dpapiTimeout),
                new NamedTest("DPAPI delete removes only the selected credential", this::dpapiDelete),
                new NamedTest("default executor captures stdout stderr and stdin", this::commandExecutorCapture),
                new NamedTest("default executor enforces timeout", this::commandExecutorTimeout),
                new NamedTest("default executor caps captured output", this::commandExecutorOutputCap),
                new NamedTest("file permission hardener applies owner-only POSIX modes", this::permissions),
                new NamedTest("Windows directory ACL grants the owner traverse permission", this::directoryAclTraverse),
                new NamedTest("runtime config lease removes password file", this::runtimeLease),
                new NamedTest("runtime config lease is idempotent", this::runtimeLeaseIdempotent));
    }

    private void redactConfig() {
        String source = "IbLoginId=user\nIbPassword = super secret\nFIXPassword=fix\nOther=keep\n";
        String result = SecretRedactor.redact(source);
        Assertions.notContains(result, "super secret", "IB password must be removed");
        Assertions.notContains(result, "FIXPassword=fix", "FIX password value must be removed");
        Assertions.contains(result, "IbPassword =[REDACTED]", "formatting before value must remain");
        Assertions.contains(result, "Other=keep", "ordinary settings must remain");
    }

    private void redactCommand() {
        for (String source : List.of(
                "StartIBC 1045 /PW:secret /Mode:paper",
                "StartIBC 1045 /PW:\"secret with spaces\" /Mode:paper",
                "StartIBC /FIXPW:fixsecret")) {
            String result = SecretRedactor.redact(source);
            Assertions.notContains(result, "secret", "command secret must be removed: " + source);
            Assertions.contains(result, "[REDACTED]", "redaction marker expected");
        }
    }

    private void redactExact() {
        String source = "alpha token-123 omega token-123";
        String result = SecretRedactor.redact(source, List.of("token-123"));
        Assertions.equals("alpha [REDACTED] omega [REDACTED]", result, "all exact occurrences must be replaced");
    }

    private void redactOrdinary() {
        String source = "normal log line /PWHAT:not-a-password";
        Assertions.equals(source, SecretRedactor.redact(source), "ordinary text must remain unchanged");
        Assertions.equals("", SecretRedactor.redact(null), "null input normalizes to blank");
    }

    private void secureCopy() {
        char[] source = "secret".toCharArray();
        try (SecureChars secure = new SecureChars(source)) {
            source[0] = 'X';
            char[] copy = secure.copy();
            Assertions.arrayEquals("secret".toCharArray(), copy, "constructor and copy must be defensive");
            copy[0] = 'Y';
            Assertions.equals("secret", secure.revealAsString(), "mutating copy must not alter secret");
        }
    }

    private void secureControls() {
        try (SecureChars ordinary = new SecureChars("ordinary password".toCharArray());
             SecureChars lineBreak = new SecureChars("bad\npassword".toCharArray());
             SecureChars nul = new SecureChars(new char[] {'b', 'a', 'd', '\0'})) {
            Assertions.isFalse(ordinary.containsConfigBreakingControl(),
                    "ordinary password must remain accepted");
            Assertions.isTrue(lineBreak.containsConfigBreakingControl(),
                    "line break must be detected without exposing the secret");
            Assertions.isTrue(nul.containsConfigBreakingControl(),
                    "NUL must be detected without exposing the secret");
        }
    }

    private void secureClose() {
        SecureChars secure = new SecureChars("secret".toCharArray());
        secure.close();
        secure.close();
        Assertions.throwsType(IllegalStateException.class, secure::copy, "copy after close must fail");
        Assertions.throwsType(IllegalStateException.class, secure::revealAsString, "reveal after close must fail");
    }

    private void unavailableStore() {
        UnavailableCredentialStore store = new UnavailableCredentialStore("disabled");
        Assertions.isFalse(store.isAvailable(), "unavailable store must report false");
        Assertions.isFalse(store.exists(UUID.randomUUID()), "unavailable store must have no entries");
        Assertions.throwsType(CredentialStoreException.class,
                () -> store.save(UUID.randomUUID(), "x".toCharArray()), "save must fail closed");
        Assertions.throwsType(CredentialStoreException.class,
                () -> store.load(UUID.randomUUID()), "load must fail closed");
    }

    private void dpapiSave() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-save");
        try {
            FakeExecutor executor = new FakeExecutor(new CommandResult(0, "AQID", "", false));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(
                    new AppPaths(root), executor, OperatingSystem.WINDOWS);
            UUID id = UUID.randomUUID();
            char[] secret = "S3cret! with spaces".toCharArray();
            store.save(id, secret);
            Assertions.equals("S3cret! with spaces", executor.calls.get(0).stdin(), "plaintext must be supplied via stdin");
            Assertions.notContains(String.join(" ", executor.calls.get(0).command()), "S3cret", "plaintext must not appear in command args");
            Assertions.equals("AQID\n", Files.readString(new AppPaths(root).credentialFile(id)), "only ciphertext must be persisted");
            Assertions.isTrue(store.exists(id), "stored credential must exist");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiLoad() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-load");
        try {
            AppPaths paths = new AppPaths(root);
            UUID id = UUID.randomUUID();
            Files.createDirectories(paths.credentials());
            Files.writeString(paths.credentialFile(id), "AQID\n", StandardCharsets.US_ASCII);
            String plaintext = Base64.getEncoder().encodeToString(
                    "decrypted value".getBytes(StandardCharsets.UTF_8));
            FakeExecutor executor = new FakeExecutor(new CommandResult(0, plaintext, "", false));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(paths, executor, OperatingSystem.WINDOWS);
            try (SecureChars secret = store.load(id)) {
                Assertions.equals("decrypted value", secret.revealAsString(), "decrypted stdout must become SecureChars");
            }
            Assertions.equals("AQID", executor.calls.get(0).stdin(), "ciphertext must be supplied through stdin");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiEntropy() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-entropy");
        try {
            FakeExecutor executor = new FakeExecutor(
                    new CommandResult(0, "AQID", "", false),
                    new CommandResult(0, "AQID", "", false));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(new AppPaths(root), executor, OperatingSystem.WINDOWS);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.save(first, "a".toCharArray());
            store.save(second, "b".toCharArray());
            String firstScript = decodeScript(executor.calls.get(0).command());
            String secondScript = decodeScript(executor.calls.get(1).command());
            Assertions.contains(firstScript, first.toString(), "first profile ID must be in DPAPI entropy");
            Assertions.contains(secondScript, second.toString(), "second profile ID must be in DPAPI entropy");
            Assertions.notEquals(firstScript, secondScript, "profile-scoped DPAPI scripts must differ");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiUnicodeTransport() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-unicode");
        try {
            String password = "pässwörd Ω 東京";
            String encodedPlaintext = Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
            FakeExecutor executor = new FakeExecutor(
                    new CommandResult(0, "AQID", "", false),
                    new CommandResult(0, encodedPlaintext, "", false));
            AppPaths paths = new AppPaths(root);
            UUID id = UUID.randomUUID();
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(
                    paths, executor, OperatingSystem.WINDOWS);
            store.save(id, password.toCharArray());
            try (SecureChars loaded = store.load(id)) {
                Assertions.equals(password, loaded.revealAsString(),
                        "UTF-8 password must survive the DPAPI transport boundary");
            }
            for (Call call : executor.calls) {
                String script = decodeScript(call.command());
                Assertions.contains(script, "[Console]::InputEncoding=[Text.UTF8Encoding]::new($false)",
                        "PowerShell stdin must be pinned to UTF-8");
                Assertions.contains(script, "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)",
                        "PowerShell stdout must be pinned to UTF-8");
            }
            String decryptScript = decodeScript(executor.calls.get(1).command());
            Assertions.contains(decryptScript, "[Convert]::ToBase64String($b)",
                    "decrypted bytes must cross stdout as ASCII-safe Base64");
            Assertions.notContains(decryptScript, "UTF8.GetString($b)",
                    "decrypted plaintext must not be written directly to PowerShell stdout");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiInvalidBase64() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-invalid");
        try {
            FakeExecutor executor = new FakeExecutor(new CommandResult(0, "not base64!", "", false));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(new AppPaths(root), executor, OperatingSystem.WINDOWS);
            Assertions.throwsType(CredentialStoreException.class,
                    () -> store.save(UUID.randomUUID(), "x".toCharArray()), "invalid encrypted output must fail");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiBlankBase64() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-blank");
        try {
            FakeExecutor executor = new FakeExecutor(new CommandResult(0, "   ", "", false));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(
                    new AppPaths(root), executor, OperatingSystem.WINDOWS);
            CredentialStoreException error = Assertions.throwsType(CredentialStoreException.class,
                    () -> store.save(UUID.randomUUID(), "x".toCharArray()),
                    "blank encrypted output must fail closed");
            Assertions.contains(error.getMessage(), "empty encrypted data",
                    "blank output must have a precise diagnostic");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiFailure() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-failure");
        try {
            FakeExecutor executor = new FakeExecutor(new CommandResult(1, "", "IbPassword=leak\n/PW:other", false));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(new AppPaths(root), executor, OperatingSystem.WINDOWS);
            CredentialStoreException error = Assertions.throwsType(CredentialStoreException.class,
                    () -> store.save(UUID.randomUUID(), "secret".toCharArray()), "nonzero process result must fail");
            Assertions.notContains(error.getMessage(), "leak", "error must redact config secret");
            Assertions.notContains(error.getMessage(), "other", "error must redact command secret");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiTimeout() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-timeout");
        try {
            FakeExecutor executor = new FakeExecutor(new CommandResult(-1, "", "", true));
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(new AppPaths(root), executor, OperatingSystem.WINDOWS);
            CredentialStoreException error = Assertions.throwsType(CredentialStoreException.class,
                    () -> store.save(UUID.randomUUID(), "secret".toCharArray()), "timeout must fail");
            Assertions.contains(error.getMessage().toLowerCase(java.util.Locale.ROOT), "timed out", "timeout message expected");
        } finally { TestSupport.deleteTree(root); }
    }

    private void dpapiDelete() throws Exception {
        Path root = TestSupport.tempDirectory("dpapi-delete");
        try {
            AppPaths paths = new AppPaths(root);
            Files.createDirectories(paths.credentials());
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            Files.writeString(paths.credentialFile(first), "AQID");
            Files.writeString(paths.credentialFile(second), "AQID");
            WindowsDpapiCredentialStore store = new WindowsDpapiCredentialStore(paths, new FakeExecutor(), OperatingSystem.WINDOWS);
            store.delete(first);
            Assertions.isFalse(Files.exists(paths.credentialFile(first)), "selected credential must be removed");
            Assertions.fileExists(paths.credentialFile(second), "other credential must remain");
        } finally { TestSupport.deleteTree(root); }
    }

    private void commandExecutorCapture() throws Exception {
        DefaultCommandExecutor executor = new DefaultCommandExecutor();
        CommandResult result = executor.execute(TestSupport.javaCommand("capture"),
                "value\n", Duration.ofSeconds(5));
        Assertions.equals(7, result.exitCode(), "exit code must be captured");
        Assertions.contains(result.stdout(), "OUT:value", "stdout must be captured");
        Assertions.contains(result.stderr(), "ERR:value", "stderr must be captured");
        Assertions.isFalse(result.timedOut(), "completed command must not time out");
    }

    private void commandExecutorTimeout() throws Exception {
        DefaultCommandExecutor executor = new DefaultCommandExecutor();
        CommandResult result = executor.execute(TestSupport.javaCommand("sleep", "3000"),
                "", Duration.ofMillis(100));
        Assertions.isTrue(result.timedOut(), "slow command must time out");
        Assertions.equals(-1, result.exitCode(), "timed-out command uses sentinel exit code");
    }

    private void commandExecutorOutputCap() throws Exception {
        DefaultCommandExecutor executor = new DefaultCommandExecutor();
        CommandResult result = executor.execute(TestSupport.javaCommand("output", "2300000"),
                "", Duration.ofSeconds(10));
        Assertions.isTrue(result.stdout().length() <= 2 * 1024 * 1024, "captured output must be capped");
        Assertions.isTrue(result.stdout().length() > 1_000_000, "large output should be substantially captured");
    }

    private void permissions() throws Exception {
        Path root = TestSupport.tempDirectory("permissions");
        try {
            Path directory = root.resolve("private");
            FilePermissionHardener.hardenDirectory(directory);
            Path file = directory.resolve("secret.txt");
            Files.writeString(file, "secret");
            FilePermissionHardener.hardenFile(file);
            if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                Assertions.equals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory),
                        "directory mode must be owner-only");
                Assertions.equals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file),
                        "file mode must be owner-only");
            } else {
                Assertions.isTrue(true, "ACL platform exercised without POSIX mode assertion");
            }
        } finally { TestSupport.deleteTree(root); }
    }

    private void directoryAclTraverse() {
        Assertions.isTrue(FilePermissionHardener.ownerDirectoryPermissions().contains(AclEntryPermission.EXECUTE),
                "owner ACL must include FILE_TRAVERSE/EXECUTE so Windows can access child files");
    }

    private void runtimeLease() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-lease");
        try {
            Path file = root.resolve("config.ini");
            Files.writeString(file, "IbLoginId=user\nIbPassword=secret\nFIXPassword=fix\n");
            new RuntimeConfigLease(file).close();
            Assertions.isFalse(Files.exists(file), "runtime config must be deleted on lease close");
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeLeaseIdempotent() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-lease-idempotent");
        try {
            Path file = root.resolve("config.ini");
            Files.writeString(file, "IbPassword=secret\n");
            RuntimeConfigLease lease = new RuntimeConfigLease(file);
            lease.close();
            lease.close();
            Assertions.isFalse(Files.exists(file), "second close must remain harmless");
        } finally { TestSupport.deleteTree(root); }
    }

    private static String decodeScript(List<String> command) {
        int index = command.indexOf("-EncodedCommand");
        Assertions.isTrue(index >= 0 && index + 1 < command.size(), "encoded PowerShell script expected");
        byte[] bytes = Base64.getDecoder().decode(command.get(index + 1));
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    private static final class FakeExecutor implements CommandExecutor {
        private final Deque<CommandResult> results = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();
        private FakeExecutor(CommandResult... results) { this.results.addAll(List.of(results)); }
        @Override public CommandResult execute(List<String> command, String stdin, Duration timeout) {
            calls.add(new Call(List.copyOf(command), stdin, timeout));
            return results.isEmpty() ? new CommandResult(0, "", "", false) : results.removeFirst();
        }
    }

    private record Call(List<String> command, String stdin, Duration timeout) { }
}
