package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RuntimeComponentTests implements TestSuite {
    @Override public String name() { return "Runtime launch, process, ports, commands, and logs"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("launch specification is immutable", this::launchSpecImmutable),
                new NamedTest("Windows launch script delegates to official StartIBC", this::launchScript),
                new NamedTest("launch script contains no credential arguments", this::launchScriptNoCredentials),
                new NamedTest("launch script rejects unsafe batch values", this::launchScriptUnsafe),
                new NamedTest("launching IBC is rejected on non-Windows systems", this::launchScriptNonWindows),
                new NamedTest("default process launcher captures combined output", this::defaultLauncher),
                new NamedTest("managed Java process reports lifecycle and exit code", this::managedProcess),
                new NamedTest("process tree terminator kills the exact root and descendants", this::processTree),
                new NamedTest("process identity store round-trips identity", this::identityRoundTrip),
                new NamedTest("process identity store reattaches only matching start time", this::identityReattach),
                new NamedTest("process identity store rejects corrupt and stale identities", this::identityReject),
                new NamedTest("process identity store deletes only selected identity", this::identityDelete),
                new NamedTest("IBC command client sends command and EXIT and accepts OK", this::commandSuccess),
                new NamedTest("IBC command client reports command rejection", this::commandError),
                new NamedTest("IBC command client does not treat Goodbye as command success", this::commandGoodbyeOnly),
                new NamedTest("IBC command client returns partial success after response timeout", this::commandPartialTimeout),
                new NamedTest("IBC command client propagates an empty response timeout", this::commandEmptyTimeout),
                new NamedTest("IBC command client rejects invalid timeout values", this::commandInvalidTimeout),
                new NamedTest("log tailer handles missing and empty files", this::tailMissing),
                new NamedTest("log tailer emits only complete appended lines", this::tailPartial),
                new NamedTest("log tailer preserves UTF-8 characters split across appends", this::tailUtf8Split),
                new NamedTest("log tailer normalizes CRLF and CR", this::tailLineEndings),
                new NamedTest("log tailer detects truncation", this::tailTruncate),
                new NamedTest("log tailer caps large catch-up reads", this::tailCap),
                new NamedTest("log tailer can reset to the current end", this::tailReset),
                new NamedTest("runtime log buffer enforces capacity", this::bufferCapacity),
                new NamedTest("runtime log buffer supports concurrent append", this::bufferConcurrent),
                new NamedTest("IBC log parser recognizes normal states", this::stateParserNormal),
                new NamedTest("IBC log parser recognizes failures and reset", this::stateParserError),
                new NamedTest("TCP probe distinguishes open closed and invalid ports", this::tcpProbe));
    }

    private void launchSpecImmutable() {
        List<String> command = new ArrayList<>(List.of("one"));
        Map<String, String> environment = new java.util.HashMap<>(Map.of("A", "B"));
        LaunchSpec spec = new LaunchSpec(command, Path.of("."), environment, Path.of("launch"), "display");
        command.add("two");
        environment.put("C", "D");
        Assertions.equals(List.of("one"), spec.command(), "command must be defensively copied");
        Assertions.equals(Map.of("A", "B"), spec.environment(), "environment must be defensively copied");
        Assertions.throwsType(UnsupportedOperationException.class, () -> spec.command().add("x"),
                "command view must be immutable");
    }

    private void launchScript() throws Exception {
        Path root = TestSupport.tempDirectory("launch-script");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Path runtimeConfig = paths.runtimeDirectory(profile.id()).resolve("config.ini");
            Files.createDirectories(runtimeConfig.getParent());
            Files.writeString(runtimeConfig, "TradingMode=paper\n");
            LaunchSpec spec = new LaunchScriptFactory(paths, OperatingSystem.WINDOWS).create(profile, runtimeConfig);
            String script = Files.readString(spec.launchScript());
            Assertions.contains(script, "chcp 65001 >nul", "batch file must select UTF-8 before reading paths");
            Assertions.contains(script, "setlocal DisableDelayedExpansion", "delayed expansion must be disabled");
            Assertions.contains(script, "StartIBC.bat", "official launcher must be called");
            Assertions.contains(script, "\"/Gateway\"", "Gateway mode argument must be supplied");
            Assertions.contains(script, "\"/TwsPath:" + profile.twsPath() + "\"", "TWS path must be supplied");
            Assertions.contains(script, "\"/TwsSettingsPath:" + profile.twsSettingsPath() + "\"", "settings path must be supplied");
            Assertions.contains(script, "\"/IbcPath:" + profile.ibcPath() + "\"", "IBC path must be supplied");
            Assertions.contains(script, "\"/Config:" + runtimeConfig + "\"", "runtime config must be supplied");
            Assertions.contains(script, "\"/Mode:paper\"", "trading mode must be explicit");
            Assertions.equals(List.of("cmd.exe", "/d", "/s", "/c", spec.displayCommand()), spec.command(),
                    "process command must execute only the generated script");
        } finally { TestSupport.deleteTree(root); }
    }

    private void launchScriptNoCredentials() throws Exception {
        Path root = TestSupport.tempDirectory("launch-no-secret");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Path runtime = paths.runtimeDirectory(profile.id()).resolve("config.ini");
            Files.createDirectories(runtime.getParent());
            Files.writeString(runtime, "IbPassword=top-secret\n");
            LaunchSpec spec = new LaunchScriptFactory(paths, OperatingSystem.WINDOWS).create(profile, runtime);
            String script = Files.readString(spec.launchScript());
            Assertions.notContains(script, "top-secret", "runtime password must not enter launch script");
            Assertions.notContains(script.toLowerCase(java.util.Locale.ROOT), "/pw", "password argument must not be used");
            Assertions.notContains(script.toLowerCase(java.util.Locale.ROOT), "/user", "username argument must not be used");
            Assertions.notContains(spec.displayCommand(), "top-secret", "display command must remain secret-free");
        } finally { TestSupport.deleteTree(root); }
    }

    private void launchScriptUnsafe() throws Exception {
        Path root = TestSupport.tempDirectory("launch-unsafe");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Path unsafe = root.resolve("runtime%TEMP%/config.ini");
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> new LaunchScriptFactory(paths, OperatingSystem.WINDOWS).create(profile, unsafe),
                    "percent expansion in a batch argument must be rejected");
            for (String value : List.of("bad!value", "bad%value", "bad\"value", "bad\nvalue",
                    "bad\rvalue", "bad\0value", "bad\u2028value", "bad\u2029value")) {
                Assertions.throwsType(IllegalArgumentException.class, () -> LaunchScriptFactory.quote(value),
                        "unsafe quoted value must be rejected: " + value);
            }
            Assertions.equals("\"safe value\"", LaunchScriptFactory.quote("safe value"), "safe value must be quoted");
        } finally { TestSupport.deleteTree(root); }
    }

    private void launchScriptNonWindows() throws Exception {
        Path root = TestSupport.tempDirectory("launch-nonwindows");
        try {
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Assertions.throwsType(IOException.class,
                    () -> new LaunchScriptFactory(new AppPaths(root), OperatingSystem.LINUX)
                            .create(profile, root.resolve("config.ini")),
                    "version 1.0 must fail explicitly rather than attempt unsupported launch automation");
        } finally { TestSupport.deleteTree(root); }
    }

    private void defaultLauncher() throws Exception {
        Path root = TestSupport.tempDirectory("default-launcher");
        try {
            Path log = root.resolve("logs/output.log");
            LaunchSpec spec = new LaunchSpec(
                    TestSupport.javaCommand("stdout-stderr"), root, Map.of(), root.resolve("none"), "test");
            ManagedProcess process = new DefaultProcessLauncher().launch(spec, log);
            Assertions.isTrue(process.waitFor(Duration.ofSeconds(5)), "test process must exit");
            String output = Files.readString(log);
            Assertions.contains(output, "stdout", "stdout must be captured");
            Assertions.contains(output, "stderr", "stderr must be merged and captured");
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedProcess() throws Exception {
        Process raw = new ProcessBuilder(TestSupport.javaCommand("exit", "7")).start();
        ManagedProcess process = new JavaManagedProcess(raw);
        Assertions.isTrue(process.pid() > 0, "PID must be exposed");
        Assertions.isTrue(process.waitFor(Duration.ofSeconds(5)), "process must finish");
        Assertions.isFalse(process.isAlive(), "finished process must report dead");
        Assertions.equals(7, process.exitCode().orElseThrow(), "exit code must be available");
        Assertions.isTrue(process.onExit().isDone() || process.onExit().get(2, TimeUnit.SECONDS) != null,
                "onExit future must complete");
    }

    private void processTree() throws Exception {
        Process raw = new ProcessBuilder(TestSupport.javaCommand("spawn-child", "30000")).start();
        ManagedProcess process = new JavaManagedProcess(raw);
        try {
            Assertions.eventually(Duration.ofSeconds(3), () -> !process.descendants().isEmpty(),
                    "test process must create a descendant");
            List<ProcessHandle> descendants = process.descendants();
            boolean stopped = new ProcessTreeTerminator().terminate(process, Duration.ofMillis(200), Duration.ofSeconds(3));
            Assertions.isTrue(stopped, "root and descendants must stop");
            Assertions.isFalse(process.isAlive(), "exact root must be dead");
            Assertions.isTrue(descendants.stream().noneMatch(ProcessHandle::isAlive), "captured descendants must be dead");
        } finally {
            raw.destroyForcibly();
            for (ProcessHandle child : process.descendants()) child.destroyForcibly();
        }
    }

    private void identityRoundTrip() throws Exception {
        Path root = TestSupport.tempDirectory("identity-roundtrip");
        try {
            AppPaths paths = new AppPaths(root);
            ProcessIdentityStore store = new ProcessIdentityStore(paths);
            UUID id = UUID.randomUUID();
            ManagedProcess current = new ReattachedManagedProcess(ProcessHandle.current());
            store.save(id, current);
            ProcessIdentity loaded = store.load(id).orElseThrow();
            Assertions.equals(ProcessHandle.current().pid(), loaded.pid(), "PID must round-trip");
            Assertions.isTrue(Math.abs(loaded.startedAt().toEpochMilli()
                    - ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()) < 2000,
                    "start time must round-trip");
        } finally { TestSupport.deleteTree(root); }
    }

    private void identityReattach() throws Exception {
        Path root = TestSupport.tempDirectory("identity-reattach");
        try {
            AppPaths paths = new AppPaths(root);
            ProcessIdentityStore store = new ProcessIdentityStore(paths);
            UUID id = UUID.randomUUID();
            store.save(id, new ReattachedManagedProcess(ProcessHandle.current()));
            Optional<ManagedProcess> attached = store.reattach(id);
            Assertions.isTrue(attached.isPresent(), "matching live PID and start time must reattach");
            Assertions.equals(ProcessHandle.current().pid(), attached.orElseThrow().pid(), "reattached PID must match");

            Instant wrong = ProcessHandle.current().info().startInstant().orElseThrow().minusSeconds(60);
            Files.writeString(paths.runtimeState(id), "pid=" + ProcessHandle.current().pid() + "\nstartedAt=" + wrong + "\n");
            Assertions.isTrue(store.reattach(id).isEmpty(), "PID reuse or mismatched start time must fail closed");
        } finally { TestSupport.deleteTree(root); }
    }

    private void identityReject() throws Exception {
        Path root = TestSupport.tempDirectory("identity-reject");
        try {
            AppPaths paths = new AppPaths(root);
            ProcessIdentityStore store = new ProcessIdentityStore(paths);
            UUID id = UUID.randomUUID();
            Files.createDirectories(paths.runtimeDirectory(id));
            for (String value : List.of("broken", "pid=x\nstartedAt=no\n", "pid=-1\nstartedAt=2020-01-01T00:00:00Z\n")) {
                Files.writeString(paths.runtimeState(id), value);
                Assertions.isTrue(store.load(id).isEmpty(), "malformed identity must be ignored");
                Assertions.isTrue(store.reattach(id).isEmpty(), "malformed identity must not attach");
            }
            Files.writeString(paths.runtimeState(id), "pid=9223372036854775807\nstartedAt=2020-01-01T00:00:00Z\n");
            Assertions.isTrue(store.reattach(id).isEmpty(), "nonexistent PID must not attach");
        } finally { TestSupport.deleteTree(root); }
    }

    private void identityDelete() throws Exception {
        Path root = TestSupport.tempDirectory("identity-delete");
        try {
            AppPaths paths = new AppPaths(root);
            ProcessIdentityStore store = new ProcessIdentityStore(paths);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.save(first, new ReattachedManagedProcess(ProcessHandle.current()));
            store.save(second, new ReattachedManagedProcess(ProcessHandle.current()));
            store.delete(first);
            Assertions.isFalse(Files.exists(paths.runtimeState(first)), "selected identity must be removed");
            Assertions.fileExists(paths.runtimeState(second), "other identity must remain");
        } finally { TestSupport.deleteTree(root); }
    }

    private void commandSuccess() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                received.add(reader.readLine());
                received.add(reader.readLine());
                writer.write("IBC command server ready\nOK STOP\nOK Goodbye\n");
                writer.flush();
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.STOP,
                    Duration.ofSeconds(2));
            Assertions.isTrue(result.success(), "OK command response must succeed");
            Assertions.contains(result.response(), "OK STOP", "full response must be returned");
            Assertions.eventually(Duration.ofSeconds(2), () -> received.size() == 2, "server must receive both lines");
            Assertions.equals(List.of("STOP", "EXIT"), received, "client must send command followed by EXIT");
        }
    }

    private void commandError() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                reader.readLine(); reader.readLine();
                writer.write("ERROR Command rejected\nOK Goodbye\n"); writer.flush();
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.PAUSE,
                    Duration.ofSeconds(2));
            Assertions.isFalse(result.success(), "ERROR response must fail");
            Assertions.contains(result.response(), "Command rejected", "error detail must be retained");
        }
    }

    private void commandGoodbyeOnly() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                reader.readLine(); reader.readLine();
                writer.write("OK Goodbye\n"); writer.flush();
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.RESTART,
                    Duration.ofSeconds(2));
            Assertions.isFalse(result.success(), "Goodbye acknowledges EXIT, not the requested command");
        }
    }

    private void commandPartialTimeout() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                reader.readLine(); reader.readLine();
                writer.write("OK PAUSE\n"); writer.flush();
                Thread.sleep(500);
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.PAUSE,
                    Duration.ofMillis(120));
            Assertions.isTrue(result.success(), "received command acknowledgement must survive missing Goodbye timeout");
            Assertions.contains(result.response(), "OK PAUSE", "partial response must be returned");
        }
    }

    private void commandEmptyTimeout() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket)) {
                reader.readLine(); reader.readLine();
                Thread.sleep(500);
            }
        })) {
            Assertions.throwsType(SocketTimeoutException.class,
                    () -> new IbcCommandClient().send("127.0.0.1", server.port(), IbcCommand.STOP, Duration.ofMillis(100)),
                    "no response before timeout must be reported");
        }
    }

    private void commandInvalidTimeout() {
        IbcCommandClient client = new IbcCommandClient();
        Assertions.throwsType(IllegalArgumentException.class,
                () -> client.send("127.0.0.1", 1, IbcCommand.STOP, Duration.ZERO),
                "zero timeout must be rejected");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> client.send("127.0.0.1", 1, IbcCommand.STOP, Duration.ofDays(100)),
                "timeout exceeding socket integer range must be rejected");
    }

    private void tailMissing() throws Exception {
        Path root = TestSupport.tempDirectory("tail-missing");
        try {
            LogTailer tailer = new LogTailer(root.resolve("missing.log"));
            Assertions.equals(List.of(), tailer.readNewLines(), "missing file must produce no lines");
            Path empty = root.resolve("empty.log");
            Files.writeString(empty, "");
            Assertions.equals(List.of(), new LogTailer(empty).readNewLines(), "empty file must produce no lines");
        } finally { TestSupport.deleteTree(root); }
    }

    private void tailPartial() throws Exception {
        Path root = TestSupport.tempDirectory("tail-partial");
        try {
            Path file = root.resolve("log.txt");
            Files.writeString(file, "one\ntwo");
            LogTailer tailer = new LogTailer(file);
            Assertions.equals(List.of("one"), tailer.readNewLines(), "partial trailing line must be retained");
            Files.writeString(file, " continued\nthree\n", java.nio.file.StandardOpenOption.APPEND);
            Assertions.equals(List.of("two continued", "three"), tailer.readNewLines(),
                    "partial line must be joined with later bytes");
            Assertions.equals(List.of(), tailer.readNewLines(), "unchanged file must produce no duplicate lines");
        } finally { TestSupport.deleteTree(root); }
    }

    private void tailUtf8Split() throws Exception {
        Path root = TestSupport.tempDirectory("tail-utf8-split");
        try {
            Path file = root.resolve("log.txt");
            byte[] complete = "price € 😀\n".getBytes(StandardCharsets.UTF_8);
            int firstCut = "price € ".getBytes(StandardCharsets.UTF_8).length + 2;
            Files.write(file, java.util.Arrays.copyOfRange(complete, 0, firstCut));
            LogTailer tailer = new LogTailer(file);
            Assertions.equals(List.of(), tailer.readNewLines(),
                    "incomplete UTF-8 code point must be retained rather than replaced");
            Files.write(file, java.util.Arrays.copyOfRange(complete, firstCut, complete.length),
                    java.nio.file.StandardOpenOption.APPEND);
            Assertions.equals(List.of("price € 😀"), tailer.readNewLines(),
                    "split multibyte code point must decode exactly after append");
        } finally { TestSupport.deleteTree(root); }
    }

    private void tailLineEndings() throws Exception {
        Path root = TestSupport.tempDirectory("tail-eol");
        try {
            Path file = root.resolve("log.txt");
            Files.writeString(file, "a\r\nb\rc\n");
            Assertions.equals(List.of("a", "b", "c"), new LogTailer(file).readNewLines(),
                    "all common line endings must normalize");
        } finally { TestSupport.deleteTree(root); }
    }

    private void tailTruncate() throws Exception {
        Path root = TestSupport.tempDirectory("tail-truncate");
        try {
            Path file = root.resolve("log.txt");
            Files.writeString(file, "old one\nold two\n");
            LogTailer tailer = new LogTailer(file);
            tailer.readNewLines();
            Files.writeString(file, "new\n");
            Assertions.equals(List.of("new"), tailer.readNewLines(), "truncation must reset the cursor");
        } finally { TestSupport.deleteTree(root); }
    }

    private void tailCap() throws Exception {
        Path root = TestSupport.tempDirectory("tail-cap");
        try {
            Path file = root.resolve("log.txt");
            String content = "x".repeat(1_200_000) + "\nlast\n";
            Files.writeString(file, content);
            List<String> lines = new LogTailer(file).readNewLines();
            Assertions.equals("[IBC Manager skipped older log data]", lines.get(0),
                    "large catch-up read must identify skipped data");
            Assertions.equals("last", lines.get(lines.size() - 1), "latest complete line must remain available");
        } finally { TestSupport.deleteTree(root); }
    }

    private void tailReset() throws Exception {
        Path root = TestSupport.tempDirectory("tail-reset");
        try {
            Path file = root.resolve("log.txt");
            Files.writeString(file, "historic\n");
            LogTailer tailer = new LogTailer(file);
            tailer.resetToEnd();
            Assertions.equals(List.of(), tailer.readNewLines(), "reset must skip historical data");
            Files.writeString(file, "new\n", java.nio.file.StandardOpenOption.APPEND);
            Assertions.equals(List.of("new"), tailer.readNewLines(), "new data after reset must be read");
        } finally { TestSupport.deleteTree(root); }
    }

    private void bufferCapacity() {
        RuntimeLogBuffer buffer = new RuntimeLogBuffer(3);
        buffer.append("one"); buffer.append("two"); buffer.append(null); buffer.append("four");
        Assertions.equals(List.of("two", "", "four"), buffer.snapshot(), "oldest lines must be evicted");
        Assertions.equals(3, buffer.size(), "size must not exceed capacity");
        buffer.clear();
        Assertions.equals(0, buffer.size(), "clear must empty buffer");
        Assertions.throwsType(IllegalArgumentException.class, () -> new RuntimeLogBuffer(0),
                "nonpositive capacity must be rejected");
    }

    private void bufferConcurrent() throws Exception {
        RuntimeLogBuffer buffer = new RuntimeLogBuffer(1000);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int thread = 0; thread < 4; thread++) {
            int id = thread;
            executor.submit(() -> {
                for (int i = 0; i < 100; i++) buffer.append(id + ":" + i);
            });
        }
        executor.shutdown();
        Assertions.isTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "append workers must finish");
        Assertions.equals(400, buffer.size(), "all concurrent lines must be retained");
        Assertions.equals(400L, buffer.snapshot().stream().distinct().count(), "concurrent lines must not be corrupted");
    }

    private void stateParserNormal() {
        IbcLogStateParser parser = new IbcLogStateParser();
        assertHint(parser, "Starting IBC version 3.24.1", RuntimeState.STARTING);
        assertHint(parser, "Login dialog WINDOW_OPENED", RuntimeState.WAITING_FOR_LOGIN);
        assertHint(parser, "Second factor authentication initiated", RuntimeState.WAITING_FOR_SECOND_FACTOR);
        assertHint(parser, "Login has completed", RuntimeState.RUNNING);
        assertHint(parser, "IBC is paused", RuntimeState.PAUSED);
        assertHint(parser, "Gateway finished at 12:00", RuntimeState.STOPPED);
        Assertions.isTrue(parser.accept("unrelated").isEmpty(), "unrelated lines must not produce a new hint");
        Assertions.equals(RuntimeState.STOPPED, parser.latest().orElseThrow().state(), "latest recognized state must remain");
    }

    private void stateParserError() {
        IbcLogStateParser parser = new IbcLogStateParser();
        for (String line : List.of("Login failed", "Too many failed login attempts", "Exiting with exit code=1",
                "Can't find suitable Java installation")) {
            assertHint(parser, line, RuntimeState.ERROR);
        }
        parser.reset();
        Assertions.isTrue(parser.latest().isEmpty(), "reset must clear stale state");
    }

    private void tcpProbe() throws Exception {
        TcpPortProbe probe = new TcpPortProbe();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Assertions.isTrue(probe.isOpen("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(1)),
                    "listening socket must be detected");
        }
        int closedPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = reservation.getLocalPort();
        }
        Assertions.isFalse(probe.isOpen("127.0.0.1", closedPort, Duration.ofMillis(100)),
                "closed socket must be rejected");
        Assertions.isFalse(probe.isOpen("127.0.0.1", 0, Duration.ofMillis(100)), "port zero must be rejected");
        Assertions.isFalse(probe.isOpen("127.0.0.1", 65536, Duration.ofMillis(100)), "out-of-range port must be rejected");
    }

    private static void assertHint(IbcLogStateParser parser, String line, RuntimeState expected) {
        Assertions.equals(expected, parser.accept(line).orElseThrow().state(), "unexpected state for line: " + line);
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(Socket socket) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface SocketAction { void run(Socket socket) throws Exception; }

    private static final class TestServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor;
        private final CompletableFuture<Void> completion;

        private TestServer(ServerSocket server, ExecutorService executor, CompletableFuture<Void> completion) {
            this.server = server;
            this.executor = executor;
            this.completion = completion;
        }

        static TestServer start(SocketAction action) throws IOException {
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    action.run(socket);
                } catch (Exception ex) {
                    if (!server.isClosed()) throw new RuntimeException(ex);
                }
            }, executor);
            return new TestServer(server, executor, completion);
        }

        int port() { return server.getLocalPort(); }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                completion.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException ex) {
                completion.cancel(true);
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause != null && !server.isClosed()) throw new IOException("Test server failed", cause);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing test server", ex);
            } finally {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
