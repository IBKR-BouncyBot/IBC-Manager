package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.ProfileDeletionService;
import io.github.ibcmanager.app.ProfileSaveService;
import io.github.ibcmanager.app.SingleInstanceLock;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.install.IbcInstallerService;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecretRedactor;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.validation.ProfileSetValidator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ReleaseAuditTests implements TestSuite {
    @Override public String name() { return "Release security, bounds, rollback, and platform audit"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("bounded reader returns exact data", this::boundedExact),
                new NamedTest("bounded reader rejects oversized files", this::boundedOversize),
                new NamedTest("bounded reader rejects malformed UTF-8", this::boundedMalformedUtf8),
                new NamedTest("bounded reader refuses symbolic links", this::boundedSymlink),
                new NamedTest("secure directory creation refuses symbolic ancestors", this::secureDirectorySymlink),
                new NamedTest("atomic writer refuses a symbolic target", this::atomicSymlink),
                new NamedTest("profile repository ignores symbolic profile directories", this::repositorySymlink),
                new NamedTest("profile repository bounds stored profile files", this::repositoryOversize),
                new NamedTest("managed configuration bounds imported config files", this::managedConfigOversize),
                new NamedTest("runtime config cleanup never follows a symbolic link", this::runtimeLeaseSymlink),
                new NamedTest("single-instance lock refuses symbolic lock files", this::singleInstanceSymlink),
                new NamedTest("oversized process identities are removed", this::identityOversize),
                new NamedTest("symbolic process identities never expose their targets", this::identitySymlink),
                new NamedTest("process-handle identity matches the current process", this::processHandleIdentity),
                new NamedTest("command client validates host and port", this::commandValidation),
                new NamedTest("command client bounds one response line", this::commandLineCap),
                new NamedTest("command client bounds total response data", this::commandResponseCap),
                new NamedTest("command client enforces one total deadline", this::commandTotalDeadline),
                new NamedTest("runtime log buffer truncates oversized lines", this::runtimeBufferLineCap),
                new NamedTest("runtime log buffer bounds total characters", this::runtimeBufferTotalCap),
                new NamedTest("live process output bounds its character queue", this::liveOutputCap),
                new NamedTest("periodic process log bounds pending bytes", this::periodicByteCap),
                new NamedTest("periodic process log can retry a failed final write", this::periodicByteCloseRetry),
                new NamedTest("relay never deletes an arbitrary descriptor path", this::relayArbitraryPath),
                new NamedTest("relay never follows a symbolic descriptor", this::relaySymlink),
                new NamedTest("relay descriptor rejects oversized control files", this::relayDescriptorCap),
                new NamedTest("cross-role API and command port collisions are blocked", this::crossPortCollision),
                new NamedTest("secret redaction covers commented assignments", this::redactComments),
                new NamedTest("secret redaction covers structured and query values", this::redactStructured),
                new NamedTest("exact secret redaction handles overlapping values", this::redactExactOverlap),
                new NamedTest("profile save restores config after a late failure", this::profileSaveRollback),
                new NamedTest("profile deletion removes profile runtime and credential", this::profileDeleteSuccess),
                new NamedTest("profile deletion rolls back a credential failure", this::profileDeleteRollback),
                new NamedTest("incomplete profile deletion rollback retains staged data", this::profileDeleteRollbackRetains),
                new NamedTest("stale prepared deletion transaction is restored", this::stalePreparedRestore),
                new NamedTest("stale committed deletion transaction is completed", this::staleCommittedFinish),
                new NamedTest("mismatched deletion metadata cannot target another profile", this::staleTransactionMismatch),
                new NamedTest("profile editor reports invalid paths cleanly", this::profileEditorInvalidPath),
                new NamedTest("application arguments report invalid data paths cleanly", this::appInvalidDataPath),
                new NamedTest("IBC validation rejects symbolic required files", this::installerSymlink),
                new NamedTest("log tailer refuses a symbolic log source", this::logTailerSymlink));
    }

    private void boundedExact() throws Exception {
        Path root = TestSupport.tempDirectory("audit-bounded-exact");
        try {
            Path file = root.resolve("value.txt");
            Files.writeString(file, "hello €", StandardCharsets.UTF_8);
            Assertions.equals("hello €", BoundedFileReader.readString(file, StandardCharsets.UTF_8, 64, "test"),
                    "bounded UTF-8 read must preserve text");
            Assertions.equals(Files.size(file), (long) BoundedFileReader.readBytes(file, 64, "test").length,
                    "bounded byte read must preserve length");
            Assertions.isTrue(SecureFileOperations.isRegularFile(file), "fixture must remain regular");
        } finally { TestSupport.deleteTree(root); }
    }

    private void boundedOversize() throws Exception {
        Path root = TestSupport.tempDirectory("audit-bounded-large");
        try {
            Path file = root.resolve("large.bin");
            Files.write(file, new byte[65]);
            IOException error = Assertions.throwsType(IOException.class,
                    () -> BoundedFileReader.readBytes(file, 64, "test control file"),
                    "oversized control file must fail");
            Assertions.contains(error.getMessage(), "64 byte", "bound must be present in the error");
        } finally { TestSupport.deleteTree(root); }
    }

    private void boundedMalformedUtf8() throws Exception {
        Path root = TestSupport.tempDirectory("audit-bounded-utf8");
        try {
            Path file = root.resolve("bad.txt");
            Files.write(file, new byte[] {(byte) 0xC3, 0x28});
            IOException error = Assertions.throwsType(IOException.class,
                    () -> BoundedFileReader.readString(file, StandardCharsets.UTF_8, 8, "test text"),
                    "malformed UTF-8 must fail closed");
            Assertions.contains(error.getMessage(), "UTF-8", "encoding error must be explicit");
        } finally { TestSupport.deleteTree(root); }
    }

    private void boundedSymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-bounded-link");
        try {
            Path target = root.resolve("target.txt");
            Path link = root.resolve("link.txt");
            Files.writeString(target, "secret");
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.throwsType(IOException.class,
                    () -> BoundedFileReader.readString(link, StandardCharsets.UTF_8, 64, "linked file"),
                    "symbolic control file must be rejected");
            Assertions.equals("secret", Files.readString(target), "symbolic target must remain unchanged");
            Assertions.isTrue(Files.isSymbolicLink(link), "link itself must remain identifiable");
        } finally { TestSupport.deleteTree(root); }
    }

    private void secureDirectorySymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-dir-link");
        try {
            Path target = root.resolve("real");
            Path link = root.resolve("linked");
            Files.createDirectory(target);
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.throwsType(IOException.class, () -> SecureFileOperations.ensureDirectory(link.resolve("child")),
                    "symbolic directory ancestors must be rejected");
            Assertions.isFalse(Files.exists(target.resolve("child")), "target directory must not be modified");
        } finally { TestSupport.deleteTree(root); }
    }

    private void atomicSymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-atomic-link");
        try {
            Path target = root.resolve("target.txt");
            Path link = root.resolve("managed.txt");
            Files.writeString(target, "unchanged");
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.throwsType(IOException.class,
                    () -> AtomicFileWriter.write(link, "replacement".getBytes(StandardCharsets.UTF_8), false),
                    "atomic writer must reject links");
            Assertions.equals("unchanged", Files.readString(target), "link target must not be overwritten");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositorySymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-repository-link");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Files.createDirectories(paths.profiles());
            Path external = root.resolve("external");
            Files.createDirectories(external);
            if (!trySymlink(paths.profiles().resolve(UUID.randomUUID().toString()), external)) {
                Assertions.isTrue(true, "symbolic links unavailable"); return;
            }
            ProfileRepository.LoadResult result = new ProfileRepository(paths).loadAll();
            Assertions.equals(0, result.profiles().size(), "linked profile must not load");
            Assertions.isTrue(result.warnings().stream().anyMatch(value -> value.contains("symbolic")),
                    "linked profile must produce a warning");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryOversize() throws Exception {
        Path root = TestSupport.tempDirectory("audit-repository-large");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            UUID id = UUID.randomUUID();
            Files.createDirectories(paths.profileDirectory(id));
            Files.write(paths.profileFile(id), new byte[1024 * 1024 + 1]);
            ProfileRepository.LoadResult result = new ProfileRepository(paths).loadAll();
            Assertions.equals(0, result.profiles().size(), "oversized profile must not load");
            Assertions.equals(1, result.warnings().size(), "oversized profile must be diagnosed once");
            Assertions.contains(result.warnings().get(0), "safety limit", "warning must identify the bound");
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedConfigOversize() throws Exception {
        Path root = TestSupport.tempDirectory("audit-config-large");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile base = TestSupport.validProfile(root.resolve("install"));
            Path config = root.resolve("large-config.ini");
            Files.write(config, new byte[ManagedConfigService.MAX_CONFIG_BYTES + 1]);
            Profile profile = base.toBuilder().baseConfigPath(config).build();
            IOException error = Assertions.throwsType(IOException.class,
                    () -> new ManagedConfigService(paths).ensureManagedConfig(profile),
                    "oversized imported config must fail");
            Assertions.contains(error.getMessage(), "safety limit", "config bound must be explained");
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeLeaseSymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-runtime-link");
        try {
            Path target = root.resolve("target.ini");
            Path link = root.resolve("config.ini");
            Files.writeString(target, "IbPassword=secret\n");
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            IOException error = Assertions.throwsType(IOException.class, () -> new RuntimeConfigLease(link).close(),
                    "linked runtime config must be rejected");
            Assertions.contains(error.getMessage(), "symbolic", "link removal must be explicit");
            Assertions.equals("IbPassword=secret\n", Files.readString(target), "target must remain untouched");
            Assertions.isFalse(Files.exists(link), "unsafe link itself must be removed");
        } finally { TestSupport.deleteTree(root); }
    }

    private void singleInstanceSymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-lock-link");
        try {
            Path target = root.resolve("target.lock");
            Path link = root.resolve("manager.lock");
            Files.writeString(target, "owner");
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.throwsType(IOException.class, () -> SingleInstanceLock.acquire(link),
                    "linked lock file must be rejected");
            Assertions.equals("owner", Files.readString(target), "lock target must remain unchanged");
        } finally { TestSupport.deleteTree(root); }
    }

    private void identityOversize() throws Exception {
        Path root = TestSupport.tempDirectory("audit-identity-large");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            UUID id = UUID.randomUUID();
            Files.createDirectories(paths.runtimeDirectory(id));
            Files.write(paths.runtimeState(id), new byte[4097]);
            ProcessIdentityStore store = new ProcessIdentityStore(paths);
            Assertions.isTrue(store.load(id).isEmpty(), "oversized identity must not load");
            Assertions.isFalse(Files.exists(paths.runtimeState(id)), "invalid identity must be removed");
            Assertions.isTrue(store.reattach(id).isEmpty(), "removed identity must not reattach");
        } finally { TestSupport.deleteTree(root); }
    }

    private void identitySymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-identity-link");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            UUID id = UUID.randomUUID();
            Files.createDirectories(paths.runtimeDirectory(id));
            Path target = root.resolve("target.properties");
            Files.writeString(target, "pid=1\nstartedAt=2020-01-01T00:00:00Z\n");
            if (!trySymlink(paths.runtimeState(id), target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            ProcessIdentityStore store = new ProcessIdentityStore(paths);
            Assertions.isTrue(store.load(id).isEmpty(), "linked identity must not load");
            Assertions.equals("pid=1\nstartedAt=2020-01-01T00:00:00Z\n", Files.readString(target),
                    "identity target must remain untouched");
            Assertions.isFalse(Files.exists(paths.runtimeState(id)), "unsafe identity link must be removed");
        } finally { TestSupport.deleteTree(root); }
    }

    private void processHandleIdentity() {
        ProcessHandle current = ProcessHandle.current();
        ProcessHandleIdentity identity = ProcessHandleIdentity.capture(current).orElseThrow();
        Assertions.equals(current.pid(), identity.pid(), "captured PID must match");
        Assertions.isTrue(identity.resolve(Duration.ofSeconds(1)).isPresent(), "current identity must resolve");
        Assertions.isTrue(identity.matches(current, Duration.ofSeconds(1)), "current handle must match");
        Assertions.isFalse(identity.fingerprint().isBlank() && current.info().command().isPresent(),
                "available command metadata should produce a fingerprint");
        Assertions.isTrue(ProcessHandleIdentity.fingerprintsCompatible("expected", ""),
                "temporarily unavailable metadata must fall back to PID and start time");
        Assertions.isTrue(ProcessHandleIdentity.fingerprintsCompatible("expected", "expected"),
                "matching metadata must remain accepted");
        Assertions.isFalse(ProcessHandleIdentity.fingerprintsCompatible("expected", "different"),
                "an explicit fingerprint mismatch must remain rejected");
    }

    private void commandValidation() {
        IbcCommandClient client = new IbcCommandClient();
        Assertions.throwsType(IllegalArgumentException.class,
                () -> client.send(" ", 1, IbcCommand.STOP, Duration.ofSeconds(1)), "blank host must fail");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> client.send("localhost", 0, IbcCommand.STOP, Duration.ofSeconds(1)), "zero port must fail");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> client.send("localhost", 65_536, IbcCommand.STOP, Duration.ofSeconds(1)), "large port must fail");
    }

    private void commandLineCap() throws Exception {
        try (OneShotServer server = OneShotServer.start(socket -> {
            readCommands(socket);
            socket.getOutputStream().write("x".repeat(IbcCommandClient.MAX_LINE_CHARACTERS + 1)
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        })) {
            IOException error = Assertions.throwsType(IOException.class,
                    () -> new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.STOP,
                            Duration.ofSeconds(2)), "oversized line must fail");
            Assertions.contains(error.getMessage(), "line", "line bound must be explicit");
            Assertions.isTrue(server.finished(Duration.ofSeconds(2)), "server must finish");
        }
    }

    private void commandResponseCap() throws Exception {
        try (OneShotServer server = OneShotServer.start(socket -> {
            readCommands(socket);
            byte[] line = ("x".repeat(1024) + "\n").getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < 80; index++) socket.getOutputStream().write(line);
            socket.getOutputStream().flush();
        })) {
            IOException error = Assertions.throwsType(IOException.class,
                    () -> new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.STOP,
                            Duration.ofSeconds(2)), "oversized response must fail");
            Assertions.contains(error.getMessage(), "response", "response bound must be explicit");
            Assertions.isTrue(server.finished(Duration.ofSeconds(2)), "server must finish");
        }
    }

    private void commandTotalDeadline() throws Exception {
        try (OneShotServer server = OneShotServer.start(socket -> {
            readCommands(socket);
            for (int index = 0; index < 20; index++) {
                socket.getOutputStream().write('x');
                socket.getOutputStream().flush();
                Thread.sleep(50);
            }
        })) {
            long started = System.nanoTime();
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.STOP,
                    Duration.ofMillis(220));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Assertions.isTrue(elapsedMillis < 700, "trickle response must not reset the total deadline");
            Assertions.isFalse(result.success(), "partial non-acknowledgement must not succeed");
            Assertions.isTrue(!result.response().isEmpty(), "partial data may be returned for diagnostics");
        }
    }

    private void runtimeBufferLineCap() {
        RuntimeLogBuffer buffer = new RuntimeLogBuffer(10);
        buffer.append("x".repeat(100_000));
        String stored = buffer.snapshot().get(0);
        Assertions.isTrue(stored.length() < 100_000, "oversized line must be shortened");
        Assertions.contains(stored, "truncated", "truncation marker must be retained");
        Assertions.equals(1, buffer.size(), "one logical line must remain");
    }

    private void runtimeBufferTotalCap() {
        RuntimeLogBuffer buffer = new RuntimeLogBuffer(10_000);
        for (int index = 0; index < 200; index++) buffer.append("x".repeat(40_000));
        int characters = buffer.snapshot().stream().mapToInt(String::length).sum();
        Assertions.isTrue(characters <= 4 * 1024 * 1024, "runtime buffer must respect total bound");
        Assertions.isTrue(buffer.size() < 200, "old lines must be evicted");
        Assertions.isFalse(buffer.snapshot().isEmpty(), "latest lines must remain");
    }

    private void liveOutputCap() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 100; index++) text.append("x".repeat(70_000)).append('\n');
        LiveProcessOutput output = new LiveProcessOutput(
                new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)));
        output.await(Duration.ofSeconds(5));
        List<String> lines = output.drain();
        int characters = lines.stream().mapToInt(String::length).sum();
        Assertions.isTrue(characters <= LiveProcessOutput.MAX_QUEUED_CHARACTERS + 256,
                "live queue must respect total bound");
        Assertions.isTrue(lines.stream().anyMatch(value -> value.contains("truncated")),
                "oversized live lines must be marked");
        Assertions.isTrue(lines.stream().allMatch(value -> value.length()
                        <= LiveProcessOutput.MAX_UNTERMINATED_LINE_CHARACTERS + 128),
                "no live line may remain unbounded");
        Assertions.isFalse(lines.isEmpty(), "bounded output must retain recent data");
    }

    private void periodicByteCap() throws Exception {
        Path root = TestSupport.tempDirectory("audit-byte-log");
        try {
            Path log = root.resolve("process.log");
            byte[] data = new byte[PeriodicByteLog.MAX_PENDING_BYTES + 1024];
            Arrays.fill(data, (byte) 'a');
            try (PeriodicByteLog output = new PeriodicByteLog(log, Duration.ofHours(1), null)) {
                output.append(data, 0, data.length);
            }
            long size = Files.size(log);
            Assertions.isTrue(size > PeriodicByteLog.MAX_PENDING_BYTES,
                    "drop marker plus bounded payload must be written");
            Assertions.isTrue(size < PeriodicByteLog.MAX_PENDING_BYTES + 512,
                    "pending output must remain tightly bounded");
            String prefix = Files.readString(log, StandardCharsets.UTF_8).substring(0, 20);
            Assertions.contains(prefix, "IBC Manager dropped", "drop marker must be written");
            Assertions.isTrue(SecureFileOperations.isRegularFile(log), "log must remain regular");
        } finally { TestSupport.deleteTree(root); }
    }


    private void periodicByteCloseRetry() throws Exception {
        Path root = TestSupport.tempDirectory("audit-byte-log-retry");
        try {
            Path log = root.resolve("process.log");
            PeriodicByteLog output = new PeriodicByteLog(log, Duration.ofHours(1), null);
            output.append("retry-me\n".getBytes(StandardCharsets.UTF_8), 0, "retry-me\n".length());
            Files.createDirectory(log);
            Assertions.throwsType(IOException.class, output::close,
                    "failed final log write must surface");
            Files.delete(log);
            output.close();
            Assertions.equals("retry-me\n", Files.readString(log, StandardCharsets.UTF_8),
                    "pending bytes must remain available for a close retry");
        } finally { TestSupport.deleteTree(root); }
    }

    private void relayArbitraryPath() throws Exception {
        Path root = TestSupport.tempDirectory("audit-relay-arbitrary");
        try {
            Path arbitrary = root.resolve("important.txt");
            Files.writeString(arbitrary, "keep me");
            Assertions.equals(64, BufferedProcessRelay.run(new String[] {arbitrary.toString()}),
                    "unexpected descriptor name must be rejected as usage error");
            Assertions.fileExists(arbitrary, "arbitrary file must not be deleted");
            Assertions.equals("keep me", Files.readString(arbitrary), "arbitrary content must remain");
        } finally { TestSupport.deleteTree(root); }
    }

    private void relaySymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-relay-link");
        try {
            Path target = root.resolve("target.bin");
            Files.writeString(target, "keep me");
            Path link = root.resolve(".ibc-manager-process-relay-" + UUID.randomUUID() + ".bin");
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.equals(65, BufferedProcessRelay.run(new String[] {link.toString()}),
                    "symbolic descriptor must fail closed");
            Assertions.equals("keep me", Files.readString(target), "descriptor target must remain unchanged");
            Assertions.isTrue(Files.isSymbolicLink(link), "symbolic descriptor itself must not be followed or deleted");
        } finally { TestSupport.deleteTree(root); }
    }

    private void relayDescriptorCap() throws Exception {
        Path root = TestSupport.tempDirectory("audit-relay-large");
        try {
            Path descriptor = root.resolve(".ibc-manager-process-relay-" + UUID.randomUUID() + ".bin");
            try (var channel = java.nio.channels.FileChannel.open(descriptor,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                channel.position(ProcessRelayDescriptor.MAX_DESCRIPTOR_BYTES);
                channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
            }
            IOException error = Assertions.throwsType(IOException.class,
                    () -> ProcessRelayDescriptor.read(descriptor), "oversized descriptor must fail");
            Assertions.contains(error.getMessage(), "safety limit", "descriptor bound must be explicit");
            Assertions.fileExists(descriptor, "reader must not delete its input");
        } finally { TestSupport.deleteTree(root); }
    }

    private void crossPortCollision() throws Exception {
        Path root = TestSupport.tempDirectory("audit-cross-port");
        try {
            Profile first = TestSupport.validProfile(root.resolve("one"));
            Profile second = TestSupport.validProfile(root.resolve("two")).toBuilder()
                    .name("Second")
                    .apiPort(first.commandServerPort())
                    .commandServerPort(8000)
                    .build();
            var result = new ProfileSetValidator().validate(List.of(first, second));
            Assertions.isFalse(result.isValid(), "API/command cross-role collision must be invalid");
            Assertions.equals(1L, result.errorCount(), "one cross-role conflict must be reported");
            Assertions.contains(result.issues().get(0).message(), "conflicts", "conflict must be explicit");
            Assertions.contains(result.issues().get(0).message(), first.name(), "other profile must be named");
        } finally { TestSupport.deleteTree(root); }
    }

    private void redactComments() {
        String source = "; IbPassword = secret\n# Token: token-value\nOther=keep";
        String result = SecretRedactor.redact(source);
        Assertions.notContains(result, "secret", "commented IBC password must be removed");
        Assertions.notContains(result, "token-value", "commented token must be removed");
        Assertions.contains(result, "[REDACTED]", "redaction marker must be present");
        Assertions.contains(result, "Other=keep", "ordinary setting must remain");
    }

    private void redactStructured() {
        String source = "{\"password\":\"secret\",'api_key':'key'} https://host/?token=abc&x=1";
        String result = SecretRedactor.redact(source);
        Assertions.notContains(result, "secret", "JSON password must be removed");
        Assertions.notContains(result, "'key'", "structured API key must be removed");
        Assertions.notContains(result, "token=abc", "query token must be removed");
        Assertions.contains(result, "x=1", "ordinary query value must remain");
        Assertions.isTrue(result.chars().filter(value -> value == '[').count() >= 3,
                "each secret must receive a marker");
    }

    private void redactExactOverlap() {
        String result = SecretRedactor.redact("abcdef and abc", List.of("abc", "abcdef"));
        Assertions.notContains(result, "abcdef", "long exact secret must be removed first");
        Assertions.notContains(result, "abc", "short exact secret must also be removed");
        Assertions.equals("[REDACTED] and [REDACTED]", result, "overlapping values must redact deterministically");
    }

    private void profileSaveRollback() throws Exception {
        Path root = TestSupport.tempDirectory("audit-save-rollback");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            ManagedConfigService configs = new ManagedConfigService(paths);
            Profile previous = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            repository.save(previous);
            credentials.save(previous.id(), "old-password".toCharArray());
            configs.ensureManagedConfig(previous);
            byte[] originalConfig = Files.readAllBytes(configs.managedConfigPath(previous));
            Profile updated = previous.toBuilder().name("Changed").credentialMode(CredentialMode.MANUAL).build();
            credentials.failDelete = true;
            Assertions.throwsType(CredentialStoreException.class,
                    () -> new ProfileSaveService(repository, credentials, configs).save(previous, updated, new char[0]),
                    "late credential failure must surface");
            Profile loaded = repository.loadAll().profiles().get(0);
            Assertions.equals(previous, loaded, "previous profile must be restored");
            Assertions.isTrue(Arrays.equals(originalConfig, Files.readAllBytes(configs.managedConfigPath(previous))),
                    "previous managed config must be restored exactly");
            Assertions.equals("old-password", credentials.value(previous.id()), "previous credential must remain");
            Assertions.isTrue(credentials.exists(previous.id()), "credential must still exist");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileDeleteSuccess() throws Exception {
        Path root = TestSupport.tempDirectory("audit-delete-success");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            repository.save(profile);
            credentials.save(profile.id(), "stored".toCharArray());
            Files.createDirectories(paths.runtimeDirectory(profile.id()));
            Files.writeString(paths.runtimeDirectory(profile.id()).resolve("state.txt"), "state");
            new ProfileDeletionService(paths, repository, credentials).delete(profile);
            Assertions.isFalse(Files.exists(paths.profileDirectory(profile.id())), "profile must be removed");
            Assertions.isFalse(Files.exists(paths.runtimeDirectory(profile.id())), "runtime state must be removed");
            Assertions.isFalse(credentials.exists(profile.id()), "credential must be removed");
            Assertions.equals(0, repository.loadAll().profiles().size(), "repository must be empty");
            Assertions.isTrue(directoryEmpty(paths.deletions()), "deletion transaction must be cleaned");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileDeleteRollback() throws Exception {
        Path root = TestSupport.tempDirectory("audit-delete-rollback");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            repository.save(profile);
            credentials.save(profile.id(), "stored".toCharArray());
            Files.createDirectories(paths.runtimeDirectory(profile.id()));
            Files.writeString(paths.runtimeDirectory(profile.id()).resolve("state.txt"), "state");
            credentials.failDelete = true;
            Assertions.throwsType(CredentialStoreException.class,
                    () -> new ProfileDeletionService(paths, repository, credentials).delete(profile),
                    "credential deletion failure must abort");
            Assertions.equals(profile, repository.loadAll().profiles().get(0), "profile must be restored");
            Assertions.fileExists(paths.runtimeDirectory(profile.id()).resolve("state.txt"), "runtime must be restored");
            Assertions.equals("stored", credentials.value(profile.id()), "credential must remain");
            Assertions.isTrue(directoryEmpty(paths.deletions()), "rolled-back transaction must be cleaned");
        } finally { TestSupport.deleteTree(root); }
    }


    private void profileDeleteRollbackRetains() throws Exception {
        Path root = TestSupport.tempDirectory("audit-delete-retained");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            repository.save(profile);
            credentials.save(profile.id(), "stored".toCharArray());
            Files.createDirectories(paths.runtimeDirectory(profile.id()));
            Files.writeString(paths.runtimeDirectory(profile.id()).resolve("state.txt"), "original runtime");
            credentials.beforeDelete = () -> {
                try {
                    Files.createDirectories(paths.profileDirectory(profile.id()));
                    Files.writeString(paths.profileDirectory(profile.id()).resolve("collision.txt"), "collision");
                    Files.createDirectories(paths.runtimeDirectory(profile.id()));
                    Files.writeString(paths.runtimeDirectory(profile.id()).resolve("collision.txt"), "collision");
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            };
            credentials.failDelete = true;
            CredentialStoreException failure = Assertions.throwsType(CredentialStoreException.class,
                    () -> new ProfileDeletionService(paths, repository, credentials).delete(profile),
                    "rollback collision must preserve the original deletion failure");
            Assertions.isTrue(failure.getSuppressed().length >= 2,
                    "failed profile and runtime restoration must be retained as suppressed diagnostics");
            Path transaction;
            try (var entries = Files.list(paths.deletions())) {
                transaction = entries.findFirst().orElseThrow();
            }
            Assertions.fileExists(transaction.resolve("profile").resolve("profile.properties"),
                    "staged profile must not be deleted after rollback failure");
            Assertions.fileExists(transaction.resolve("runtime").resolve("state.txt"),
                    "staged runtime data must not be deleted after rollback failure");
            Assertions.equals("PREPARED", readTransactionState(transaction),
                    "retained transaction must remain recoverable");

            SecureFileOperations.deleteTree(paths.profileDirectory(profile.id()));
            SecureFileOperations.deleteTree(paths.runtimeDirectory(profile.id()));
            credentials.beforeDelete = null;
            credentials.failDelete = false;
            new ProfileDeletionService(paths, repository, credentials).cleanupStaleTransactions();
            Assertions.equals(profile, repository.loadAll().profiles().get(0),
                    "startup recovery must restore the staged profile");
            Assertions.equals("original runtime",
                    Files.readString(paths.runtimeDirectory(profile.id()).resolve("state.txt")),
                    "startup recovery must restore staged runtime data");
            Assertions.isTrue(directoryEmpty(paths.deletions()),
                    "recovered transaction must be removed");
        } finally { TestSupport.deleteTree(root); }
    }

    private static String readTransactionState(Path transaction) throws IOException {
        for (String line : Files.readAllLines(transaction.resolve("transaction.properties"))) {
            if (line.startsWith("state=")) return line.substring("state=".length());
        }
        return "";
    }

    private void stalePreparedRestore() throws Exception {
        Path root = TestSupport.tempDirectory("audit-delete-prepared");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            repository.save(profile);
            Path transaction = paths.deletions().resolve(profile.id() + "-" + UUID.randomUUID());
            Files.createDirectories(transaction);
            repository.stageDelete(profile.id(), transaction.resolve("profile"));
            Files.writeString(transaction.resolve("transaction.properties"),
                    "profileId=" + profile.id() + "\nstate=PREPARED\n");
            new ProfileDeletionService(paths, repository, credentials).cleanupStaleTransactions();
            Assertions.equals(profile, repository.loadAll().profiles().get(0), "prepared profile must be restored");
            Assertions.isFalse(Files.exists(transaction), "prepared transaction must be removed");
            Assertions.directoryExists(paths.profileDirectory(profile.id()), "profile directory must return");
            Assertions.isFalse(credentials.exists(profile.id()), "unrelated credential state must remain absent");
            Assertions.isTrue(directoryEmpty(paths.deletions()), "transaction root must be empty");
        } finally { TestSupport.deleteTree(root); }
    }

    private void staleCommittedFinish() throws Exception {
        Path root = TestSupport.tempDirectory("audit-delete-committed");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            repository.save(profile);
            credentials.save(profile.id(), "stored".toCharArray());
            Path transaction = paths.deletions().resolve(profile.id() + "-" + UUID.randomUUID());
            Files.createDirectories(transaction);
            repository.stageDelete(profile.id(), transaction.resolve("profile"));
            Files.writeString(transaction.resolve("transaction.properties"),
                    "profileId=" + profile.id() + "\nstate=COMMITTED\n");
            new ProfileDeletionService(paths, repository, credentials).cleanupStaleTransactions();
            Assertions.isFalse(credentials.exists(profile.id()), "committed credential deletion must finish");
            Assertions.isFalse(Files.exists(transaction), "committed transaction must be removed");
            Assertions.equals(0, repository.loadAll().profiles().size(), "committed profile must stay deleted");
            Assertions.isFalse(Files.exists(paths.profileDirectory(profile.id())), "profile must not be restored");
            Assertions.isTrue(directoryEmpty(paths.deletions()), "transaction root must be empty");
        } finally { TestSupport.deleteTree(root); }
    }

    private void staleTransactionMismatch() throws Exception {
        Path root = TestSupport.tempDirectory("audit-delete-mismatch");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            repository.save(profile);
            credentials.save(profile.id(), "stored".toCharArray());
            Path transaction = paths.deletions().resolve(UUID.randomUUID() + "-" + UUID.randomUUID());
            Files.createDirectories(transaction);
            Files.writeString(transaction.resolve("transaction.properties"),
                    "profileId=" + profile.id() + "\nstate=COMMITTED\n");
            new ProfileDeletionService(paths, repository, credentials).cleanupStaleTransactions();
            Assertions.equals(profile, repository.loadAll().profiles().get(0),
                    "mismatched transaction must not remove the profile");
            Assertions.equals("stored", credentials.value(profile.id()),
                    "mismatched transaction must not remove the credential");
            Assertions.directoryExists(transaction,
                    "invalid transaction must remain available for manual diagnosis");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileEditorInvalidPath() throws Exception {
        var method = Class.forName("io.github.ibcmanager.ui.ProfileEditorDialog")
                .getDeclaredMethod("toPath", String.class);
        method.setAccessible(true);
        InvocationTargetException error = Assertions.throwsType(InvocationTargetException.class,
                () -> method.invoke(null, "bad\0path"), "invalid profile path must be reported");
        Assertions.isTrue(error.getCause() instanceof IllegalArgumentException,
                "profile path error must be user-facing IllegalArgumentException");
        Assertions.notContains(String.valueOf(error.getCause().getMessage()), "\0",
                "invalid profile path message must not echo control characters");
    }

    private void appInvalidDataPath() throws Exception {
        var method = Class.forName("io.github.ibcmanager.app.IbcManagerApp")
                .getDeclaredMethod("run", String[].class);
        method.setAccessible(true);
        java.io.PrintStream previousError = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        int result;
        try (java.io.PrintStream temporary = new java.io.PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(temporary);
            result = (Integer) method.invoke(null, (Object) new String[] {"--data-dir", "bad\0path"});
        } finally {
            System.setErr(previousError);
        }
        String message = captured.toString(StandardCharsets.UTF_8);
        Assertions.equals(2, result, "invalid data path must produce usage error");
        Assertions.isTrue(result != 4, "invalid data path must not become a generic startup failure");
        Assertions.notContains(message, "\0", "invalid data path diagnostics must not echo control characters");
        Assertions.contains(message, "invalid on this operating system", "diagnostic must remain actionable");
    }

    private void installerSymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-installer-link");
        try {
            createMinimalIbc(root);
            Path target = root.resolve("real-version");
            Files.move(root.resolve("version"), target);
            if (!trySymlink(root.resolve("version"), target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.isFalse(IbcInstallerService.isValidInstallation(root),
                    "symbolic required installer file must be rejected");
            Assertions.equals("3.24.1", Files.readString(target), "target version file must remain unchanged");
            Assertions.isTrue(Files.isSymbolicLink(root.resolve("version")), "link must remain visible");
        } finally { TestSupport.deleteTree(root); }
    }

    private void logTailerSymlink() throws Exception {
        Path root = TestSupport.tempDirectory("audit-log-link");
        try {
            Path target = root.resolve("target.log");
            Path link = root.resolve("profile.log");
            Files.writeString(target, "secret\n");
            if (!trySymlink(link, target)) { Assertions.isTrue(true, "symbolic links unavailable"); return; }
            Assertions.throwsType(IOException.class, () -> new LogTailer(link).readNewLines(),
                    "linked log must be rejected");
            Assertions.equals("secret\n", Files.readString(target), "log target must remain untouched");
        } finally { TestSupport.deleteTree(root); }
    }

    private static boolean trySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            return false;
        }
    }

    private static void readCommands(Socket socket) throws IOException {
        var reader = socket.getInputStream();
        int newlines = 0;
        while (newlines < 2) {
            int value = reader.read();
            if (value < 0) return;
            if (value == '\n') newlines++;
        }
    }

    private static boolean directoryEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return true;
        try (var entries = Files.list(directory)) { return entries.findAny().isEmpty(); }
    }

    private static void createMinimalIbc(Path root) throws IOException {
        Files.createDirectories(root.resolve("scripts"));
        Files.writeString(root.resolve("version"), "3.24.1");
        Files.writeString(root.resolve("config.ini"), "TradingMode=paper\n");
        Files.writeString(root.resolve("LICENSE.txt"), "GPL-3.0\n");
        Files.writeString(root.resolve("scripts/StartIBC.bat"), "@echo off\r\nrem IBC.jar\r\n");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(root.resolve("IBC.jar")))) {
            for (String name : List.of("ibcalpha/ibc/IbcTws.class", "ibcalpha/ibc/IbcGateway.class")) {
                jar.putNextEntry(new JarEntry(name));
                jar.write(new byte[] {0});
                jar.closeEntry();
            }
        }
    }

    @FunctionalInterface
    private interface SocketAction { void accept(Socket socket) throws Exception; }

    private static final class OneShotServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch finished = new CountDownLatch(1);

        private OneShotServer(ServerSocket server, SocketAction action) {
            this.server = server;
            this.thread = new Thread(() -> {
                try (Socket socket = server.accept()) { action.accept(socket); }
                catch (Exception ignored) { }
                finally { finished.countDown(); }
            }, "ibc-manager-audit-server");
            thread.setDaemon(true);
            thread.start();
        }

        static OneShotServer start(SocketAction action) throws IOException {
            return new OneShotServer(new ServerSocket(0), action);
        }

        int port() { return server.getLocalPort(); }
        boolean finished(Duration timeout) throws InterruptedException {
            return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public void close() throws IOException {
            server.close();
            try { thread.join(1000); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private final Map<UUID, char[]> values = new java.util.HashMap<>();
        private boolean failDelete;
        private Runnable beforeDelete;
        @Override public boolean isAvailable() { return true; }
        @Override public synchronized void save(UUID profileId, char[] password) {
            char[] old = values.put(profileId, Arrays.copyOf(password, password.length));
            if (old != null) Arrays.fill(old, '\0');
        }
        @Override public synchronized SecureChars load(UUID profileId) throws CredentialStoreException {
            char[] value = values.get(profileId);
            if (value == null) throw new CredentialStoreException("missing credential");
            return new SecureChars(value);
        }
        @Override public synchronized boolean exists(UUID profileId) { return values.containsKey(profileId); }
        @Override public synchronized void delete(UUID profileId) throws CredentialStoreException {
            if (beforeDelete != null) beforeDelete.run();
            if (failDelete) throw new CredentialStoreException("simulated credential deletion failure");
            char[] old = values.remove(profileId);
            if (old != null) Arrays.fill(old, '\0');
        }
        synchronized String value(UUID profileId) {
            char[] value = values.get(profileId);
            return value == null ? "" : new String(value);
        }
    }
}
