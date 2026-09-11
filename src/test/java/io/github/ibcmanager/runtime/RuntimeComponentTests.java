package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.security.CommandResult;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
                new NamedTest("process launcher locates packaged Java commands", this::packagedJavaLauncher),
                new NamedTest("process relay descriptor round-trips and fails closed", this::processRelayDescriptor),
                new NamedTest("process-log bytes stay in memory until an explicit commit",
                        this::bufferedProcessLogCadence),
                new NamedTest("buffered process relay survives the manager process exiting", this::bufferedRelaySurvivesParent),
                new NamedTest("managed Java process reports lifecycle and exit code", this::managedProcess),
                new NamedTest("process tree terminator kills the exact root and descendants", this::processTree),
                new NamedTest("process tree terminator captures descendants spawned during cooperative shutdown", this::processTreeShutdownChild),
                new NamedTest("process identity store round-trips identity", this::identityRoundTrip),
                new NamedTest("process identity store reattaches only matching start time", this::identityReattach),
                new NamedTest("process identity store rejects corrupt and stale identities", this::identityReject),
                new NamedTest("process identity store deletes only selected identity", this::identityDelete),
                new NamedTest("IBC command client sends command and EXIT and accepts OK", this::commandSuccess),
                new NamedTest("IBC command client accepts an exact bare OK acknowledgement", this::commandBareOk),
                new NamedTest("IBC command client reports command rejection", this::commandError),
                new NamedTest("IBC command client rejects an exact bare ERROR response", this::commandBareError),
                new NamedTest("later IBC command error overrides a preliminary acknowledgement",
                        this::commandLateError),
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
                new NamedTest("IBC log parser models wrapper restart and normal-exit decisions",
                        this::stateParserWrapperLifecycle),
                new NamedTest("IBC log parser tracks command-server lifecycle without socket probes",
                        this::stateParserCommandServer),
                new NamedTest("StartIBC launch marker clears stale command and login readiness",
                        this::stateParserStartMarker),
                new NamedTest("Windows listener-table parser ignores established connections",
                        this::windowsListenerParser),
                new NamedTest("Windows listener-table parser retains address and owning PID",
                        this::windowsListenerDetails),
                new NamedTest("Linux listener-table parser accepts only LISTEN rows",
                        this::linuxListenerParser),
                new NamedTest("macOS listener-table parser accepts dotted endpoints",
                        this::unixListenerParser),
                new NamedTest("passive listener probe detects a server without connecting",
                        this::passiveProbeNoConnection),
                new NamedTest("listener snapshots are cached and can be invalidated",
                        this::listenerProbeCache),
                new NamedTest("listener probe retains only a bounded stale snapshot",
                        this::listenerProbeFailure),
                new NamedTest("StartIBC-compatible Java resolver follows explicit and install4j precedence",
                        this::javaRuntimeResolution),
                new NamedTest("offline-installation startup coordinator serializes only matching program trees",
                        this::startCoordinatorSerialization),
                new NamedTest("startup milestone waiter requires the StartIBC launch marker",
                        this::startMilestone));
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
            Profile profile = withFakeJava(TestSupport.validProfile(root.resolve("install")), root);
            Path runtimeConfig = paths.runtimeDirectory(profile.id()).resolve("config.ini");
            Files.createDirectories(runtimeConfig.getParent());
            Files.writeString(runtimeConfig, "TradingMode=paper\n");
            LaunchSpec spec = windowsLaunchFactory(paths).create(profile, runtimeConfig);
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
            Profile profile = withFakeJava(TestSupport.validProfile(root.resolve("install")), root);
            Path runtime = paths.runtimeDirectory(profile.id()).resolve("config.ini");
            Files.createDirectories(runtime.getParent());
            Files.writeString(runtime, "IbPassword=top-secret\n");
            LaunchSpec spec = windowsLaunchFactory(paths).create(profile, runtime);
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
            Profile profile = withFakeJava(TestSupport.validProfile(root.resolve("install")), root);
            Path unsafe = root.resolve("runtime%TEMP%/config.ini");
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> windowsLaunchFactory(paths).create(profile, unsafe),
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
            ManagedProcess process = new DefaultProcessLauncher(Duration.ofMillis(120))
                    .launch(spec, log, "session header\n");
            Assertions.isTrue(process.waitFor(Duration.ofSeconds(5)), "test process must exit");
            String output = Files.readString(log);
            Assertions.contains(output, "stdout", "stdout must be captured");
            Assertions.contains(output, "stderr", "stderr must be merged and captured");
        } finally { TestSupport.deleteTree(root); }
    }


    private void packagedJavaLauncher() throws Exception {
        Path root = TestSupport.tempDirectory("packaged-java-launcher");
        try {
            Path bin = root.resolve("runtime/bin");
            Files.createDirectories(bin);
            IOException missing = Assertions.throwsType(IOException.class,
                    () -> DefaultProcessLauncher.locateJavaExecutable(root.resolve("runtime"), true),
                    "a packaged runtime without a Java launcher must fail closed");
            Assertions.contains(missing.getMessage(), "packaged runtime is incomplete",
                    "missing packaged Java error must explain the incomplete runtime");

            Path javaw = bin.resolve("javaw.exe");
            Files.write(javaw, new byte[] {1});
            Assertions.equals(javaw.toAbsolutePath().normalize(),
                    DefaultProcessLauncher.locateJavaExecutable(root.resolve("runtime"), true),
                    "javaw.exe must be accepted as a no-console Windows fallback");

            Path java = bin.resolve("java.exe");
            Files.write(java, new byte[] {2});
            Assertions.equals(java.toAbsolutePath().normalize(),
                    DefaultProcessLauncher.locateJavaExecutable(root.resolve("runtime"), true),
                    "java.exe must be preferred for the detached relay when both launchers exist");

            Files.write(java, new byte[0]);
            Assertions.equals(javaw.toAbsolutePath().normalize(),
                    DefaultProcessLauncher.locateJavaExecutable(root.resolve("runtime"), true),
                    "an empty java.exe must not mask a usable javaw.exe fallback");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void processRelayDescriptor() throws Exception {
        Path root = TestSupport.tempDirectory("process-relay-descriptor");
        try {
            Path log = root.resolve("logs/output.log");
            LaunchSpec spec = new LaunchSpec(
                    List.of("java", "--example", "value"),
                    root,
                    Map.of("B", "two", "A", "one"),
                    root.resolve("launch.bat"),
                    "test");
            Path descriptorPath = ProcessRelayDescriptor.write(
                    spec, log, "session header \u20ac\n", Duration.ofMillis(250));
            Assertions.fileExists(descriptorPath, "descriptor must be created");
            ProcessRelayDescriptor descriptor = ProcessRelayDescriptor.read(descriptorPath);
            Assertions.equals(spec.command(), descriptor.command(), "command must round-trip");
            Assertions.equals(root.toAbsolutePath().normalize(), descriptor.workingDirectory(),
                    "working directory must round-trip");
            Assertions.equals(spec.environment(), descriptor.environment(), "environment must round-trip");
            Assertions.equals(log.toAbsolutePath().normalize(), descriptor.logFile(),
                    "log path must round-trip");
            Assertions.equals("session header \u20ac\n", descriptor.initialLogText(),
                    "UTF-8 initial text must round-trip");
            Assertions.equals(Duration.ofMillis(250), descriptor.flushInterval(),
                    "flush interval must round-trip");
            Files.delete(descriptorPath);

            Path truncated = root.resolve("truncated.bin");
            Files.write(truncated, new byte[] {0x49, 0x42, 0x43});
            IOException truncatedFailure = Assertions.throwsType(IOException.class,
                    () -> ProcessRelayDescriptor.read(truncated),
                    "truncated descriptors must fail closed");
            Assertions.contains(truncatedFailure.getMessage(), "Truncated",
                    "truncated descriptor error must be explicit");

            String oversized = "x".repeat(4 * 1024 * 1024 + 1);
            Assertions.throwsType(IOException.class,
                    () -> ProcessRelayDescriptor.write(spec, log, oversized, Duration.ofSeconds(1)),
                    "oversized descriptor values must be rejected");
            try (var stream = Files.list(root)) {
                Assertions.isTrue(stream.noneMatch(path -> path.getFileName().toString()
                                .startsWith(".ibc-manager-process-relay-")),
                        "failed descriptor writes must remove temporary files");
            }

            Map<String, String> oversizedEnvironment = new java.util.LinkedHashMap<>();
            for (int index = 0; index < 2049; index++) {
                oversizedEnvironment.put("K" + index, "V");
            }
            LaunchSpec invalidEnvironment = new LaunchSpec(
                    List.of("java"), root, oversizedEnvironment, root.resolve("launch.bat"), "test");
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> ProcessRelayDescriptor.write(
                            invalidEnvironment, log, "", Duration.ofSeconds(1)),
                    "oversized process environments must be rejected before writing");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void bufferedProcessLogCadence() throws Exception {
        Path root = TestSupport.tempDirectory("buffered-process-log");
        try {
            Path log = root.resolve("logs/output.log");
            byte[] header = "buffered-session-header\n".getBytes(StandardCharsets.UTF_8);
            byte[] line = "live-buffered-line\n".getBytes(StandardCharsets.UTF_8);
            try (PeriodicByteLog buffered = new PeriodicByteLog(log, Duration.ofHours(1), header)) {
                buffered.append(line, 0, line.length);
                Assertions.isFalse(Files.exists(log) && Files.size(log) > 0,
                        "process-log bytes must remain in memory before a commit");
                buffered.flush();
                Assertions.isTrue(Files.isRegularFile(log) && Files.size(log) > 0,
                        "an explicit commit must create the process log");
                String text = Files.readString(log);
                Assertions.contains(text, "buffered-session-header",
                        "the initial session header must be committed");
                Assertions.contains(text, "live-buffered-line",
                        "buffered process output must be committed");
            }
            Assertions.equals(Duration.ofSeconds(60), DefaultProcessLauncher.DISK_FLUSH_INTERVAL,
                    "production process-log disk cadence must remain 60 seconds");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void bufferedRelaySurvivesParent() throws Exception {
        Path root = TestSupport.tempDirectory("detached-process-log");
        long relayPid = -1;
        try {
            Path log = root.resolve("logs/output.log");
            Process launcher = new ProcessBuilder(TestSupport.javaCommand(
                    "launch-buffered-and-exit", log.toString(), "140")).start();
            Assertions.isTrue(launcher.waitFor(5, TimeUnit.SECONDS),
                    "manager-fixture process must exit promptly");
            Assertions.equals(0, launcher.exitValue(), "manager-fixture process must launch the relay");
            String output = new String(launcher.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            Assertions.isTrue(output.startsWith("RELAY:"), "manager fixture must report the relay PID");
            relayPid = Long.parseLong(output.substring("RELAY:".length()));
            Assertions.eventually(Duration.ofSeconds(4), () -> {
                if (!Files.exists(log)) return false;
                String text = Files.readString(log);
                return text.contains("detached-session-header") && text.contains("detached-buffered-line");
            }, "detached relay must continue and commit output after its parent manager exits");
            final long observedPid = relayPid;
            Assertions.eventually(Duration.ofSeconds(4),
                    () -> ProcessHandle.of(observedPid).map(handle -> !handle.isAlive()).orElse(true),
                    "detached relay must exit after the launched child exits");
        } finally {
            if (relayPid > 0) ProcessHandle.of(relayPid).filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroyForcibly);
            TestSupport.deleteTree(root);
        }
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
            List<ProcessHandleIdentity> descendants = process.descendants().stream()
                    .map(ProcessHandleIdentity::capture)
                    .flatMap(Optional::stream)
                    .toList();
            boolean stopped = new ProcessTreeTerminator().terminate(
                    process, Duration.ofMillis(200), Duration.ofSeconds(3));
            Assertions.isTrue(stopped, "root and descendants must stop");
            Assertions.isFalse(process.isAlive(), "exact root must be dead");
            Assertions.eventually(Duration.ofSeconds(3),
                    () -> descendants.stream().allMatch(identity ->
                            identity.resolve(Duration.ofSeconds(2)).isEmpty()),
                    "captured descendant identities must be dead");
        } finally {
            raw.destroyForcibly();
            for (ProcessHandle child : process.descendants()) child.destroyForcibly();
        }
    }

    private void processTreeShutdownChild() throws Exception {
        Path root = TestSupport.tempDirectory("process-tree-shutdown-child");
        Path childPidFile = root.resolve("child.pid");
        Process raw = new ProcessBuilder(TestSupport.javaCommand(
                "spawn-child-after-signal", childPidFile.toString(), "30000"))
                .redirectErrorStream(true)
                .start();
        ManagedProcess process = new CooperativeShutdownManagedProcess(raw, childPidFile);
        ProcessHandle child = null;
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8))) {
            Assertions.eventually(Duration.ofSeconds(3), output::ready,
                    "cooperative shutdown fixture must become ready");
            Assertions.equals("READY", output.readLine(),
                    "cooperative shutdown fixture must publish its ready marker");
            boolean stopped = new ProcessTreeTerminator().terminate(
                    process, Duration.ofSeconds(3), Duration.ofSeconds(3));
            Assertions.eventually(Duration.ofSeconds(2), () -> Files.isRegularFile(childPidFile),
                    "cooperative shutdown must publish the descendant PID");
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());
            child = ProcessHandle.of(childPid).orElse(null);
            Assertions.isTrue(stopped, "root and shutdown-spawned descendant must stop");
            Assertions.isFalse(process.isAlive(), "cooperatively stopped root must be dead");
            Assertions.isTrue(child == null || !child.isAlive(),
                    "descendant created during cooperative shutdown must be dead");
        } finally {
            raw.destroyForcibly();
            if (child != null && child.isAlive()) child.destroyForcibly();
            for (ProcessHandle descendant : process.descendants()) descendant.destroyForcibly();
            TestSupport.deleteTree(root);
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

    private void commandBareOk() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                reader.readLine(); reader.readLine();
                writer.write("OK\nOK Goodbye\n"); writer.flush();
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(),
                    IbcCommand.RECONNECTDATA, Duration.ofSeconds(2));
            Assertions.equals(CommandDisposition.COMPLETED, result.disposition(),
                    "a future or third-party IBC build may use an exact bare OK line");
            Assertions.isTrue(result.success(), "bare OK must acknowledge the requested command");
        }
    }

    private void commandBareError() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                reader.readLine(); reader.readLine();
                writer.write("ERROR\nOK Goodbye\n"); writer.flush();
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(),
                    IbcCommand.RECONNECTDATA, Duration.ofSeconds(2));
            Assertions.equals(CommandDisposition.REJECTED, result.disposition(),
                    "an exact bare ERROR line must remain a command rejection");
            Assertions.isFalse(result.success(), "bare ERROR must fail the command");
        }
    }

    private void commandLateError() throws Exception {
        try (TestServer server = TestServer.start(socket -> {
            try (BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                reader.readLine(); reader.readLine();
                writer.write("OK PAUSE in progress\n");
                writer.write("ERROR Unable to pause application\n");
                writer.write("OK Goodbye\n");
                writer.flush();
            }
        })) {
            IbcCommandResult result = new IbcCommandClient().send("127.0.0.1", server.port(),
                    IbcCommand.PAUSE, Duration.ofSeconds(2));
            Assertions.equals(CommandDisposition.REJECTED, result.disposition(),
                    "the complete command response must be authoritative");
            Assertions.isFalse(result.success(), "a later ERROR must override an earlier in-progress ACK");
            Assertions.contains(result.response(), "Unable to pause", "late failure detail must be retained");
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
        assertHint(parser, "Starting IBC version 3.24.2", RuntimeState.STARTING);
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
        for (String line : List.of("Login failed", "Too many failed login attempts")) {
            assertHint(parser, line, RuntimeState.UNKNOWN);
            Assertions.isTrue(parser.errorExitConfirmed(),
                    "child failure must be retained for final wrapper-exit classification");
            parser.reset();
        }
        assertHint(parser, "Can't find suitable Java installation", RuntimeState.ERROR);
        assertHint(parser, "Exiting with exit code=1", RuntimeState.UNKNOWN);
        Assertions.isTrue(parser.errorExitConfirmed(),
                "legacy error-exit wording must be retained for final wrapper-exit classification");
        parser.reset();
        assertHint(parser, "Exiting after error with exit code=4", RuntimeState.UNKNOWN);
        Assertions.isTrue(parser.errorExitConfirmed(),
                "the exact IBC 3.24.2 error-exit wording must be recognized");
        parser.reset();
        Assertions.isTrue(parser.latest().isEmpty(), "reset must clear stale state");
    }

    private void stateParserWrapperLifecycle() {
        IbcLogStateParser parser = new IbcLogStateParser();
        assertHint(parser, "Program has exited", RuntimeState.UNKNOWN);
        Assertions.isTrue(parser.childExitObserved(), "child exit must be tracked independently of wrapper exit");
        assertHint(parser, "IBC will autorestart shortly", RuntimeState.RESTARTING);
        Assertions.isTrue(parser.restartPending(), "automatic restart must become an explicit pending state");
        parser.accept("Starting IBC with this command: java -cp IBC.jar ibcalpha.ibc.IbcGateway");
        Assertions.isFalse(parser.restartPending(), "replacement launch must clear the previous restart decision");
        Assertions.isFalse(parser.errorExitConfirmed(), "replacement launch must clear old child errors");

        assertHint(parser, "Program has exited", RuntimeState.UNKNOWN);
        assertHint(parser, "IBC will cold-restart shortly", RuntimeState.RESTARTING);
        parser.accept("Starting IBC with this command: replacement");
        assertHint(parser, "Program has exited", RuntimeState.UNKNOWN);
        assertHint(parser, "IBC will restart shortly due to 2FA completion timeout", RuntimeState.RESTARTING);
        parser.accept("Starting IBC with this command: replacement");
        assertHint(parser, "Program has exited", RuntimeState.UNKNOWN);
        assertHint(parser, "IBC will restart shortly due to login dialog display timeout",
                RuntimeState.RESTARTING);

        parser.reset();
        assertHint(parser, "Program has exited", RuntimeState.UNKNOWN);
        assertHint(parser, "Normal exit", RuntimeState.STOPPED);
        Assertions.isTrue(parser.normalExitConfirmed(),
                "normal or ClosedownAt-driven wrapper termination must be distinguished from a crash");
        Assertions.isFalse(parser.errorExitConfirmed(), "normal exit must not carry an error marker");

        parser.reset();
        Assertions.isTrue(parser.accept("Abnormal exit").isEmpty(),
                "normal-exit recognition must require the exact StartIBC wrapper marker");
        Assertions.isFalse(parser.normalExitConfirmed(),
                "an unrelated line containing the words normal exit must not mask a crash");
    }

    private void stateParserCommandServer() {
        IbcLogStateParser parser = new IbcLogStateParser();
        Assertions.equals(IbcLogStateParser.CommandServerState.UNKNOWN, parser.commandServerState(),
                "new parser must not assume command readiness");

        parser.accept("2026-08-03 IBC: CommandServer is starting with port 7462");
        Assertions.equals(IbcLogStateParser.CommandServerState.STARTING, parser.commandServerState(),
                "command-server startup must be represented explicitly");
        parser.accept("2026-08-03 IBC: CommandServer listening on address: 127.0.0.1 port: 7462");
        Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(),
                "official IBC listening line must enable commands");
        parser.accept("2026-08-03 IBC: CommandServer: ControlFrom setting =");
        parser.accept("2026-08-03 IBC: CommandServer accepted connection from: /127.0.0.1");
        parser.accept("2026-08-03 IBC: Closing command channel");
        Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(),
                "closing one client channel must not close the command server");

        parser.accept("2026-08-03 IBC: CommandServer closing");
        Assertions.equals(IbcLogStateParser.CommandServerState.CLOSED, parser.commandServerState(),
                "command-server shutdown must clear readiness");
        parser.markCommandServerOpen();
        parser.accept("===== IBC Manager session 2026-08-03T19:00:00Z =====");
        Assertions.equals(IbcLogStateParser.CommandServerState.UNKNOWN, parser.commandServerState(),
                "a new managed session must clear stale readiness");
        Assertions.isTrue(parser.latest().isEmpty(), "a new managed session must clear stale state hints");
        parser.markCommandServerOpen();
        parser.resetSessionState();
        Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(),
                "session-state reset must preserve the live command server");
        parser.markCommandServerClosed();
        Assertions.equals(IbcLogStateParser.CommandServerState.CLOSED, parser.commandServerState(),
                "failed command transport must invalidate readiness");
    }

    private void stateParserStartMarker() {
        IbcLogStateParser parser = new IbcLogStateParser();
        parser.accept("CommandServer listening on address: 127.0.0.1 port: 7462");
        parser.accept("Login has completed");
        Assertions.isTrue(parser.mainWindowReady(), "fixture must begin in a command-capable state");
        long generation = parser.sessionGeneration();

        parser.accept("Starting IBC with this command: java -cp IBC.jar ibcalpha.ibc.IbcGateway");
        Assertions.isFalse(parser.mainWindowReady(), "a replacement IBC JVM must clear login readiness");
        Assertions.equals(IbcLogStateParser.CommandServerState.UNKNOWN, parser.commandServerState(),
                "a replacement IBC JVM must clear command-server readiness");
        Assertions.isTrue(parser.latest().isEmpty(), "stale state hints must not cross an IBC JVM boundary");
        Assertions.isTrue(parser.launchCommandObserved(), "the serialized startup milestone must be recorded");
        Assertions.equals(generation + 1, parser.sessionGeneration(),
                "each StartIBC launch marker must advance the session generation");
    }

    private void windowsListenerParser() {
        String output = """
                Active Connections

                  Proto  Local Address          Foreign Address        State           PID
                  TCP    127.0.0.1:4001         0.0.0.0:0              LISTENING       100
                  TCP    [::1]:4002             [::]:0                 ABHOEREN        101
                  TCP    127.0.0.1:4003         127.0.0.1:55000        ESTABLISHED     102
                  UDP    0.0.0.0:4004           *:*                                    103
                """;
        Assertions.equals(Set.of(4001, 4002), ListeningPortProbe.parseWindowsNetstat(output),
                "only listening TCP rows must be retained, including localized state labels");
    }

    private void windowsListenerDetails() {
        String output = """
                  TCP    127.0.0.1:4001         0.0.0.0:0              LISTENING       8123
                  TCP    0.0.0.0:4002           0.0.0.0:0              LISTENING       9456
                """;
        Set<ListeningPortProbe.ListenerEndpoint> endpoints =
                ListeningPortProbe.parseWindowsNetstatEndpoints(output);
        Assertions.isTrue(endpoints.contains(new ListeningPortProbe.ListenerEndpoint(
                        "127.0.0.1", 4001, 8123)),
                "loopback address and owning PID must be retained");
        Assertions.isTrue(endpoints.contains(new ListeningPortProbe.ListenerEndpoint(
                        "0.0.0.0", 4002, 9456)),
                "wildcard address and owning PID must be retained");
    }

    private void linuxListenerParser() {
        String output = """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0100007F:0FA1 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1
                   1: 0100007F:0FA2 0100007F:D6D8 01 00000000:00000000 00:00000000 00000000 1000 0 2
                   2: 00000000:0FA3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 3
                """;
        Assertions.equals(Set.of(4001, 4003), ListeningPortProbe.parseLinuxProcNet(output),
                "only Linux LISTEN state 0A rows must be retained");
    }

    private void unixListenerParser() {
        String output = """
                Active Internet connections (including servers)
                Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)
                tcp4       0      0  127.0.0.1.4001         *.*                    LISTEN
                tcp6       0      0  *.4002                  *.*                    LISTEN
                tcp4       0      0  127.0.0.1.4003         127.0.0.1.50100        ESTABLISHED
                """;
        Assertions.equals(Set.of(4001, 4002), ListeningPortProbe.parseUnixNetstat(output),
                "only dotted macOS listener endpoints must be retained");
    }

    private void passiveProbeNoConnection() throws Exception {
        ListeningPortProbe probe = new ListeningPortProbe();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(1500);
            var accepted = executor.submit(() -> {
                try (Socket acceptedSocket = server.accept()) {
                    return acceptedSocket.isConnected();
                } catch (SocketTimeoutException expected) {
                    return false;
                }
            });
            probe.invalidate();
            Assertions.equals(PortListenerState.LISTENING,
                    probe.inspect("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2)),
                    "operating-system listener inspection must detect the bound socket");
            Assertions.isFalse(accepted.get(3, TimeUnit.SECONDS),
                    "passive listener inspection must not create a client connection");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
        Assertions.equals(PortListenerState.UNKNOWN,
                probe.inspect("127.0.0.1", 0, Duration.ofSeconds(1)),
                "port zero must be rejected as uninspectable");
        Assertions.equals(PortListenerState.UNKNOWN,
                probe.inspect("127.0.0.1", 65536, Duration.ofSeconds(1)),
                "out-of-range ports must be rejected as uninspectable");
    }

    private void listenerProbeCache() {
        AtomicInteger reads = new AtomicInteger();
        AtomicLong time = new AtomicLong();
        ListeningPortProbe probe = new ListeningPortProbe(timeout -> {
            reads.incrementAndGet();
            return Set.of(4001);
        }, Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMinutes(1), time::get);

        Assertions.equals(PortListenerState.LISTENING,
                probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1)),
                "first lookup must use the source");
        Assertions.equals(PortListenerState.NOT_LISTENING,
                probe.inspect("127.0.0.1", 4002, Duration.ofSeconds(1)),
                "same snapshot must answer other ports");
        Assertions.equals(1, reads.get(), "multiple profiles must share one cached listener snapshot");
        time.set(Duration.ofSeconds(4).toNanos());
        probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1));
        Assertions.equals(1, reads.get(), "snapshot must remain cached within its lifetime");
        time.set(Duration.ofSeconds(6).toNanos());
        probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1));
        Assertions.equals(2, reads.get(), "expired snapshot must be refreshed");
        probe.invalidate();
        probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1));
        Assertions.equals(3, reads.get(), "launch preflight invalidation must force one fresh snapshot");
    }

    private void listenerProbeFailure() {
        AtomicInteger reads = new AtomicInteger();
        AtomicLong time = new AtomicLong();
        ListeningPortProbe probe = new ListeningPortProbe(timeout -> {
            if (reads.getAndIncrement() == 0) return Set.of(4001);
            throw new IOException("simulated listener-table failure");
        }, Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofHours(1), time::get);

        Assertions.equals(PortListenerState.LISTENING,
                probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1)),
                "successful snapshot must be used");
        time.set(Duration.ofSeconds(6).toNanos());
        Assertions.equals(PortListenerState.LISTENING,
                probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1)),
                "a recent snapshot may bridge a temporary inspection failure");
        time.set(Duration.ofSeconds(16).toNanos());
        Assertions.equals(PortListenerState.UNKNOWN,
                probe.inspect("127.0.0.1", 4001, Duration.ofSeconds(1)),
                "stale listener data must eventually become unknown");

        AtomicInteger freshReads = new AtomicInteger();
        ListeningPortProbe forceFresh = new ListeningPortProbe(timeout -> {
            if (freshReads.getAndIncrement() == 0) return Set.of(4001);
            throw new IOException("forced refresh failed");
        }, Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofHours(1), time::get);
        Assertions.equals(PortListenerState.LISTENING,
                forceFresh.inspect("127.0.0.1", 4001, Duration.ofSeconds(1)),
                "initial force-fresh fixture snapshot must succeed");
        forceFresh.invalidate();
        Assertions.equals(PortListenerState.UNKNOWN,
                forceFresh.inspect("127.0.0.1", 4001, Duration.ofSeconds(1)),
                "launch preflight must not trust stale data after a forced refresh fails");
    }

    private void javaRuntimeResolution() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-java-resolution");
        try {
            Profile base = TestSupport.validProfile(root.resolve("install"));
            Path program = base.twsPath().resolve("ibgateway").resolve(base.twsMajorVersion());
            Path install4j = program.resolve(".install4j");
            Path explicitBin = createFakeJava(root.resolve("explicit"), "java.exe");
            Path preferredRoot = root.resolve("preferred");
            Path preferredBin = createFakeJava(preferredRoot, "bin/java.exe");
            Path installedRoot = root.resolve("installed");
            Path installedBin = createFakeJava(installedRoot, "bin/java.exe");
            Path programData = root.resolve("ProgramData");
            Path oracleBin = createFakeJava(programData.resolve("Oracle/Java/javapath"), "java.exe");
            Files.writeString(install4j.resolve("pref_jre.cfg"), preferredRoot + System.lineSeparator());
            Files.writeString(install4j.resolve("inst_jre.cfg"), installedRoot + System.lineSeparator());

            List<Path> executed = new ArrayList<>();
            IbcJavaRuntimeResolver resolver = new IbcJavaRuntimeResolver(
                    OperatingSystem.WINDOWS, Map.of("PROGRAMDATA", programData.toString()),
                    (command, input, timeout) -> {
                        executed.add(Path.of(command.get(0)).toAbsolutePath().normalize());
                        return new CommandResult(0, "", "openjdk version \"17.0.12\"", false);
                    }, new OfflineApplicationLayoutResolver());

            Profile explicit = base.toBuilder().ibcJavaPath(explicitBin).build();
            Assertions.equals(explicitBin.toAbsolutePath().normalize(), resolver.resolve(explicit).directory(),
                    "explicit /JavaPath directory must take precedence over install4j files");
            Assertions.equals(explicitBin.resolve("java.exe").toAbsolutePath().normalize(), executed.get(0),
                    "the exact explicit runtime must be version checked");

            Profile automatic = base.toBuilder().ibcJavaPath(Path.of("")).build();
            Assertions.equals(preferredBin.toAbsolutePath().normalize(), resolver.resolve(automatic).directory(),
                    "pref_jre.cfg must precede inst_jre.cfg and ProgramData fallback");
            Files.delete(install4j.resolve("pref_jre.cfg"));
            Assertions.equals(installedBin.toAbsolutePath().normalize(), resolver.resolve(automatic).directory(),
                    "inst_jre.cfg must be used when no preferred runtime is configured");
            Files.delete(install4j.resolve("inst_jre.cfg"));
            Assertions.equals(oracleBin.toAbsolutePath().normalize(), resolver.resolve(automatic).directory(),
                    "ProgramData Oracle javapath must be the final StartIBC-compatible fallback");

            IbcJavaRuntimeResolver java8 = new IbcJavaRuntimeResolver(
                    OperatingSystem.WINDOWS, Map.of("PROGRAMDATA", programData.toString()),
                    (command, input, timeout) -> new CommandResult(
                            0, "", "java version \"1.8.0_401\"", false),
                    new OfflineApplicationLayoutResolver());
            IOException incompatible = Assertions.throwsType(IOException.class,
                    () -> java8.resolve(automatic), "Java 8 must be rejected before StartIBC is launched");
            Assertions.contains(incompatible.getMessage(), "requires Java 17",
                    "the rejected runtime must explain the supported-IBC Java requirement");

            TestSupport.writeIbcJar(base.ibcPath().resolve("IBC.jar"),
                    io.github.ibcmanager.app.Version.IBC_MINIMUM_SUPPORTED_VERSION, 65);
            IbcJavaRuntimeResolver java17ForJava21Ibc = new IbcJavaRuntimeResolver(
                    OperatingSystem.WINDOWS, Map.of("PROGRAMDATA", programData.toString()),
                    (command, input, timeout) -> new CommandResult(
                            0, "", "openjdk version \"17.0.12\"", false),
                    new OfflineApplicationLayoutResolver());
            IOException newerIbcNeedsNewerJava = Assertions.throwsType(IOException.class,
                    () -> java17ForJava21Ibc.resolve(automatic),
                    "a dynamically resolved IBC release must not start on an older Java runtime");
            Assertions.contains(newerIbcNeedsNewerJava.getMessage(), "requires Java 21",
                    "the error must report the Java requirement embedded in the selected IBC release");

            IbcJavaRuntimeResolver java21 = new IbcJavaRuntimeResolver(
                    OperatingSystem.WINDOWS, Map.of("PROGRAMDATA", programData.toString()),
                    (command, input, timeout) -> new CommandResult(
                            0, "", "openjdk version \"21.0.7\"", false),
                    new OfflineApplicationLayoutResolver());
            Assertions.equals(21, java21.resolve(automatic).major(),
                    "a runtime satisfying a future IBC class-file requirement must be accepted");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void startCoordinatorSerialization() throws Exception {
        Path root = TestSupport.tempDirectory("start-coordinator");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Profile first = TestSupport.validProfile(root.resolve("shared"));
            Profile second = first.toBuilder().id(UUID.randomUUID())
                    .twsSettingsPath(root.resolve("settings-2")).build();
            Files.createDirectories(second.twsSettingsPath());
            ApplicationStartCoordinator coordinator = new ApplicationStartCoordinator(
                    root.resolve("locks"), new OfflineApplicationLayoutResolver());
            StartCoordinator.Lease firstLease = coordinator.acquire(first, Duration.ofSeconds(2));
            CompletableFuture<Boolean> secondAcquired = CompletableFuture.supplyAsync(() -> {
                try (StartCoordinator.Lease acquired = coordinator.acquire(second, Duration.ofSeconds(3))) {
                    return acquired.coordinated();
                } catch (IOException | InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }, executor);
            Thread.sleep(150);
            Assertions.isFalse(secondAcquired.isDone(),
                    "profiles sharing one offline program tree must serialize the mutation-prone start phase");
            firstLease.close();
            Assertions.isTrue(secondAcquired.get(2, TimeUnit.SECONDS),
                    "the second profile must proceed immediately after the shared start milestone is released");

            Profile independent = TestSupport.validProfile(root.resolve("independent"));
            try (StartCoordinator.Lease shared = coordinator.acquire(first, Duration.ofSeconds(2));
                    StartCoordinator.Lease other = coordinator.acquire(independent, Duration.ofSeconds(2))) {
                Assertions.isTrue(shared.coordinated() && other.coordinated(),
                        "different offline installations must remain independently startable");
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            TestSupport.deleteTree(root);
        }
    }

    private void startMilestone() throws Exception {
        IbcLogStateParser parser = new IbcLogStateParser();
        MilestoneProcess alive = new MilestoneProcess(true, OptionalInt.empty());
        AtomicInteger reads = new AtomicInteger();
        StartMilestoneAwaiter.await(alive, parser, () -> {
            if (reads.incrementAndGet() == 2) {
                parser.accept("Starting IBC with this command: java -cp IBC.jar ibcalpha.ibc.IbcGateway");
            }
        }, Duration.ofSeconds(1));
        Assertions.equals(2, reads.get(), "the waiter must retain the shared start lock until the marker appears");

        MilestoneProcess exited = new MilestoneProcess(false, OptionalInt.of(27));
        IOException earlyExit = Assertions.throwsType(IOException.class,
                () -> StartMilestoneAwaiter.await(exited, new IbcLogStateParser(), () -> { },
                        Duration.ofSeconds(1)),
                "a wrapper that exits before the marker must fail the coordinated start");
        Assertions.contains(earlyExit.getMessage(), "exit code 27", "early exit code must be preserved");

        IOException timeout = Assertions.throwsType(IOException.class,
                () -> StartMilestoneAwaiter.await(alive, new IbcLogStateParser(), () -> { },
                        Duration.ofMillis(60)),
                "a wrapper that never reaches the marker must time out rather than releasing the lock early");
        Assertions.contains(timeout.getMessage(), "serialized launch milestone",
                "timeout must identify the guarded StartIBC phase");
    }

    private static Path createFakeJava(Path root, String relativeExecutable) throws IOException {
        Path executable = root.resolve(relativeExecutable);
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "test fixture");
        return executable.getParent();
    }


    private static Profile withFakeJava(Profile profile, Path root) throws IOException {
        Path bin = root.resolve("fake-java").resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("java.exe"), "test fixture");
        return profile.toBuilder().ibcJavaPath(bin).build();
    }

    private static LaunchScriptFactory windowsLaunchFactory(AppPaths paths) {
        IbcJavaRuntimeResolver resolver = new IbcJavaRuntimeResolver(
                OperatingSystem.WINDOWS,
                Map.of(),
                (command, input, timeout) -> new CommandResult(
                        0, "", "openjdk version \"17.0.12\"", false),
                new OfflineApplicationLayoutResolver());
        return new LaunchScriptFactory(paths, OperatingSystem.WINDOWS, resolver);
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

    private static final class CooperativeShutdownManagedProcess implements ManagedProcess {
        private static final Duration CHILD_PUBLICATION_TIMEOUT = Duration.ofSeconds(10);

        private final Process process;
        private final Path childPidFile;
        private final ManagedProcess delegate;
        private final AtomicBoolean shutdownRequested = new AtomicBoolean();

        CooperativeShutdownManagedProcess(Process process, Path childPidFile) {
            this.process = process;
            this.childPidFile = childPidFile.toAbsolutePath().normalize();
            this.delegate = new JavaManagedProcess(process);
        }

        @Override public long pid() { return delegate.pid(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public Optional<Instant> startInstant() { return delegate.startInstant(); }
        @Override public List<ProcessHandle> descendants() { return delegate.descendants(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return delegate.onExit(); }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            return delegate.waitFor(timeout);
        }
        @Override public void destroy() {
            if (!shutdownRequested.compareAndSet(false, true)) return;
            try (var signal = process.getOutputStream()) {
                signal.write(1);
                signal.flush();
            } catch (IOException failure) {
                process.destroy();
                return;
            }

            // The production terminator captures descendants before destroy() and then repeatedly
            // while waiting for the root. Wait here only until the cooperative fixture has
            // atomically published the child it creates after the shutdown signal. This keeps the
            // intended race boundary while removing scheduler-dependent test flakiness under heavy
            // concurrent CI load.
            long deadline = System.nanoTime() + CHILD_PUBLICATION_TIMEOUT.toNanos();
            while (process.isAlive() && !Files.isRegularFile(childPidFile)) {
                if (System.nanoTime() >= deadline) return;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        @Override public void destroyForcibly() {
            process.destroyForcibly();
        }
        @Override public OptionalInt exitCode() { return delegate.exitCode(); }
    }

    private static final class MilestoneProcess implements ManagedProcess {
        private final boolean alive;
        private final OptionalInt exitCode;

        private MilestoneProcess(boolean alive, OptionalInt exitCode) {
            this.alive = alive;
            this.exitCode = exitCode;
        }

        @Override public long pid() { return 1L; }
        @Override public boolean isAlive() { return alive; }
        @Override public Optional<Instant> startInstant() { return Optional.empty(); }
        @Override public List<ProcessHandle> descendants() { return List.of(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return new CompletableFuture<>(); }
        @Override public boolean waitFor(Duration timeout) { return !alive; }
        @Override public void destroy() { }
        @Override public void destroyForcibly() { }
        @Override public OptionalInt exitCode() { return exitCode; }
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
