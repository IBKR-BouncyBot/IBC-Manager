package io.github.ibcmanager.app;

import io.github.ibcmanager.logging.AppLog;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.tests.ThrowingRunnable;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ApplicationTests implements TestSuite {
    @Override public String name() { return "Application bootstrap, paths, locking, and logging"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("detects Windows Linux macOS and unknown operating systems", this::operatingSystems),
                new NamedTest("chooses platform-specific default data directories", this::defaultPaths),
                new NamedTest("derives all application paths from one normalized root", this::pathLayout),
                new NamedTest("single-instance lock excludes a second process and can be reacquired", this::singleInstance),
                new NamedTest("startup command uses the packaged application path", this::packagedStartup),
                new NamedTest("startup command record is normalized and immutable", this::startupRecord),
                new NamedTest("version command reports application, IBC release channel, and compatibility floor", this::versionCommand),
                new NamedTest("invalid command-line arguments return usage error", this::invalidArguments),
                new NamedTest("headless smoke succeeds with an empty data directory", this::emptySmoke),
                new NamedTest("headless smoke reports invalid stored profiles", this::invalidProfileSmoke),
                new NamedTest("headless smoke reports corrupt profile recovery warnings", this::corruptProfileSmoke),
                new NamedTest("GUI startup fails clearly in a headless session", this::headlessGui),
                new NamedTest("application services create every required directory", this::serviceDirectories),
                new NamedTest("application log redacts secrets and restores exception handler", this::applicationLog),
                new NamedTest("version constants are stable and nonblank", this::versionConstants));
    }

    private void operatingSystems() {
        String original = System.getProperty("os.name");
        try {
            assertOperatingSystem("Windows 11", OperatingSystem.WINDOWS);
            assertOperatingSystem("Linux", OperatingSystem.LINUX);
            assertOperatingSystem("GNU/Linux", OperatingSystem.LINUX);
            assertOperatingSystem("Mac OS X", OperatingSystem.MAC);
            assertOperatingSystem("Darwin", OperatingSystem.MAC);
            assertOperatingSystem("AIX", OperatingSystem.LINUX);
            assertOperatingSystem("Plan 9", OperatingSystem.OTHER);
            System.clearProperty("os.name");
            Assertions.equals(OperatingSystem.OTHER, OperatingSystem.current(), "missing os.name must be safe");
        } finally {
            restoreProperty("os.name", original);
        }
    }

    private static void assertOperatingSystem(String name, OperatingSystem expected) {
        System.setProperty("os.name", name);
        Assertions.equals(expected, OperatingSystem.current(), "unexpected OS mapping for " + name);
    }

    private void defaultPaths() {
        AppPaths windows = AppPaths.systemDefault(Map.of("LOCALAPPDATA", "C:/Users/Test/AppData/Local"),
                "C:/Users/Test", OperatingSystem.WINDOWS);
        Assertions.equals(Path.of("C:/Users/Test/AppData/Local/IBCManager").toAbsolutePath().normalize(),
                windows.root(), "Windows must prefer LOCALAPPDATA");
        AppPaths fallback = AppPaths.systemDefault(Map.of("LOCALAPPDATA", "  "),
                "/home/test", OperatingSystem.WINDOWS);
        Assertions.equals(Path.of("/home/test/AppData/Local/IBCManager").toAbsolutePath().normalize(),
                fallback.root(), "Windows must have a deterministic fallback");
        Assertions.equals(Path.of("/Users/test/Library/Application Support/IBCManager").toAbsolutePath().normalize(),
                AppPaths.systemDefault(Map.of(), "/Users/test", OperatingSystem.MAC).root(),
                "macOS must use Application Support");
        Assertions.equals(Path.of("/home/test/.local/share/ibc-manager").toAbsolutePath().normalize(),
                AppPaths.systemDefault(Map.of(), "/home/test", OperatingSystem.LINUX).root(),
                "Linux must use the user data directory");
        Assertions.equals(Path.of("/home/test/.local/share/ibc-manager").toAbsolutePath().normalize(),
                AppPaths.systemDefault(Map.of(), "/home/test", OperatingSystem.OTHER).root(),
                "unknown systems must use the conservative Unix layout");
    }

    private void pathLayout() {
        Path root = Path.of("build", "..", "data-root");
        AppPaths paths = new AppPaths(root);
        Assertions.equals(root.toAbsolutePath().normalize(), paths.root(), "root must be absolute and normalized");
        Assertions.equals(paths.root().resolve("profiles"), paths.profiles(), "profile root mismatch");
        Assertions.equals(paths.root().resolve("credentials"), paths.credentials(), "credential root mismatch");
        Assertions.equals(paths.root().resolve("runtime"), paths.runtime(), "runtime root mismatch");
        Assertions.equals(paths.root().resolve("logs"), paths.logs(), "log root mismatch");
        Assertions.equals(paths.root().resolve("diagnostics"), paths.diagnostics(), "diagnostic root mismatch");
        var id = java.util.UUID.randomUUID();
        Assertions.equals(paths.profiles().resolve(id.toString()).resolve("profile.properties"), paths.profileFile(id),
                "profile file must be scoped by UUID");
        Assertions.equals(paths.runtime().resolve(id.toString()).resolve("process.properties"), paths.runtimeState(id),
                "runtime identity must be scoped by UUID");
        Assertions.equals(paths.runtime().resolve(id.toString()).resolve("recovery-history.properties"),
                paths.recoveryHistory(id), "automatic recovery history must be scoped by UUID");
        Assertions.equals(paths.credentials().resolve(id + ".dpapi"), paths.credentialFile(id),
                "credential file must be scoped by UUID");
    }

    private void singleInstance() throws Exception {
        Path root = TestSupport.tempDirectory("single-instance");
        Path lockFile = root.resolve("manager.lock");
        try {
            SingleInstanceLock first = SingleInstanceLock.acquire(lockFile);
            try {
                Assertions.fileExists(lockFile, "lock file must exist while held");
                if (Files.getFileAttributeView(lockFile,
                        java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                    Assertions.equals(PosixFilePermissions.fromString("rw-------"),
                            Files.getPosixFilePermissions(lockFile),
                            "single-instance lock must be owner-only");
                }
                Process blocked = new ProcessBuilder(TestSupport.javaCommand("try-lock", lockFile.toString())).start();
                Assertions.isTrue(blocked.waitFor(5, TimeUnit.SECONDS),
                        "cross-process lock contender must finish promptly");
                Assertions.equals(23, blocked.exitValue(),
                        "a second process must be denied while the lock is held");
                Assertions.contains(new String(blocked.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                        "CONFLICT", "cross-process lock conflict must be explicit");
                IOException error = Assertions.throwsType(IOException.class,
                        () -> SingleInstanceLock.acquire(lockFile), "same-process second acquisition must fail");
                Assertions.contains(error.getMessage(), "already", "lock failure must explain the conflict");
                Process stillBlocked = new ProcessBuilder(
                        TestSupport.javaCommand("try-lock", lockFile.toString())).start();
                Assertions.isTrue(stillBlocked.waitFor(5, TimeUnit.SECONDS),
                        "post-overlap lock contender must finish promptly");
                Assertions.equals(23, stillBlocked.exitValue(),
                        "same-process overlap handling must not release the cross-process lock");
            } finally {
                first.close();
            }
            Assertions.equals(Long.toString(ProcessHandle.current().pid()), Files.readString(lockFile),
                    "released lock file must identify the former owning process");
            Process allowed = new ProcessBuilder(TestSupport.javaCommand("try-lock", lockFile.toString())).start();
            Assertions.isTrue(allowed.waitFor(5, TimeUnit.SECONDS),
                    "cross-process lock reacquisition must finish promptly");
            Assertions.equals(0, allowed.exitValue(), "a second process must acquire the released lock");
            String allowedOutput = new String(allowed.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            Assertions.isTrue(allowedOutput.startsWith("ACQUIRED:"),
                    "successful cross-process lock acquisition must report its PID");
            Assertions.equals(allowedOutput.substring("ACQUIRED:".length()), Files.readString(lockFile),
                    "reacquired lock file must identify the new owning process");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void packagedStartup() {
        String original = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", "build/IBC Manager.exe");
            StartupCommand command = StartupCommand.detect().orElseThrow();
            Assertions.equals(Path.of("build/IBC Manager.exe").toAbsolutePath().normalize(), command.executable(),
                    "jpackage executable must be used exactly");
            Assertions.equals(List.of("--autostart"), command.arguments(),
                    "startup task must request only profile auto-start");
        } finally {
            restoreProperty("jpackage.app-path", original);
        }
    }

    private void startupRecord() {
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>(List.of("--autostart"));
        StartupCommand command = new StartupCommand(Path.of(".", "manager.exe"), arguments);
        arguments.add("--unexpected");
        Assertions.equals(Path.of("manager.exe").toAbsolutePath().normalize(), command.executable(),
                "record must normalize executable path");
        Assertions.equals(List.of("--autostart"), command.arguments(), "record must defensively copy arguments");
        Assertions.throwsType(UnsupportedOperationException.class,
                () -> command.arguments().add("x"), "argument list must be immutable");
    }

    private void versionCommand() throws Exception {
        Captured captured = capture(() -> {
            int code = IbcManagerApp.run(new String[] {"--version"});
            Assertions.equals(0, code, "version command must succeed");
        });
        Assertions.contains(captured.stdout(), Version.APPLICATION_NAME + " " + Version.VERSION,
                "version output must identify application");
        Assertions.contains(captured.stdout(), "Engine: " + Version.IBC_RELEASE_CHANNEL,
                "version output must identify the dynamic IBC release channel");
        Assertions.contains(captured.stdout(), "upstream " + Version.IBC_MINIMUM_SUPPORTED_VERSION,
                "version output must identify the IBC compatibility floor");
        Assertions.equals("", captured.stderr(), "version command must not print errors");
    }

    private void invalidArguments() throws Exception {
        Captured unknown = capture(() -> {
            Assertions.equals(2, IbcManagerApp.run(new String[] {"--unknown"}),
                    "unknown option must return usage error");
        });
        Assertions.contains(unknown.stderr(), "Unknown argument", "unknown option must be named");
        Assertions.contains(unknown.stderr(), "Usage:", "usage must be printed");

        Captured missing = capture(() -> {
            Assertions.equals(2, IbcManagerApp.run(new String[] {"--data-dir"}),
                    "missing data directory value must return usage error");
        });
        Assertions.contains(missing.stderr(), "requires a directory", "missing value must be explained");
    }

    private void emptySmoke() throws Exception {
        Path root = TestSupport.tempDirectory("empty-smoke");
        try {
            Captured captured = capture(() -> {
                int code = IbcManagerApp.run(new String[] {"--headless-smoke", "--data-dir", root.toString()});
                Assertions.equals(0, code, "empty data directory must pass smoke validation");
            });
            Assertions.contains(captured.stdout(), "headless smoke test: OK", "smoke test must report success");
            Assertions.contains(captured.stdout(), "Profiles loaded: 0", "empty repository must be reported");
            Assertions.contains(captured.stdout(), "Profiles with validation errors: 0",
                    "empty repository has no invalid profiles");
            for (Path directory : List.of(new AppPaths(root).profiles(), new AppPaths(root).credentials(),
                    new AppPaths(root).runtime(), new AppPaths(root).logs(), new AppPaths(root).diagnostics())) {
                Assertions.directoryExists(directory, "smoke bootstrap must create " + directory);
            }
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void invalidProfileSmoke() throws Exception {
        Path root = TestSupport.tempDirectory("invalid-smoke");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder().apiPort(70000).build();
            new ProfileRepository(paths).save(profile);
            Captured captured = capture(() -> {
                int code = IbcManagerApp.run(new String[] {"--headless-smoke", "--data-dir", root.toString()});
                Assertions.equals(5, code, "stored validation errors must fail smoke gate");
            });
            Assertions.contains(captured.stdout(), "Profiles loaded: 1", "stored profile must be loaded");
            Assertions.contains(captured.stdout(), "Profiles with validation errors: 1",
                    "invalid profile count must be reported");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void corruptProfileSmoke() throws Exception {
        Path root = TestSupport.tempDirectory("corrupt-smoke");
        try {
            AppPaths paths = new AppPaths(root);
            Path directory = paths.profiles().resolve(java.util.UUID.randomUUID().toString());
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("profile.properties"), "corrupt\n");
            Captured captured = capture(() -> {
                int code = IbcManagerApp.run(new String[] {"--headless-smoke", "--data-dir", root.toString()});
                Assertions.equals(0, code, "unrecoverable files are warnings rather than invalid loaded profiles");
            });
            Assertions.contains(captured.stdout(), "Profiles loaded: 0", "corrupt profile must be skipped");
            Assertions.contains(captured.stdout(), "Recovery warnings: 1", "corrupt profile warning must be counted");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void headlessGui() throws Exception {
        if (!GraphicsEnvironment.isHeadless()) return;
        Captured captured = capture(() -> {
            int code = IbcManagerApp.run(new String[] {"--data-dir", "build/headless-test"});
            Assertions.equals(3, code, "GUI start in headless mode must have a dedicated exit code");
        });
        Assertions.contains(captured.stderr(), OperatingSystem.current() == OperatingSystem.WINDOWS
                        ? "graphical desktop session is required" : "Windows only",
                "headless error must be actionable");
    }

    private void serviceDirectories() throws Exception {
        Path root = TestSupport.tempDirectory("services");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            try (AppServices services = AppServices.create(paths)) {
                Assertions.equals(paths.root(), services.paths().root(), "service graph must retain paths");
                Assertions.isTrue(services.profileRepository() != null, "profile repository must be available");
                Assertions.isTrue(services.runtimeRegistry() != null, "runtime registry must be available");
                Assertions.isTrue(services.diagnosticBundleService() != null, "diagnostics must be available");
                Assertions.isTrue(services.taskSchedulerService() != null, "task scheduler facade must be available");
            }
            for (Path directory : List.of(paths.root(), paths.profiles(), paths.credentials(), paths.runtime(),
                    paths.logs(), paths.diagnostics())) {
                Assertions.directoryExists(directory, "service bootstrap must create " + directory);
                if (Files.getFileAttributeView(directory,
                        java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                    Assertions.equals(PosixFilePermissions.fromString("rwx------"),
                            Files.getPosixFilePermissions(directory),
                            "application data directory must be owner-only: " + directory);
                }
            }
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void applicationLog() throws Exception {
        Path root = TestSupport.tempDirectory("app-log");
        Thread.UncaughtExceptionHandler previous = (thread, error) -> { };
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(previous);
        try {
            AppPaths paths = new AppPaths(root);
            AppLog logService = AppLog.initialize(paths, Duration.ofMillis(220));
            try {
                Assertions.notEquals(previous, Thread.getDefaultUncaughtExceptionHandler(),
                        "logger must install its uncaught exception handler");
                Assertions.isFalse(java.util.logging.Logger.getLogger("io.github.ibcmanager").getUseParentHandlers(),
                        "application records must not reach an unredacted parent console handler");
                AppLog.get(ApplicationTests.class).log(Level.WARNING,
                        "IbPassword=LogSecret", new IllegalStateException("StartIBC /PW:ThrownSecret"));
                Thread.sleep(60);
                Assertions.isFalse(Files.exists(paths.appLog()) && Files.size(paths.appLog()) > 0,
                        "application records must remain in memory before the scheduled disk commit");
                Assertions.eventually(Duration.ofSeconds(3),
                        () -> Files.exists(paths.appLog()) && Files.size(paths.appLog()) > 0,
                        "application records must be committed on the configured interval");
                AppLog.get(ApplicationTests.class).info("close-flush-marker");
            } finally {
                logService.close();
            }
            Assertions.equals(previous, Thread.getDefaultUncaughtExceptionHandler(),
                    "closing logger must restore the previous uncaught exception handler");
            String log = Files.readString(paths.appLog());
            Assertions.notContains(log, "LogSecret", "formatted log must redact config password");
            Assertions.notContains(log, "ThrownSecret", "formatted exception must redact command password");
            Assertions.contains(log, "[REDACTED]", "redaction must remain visible in logs");
            Assertions.contains(log, IllegalStateException.class.getName(), "exception type must remain diagnosable");
            Assertions.contains(log, "close-flush-marker", "closing the logger must commit the final partial interval");
            Assertions.equals(Duration.ofSeconds(60), AppLog.DISK_FLUSH_INTERVAL,
                    "production application-log disk cadence must remain 60 seconds");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
            TestSupport.deleteTree(root);
        }
    }

    private void versionConstants() {
        Assertions.equals("IBC Manager", Version.APPLICATION_NAME, "application name changed unexpectedly");
        Assertions.isTrue(Version.VERSION.matches("[0-9]+\\.[0-9]+\\.[0-9]+"),
                "manager version must use semantic numeric form");
        Assertions.isTrue(Version.IBC_MINIMUM_SUPPORTED_VERSION.matches("[0-9]+\\.[0-9]+\\.[0-9]+"),
                "IBC compatibility floor must use semantic numeric form");
        Assertions.equals("integrated engine", Version.IBC_RELEASE_CHANNEL,
                "IBC release channel changed unexpectedly");
    }

    private static Captured capture(ThrowingRunnable action) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            action.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Captured(stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private record Captured(String stdout, String stderr) { }
}
