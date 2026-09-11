package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.Profile;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class ArchitectureTests implements TestSuite {
    private static final Path ROOT = locateProjectRoot();
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);$");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([a-zA-Z0-9_.*]+);$");

    @Override public String name() { return "Source architecture, dependency, packaging, and release invariants"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("all Java source files satisfy strict hygiene rules", this::sourceHygiene),
                new NamedTest("Java packages match source-tree paths", this::packageLayout),
                new NamedTest("production code has no third-party Java dependencies", this::dependencyBoundary),
                new NamedTest("profile persistence model has no password or secret field", this::profileHasNoSecret),
                new NamedTest("launcher and startup task never pass credentials in arguments", this::noCredentialArguments),
                new NamedTest("runtime supervision avoids global desktop and process automation", this::noGlobalAutomation),
                new NamedTest("command-server monitoring does not create periodic client connections",
                        this::quietCommandServerMonitoring),
                new NamedTest("API listener monitoring never opens raw client connections",
                        this::passiveApiMonitoring),
                new NamedTest("manual start stop restart and pause actions require confirmation", this::sessionActionConfirmationWiring),
                new NamedTest("test subprocesses are platform-neutral", this::portableTestSubprocesses),
                new NamedTest("no TOTP generator or cryptographic OTP implementation is present", this::noTotpImplementation),
                new NamedTest("IBC compatibility-floor reference and GPL notices are retained", this::ibcNotices),
                new NamedTest("IBC installer resolves latest official releases without a version pin",
                        this::dynamicLatestIbcInstaller),
                new NamedTest("release documentation and build scripts are present", this::releaseFiles),
                new NamedTest("application and process logs use 60-second disk batching", this::bufferedLogArchitecture),
                new NamedTest("Windows packaging launchers enforce the complete release gates", this::windowsPackagingScripts),
                new NamedTest("source directories contain no generated binary artifacts", this::noGeneratedArtifacts),
                new NamedTest("compiled production classes target Java 17 bytecode", this::java17Bytecode),
                new NamedTest("every concrete test suite is registered", this::allSuitesRegistered),
                new NamedTest("default IBC configuration resource is present and parseable", this::defaultConfigResource),
                new NamedTest("manager distribution does not bundle the IBC executable JAR", this::noBundledIbcJar),
                new NamedTest("test cleanup retries transient Windows sharing violations",
                        this::transientCleanupRetry),
                new NamedTest("asynchronous assertions count one logical assertion", this::eventuallyCountsOnce));
    }

    private void sourceHygiene() throws Exception {
        List<Path> files = javaSources();
        Assertions.isTrue(files.size() >= 90, "expected a substantial production and test source tree");
        Pattern unresolvedMarker = Pattern.compile("(?i)\\b(?:" + "TO" + "DO|FIX" + "ME|X" + "XX)\\b");
        String stackTraceCall = ".printStack" + "Trace(";
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            Assertions.isFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF
                            && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF,
                    file + " must not contain a UTF-8 BOM");
            String source = new String(bytes, StandardCharsets.UTF_8);
            Assertions.isFalse(source.contains("\t"), file + " contains a tab character");
            Assertions.isFalse(Pattern.compile("(?m)[ \\t]+$").matcher(source).find(),
                    file + " contains trailing whitespace");
            Assertions.isFalse(unresolvedMarker.matcher(source).find(),
                    file + " contains an unresolved work marker");
            Assertions.isFalse(Pattern.compile("(?m)^import\\s+(?:static\\s+)?[^;]+\\.\\*;").matcher(source).find(),
                    file + " contains a wildcard import");
            if (file.startsWith(ROOT.resolve("src/main/java"))) {
                Assertions.isFalse(source.contains(stackTraceCall),
                        file + " must use controlled logging instead of printStackTrace");
            }
        }
    }

    private void packageLayout() throws Exception {
        for (Path sourceFile : javaSources()) {
            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            Matcher matcher = PACKAGE.matcher(source);
            Assertions.isTrue(matcher.find(), sourceFile + " must declare a package");
            Path sourceRoot;
            if (sourceFile.startsWith(ROOT.resolve("src/main/java"))) {
                sourceRoot = ROOT.resolve("src/main/java");
            } else if (sourceFile.startsWith(ROOT.resolve("src/test/java"))) {
                sourceRoot = ROOT.resolve("src/test/java");
            } else {
                sourceRoot = ROOT.resolve("src/build/java");
            }
            String expected = sourceRoot.relativize(sourceFile.getParent()).toString()
                    .replace(sourceFile.getFileSystem().getSeparator(), ".");
            Assertions.equals(expected, matcher.group(1), sourceFile + " package/path mismatch");
        }
    }

    private void dependencyBoundary() throws Exception {
        Set<String> external = new HashSet<>();
        for (Path file : mainSources()) {
            Matcher matcher = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.startsWith("java.") && !imported.startsWith("javax.")
                        && !imported.startsWith("io.github.ibcmanager.")) {
                    external.add(imported);
                }
            }
        }
        Assertions.equals(Set.of(), external, "production imports must be JDK or internal only");
        String build = Files.readString(ROOT.resolve("build.xml"), StandardCharsets.UTF_8);
        Assertions.notContains(build, "<dependency", "Ant build must not fetch hidden dependencies");
        Assertions.notContains(build, "maven", "Ant build must remain offline and dependency-free");
    }

    private void profileHasNoSecret() {
        for (Field field : Profile.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            Assertions.isFalse(name.contains("password") || name.contains("secret") || name.contains("token"),
                    "persistent profile model contains a secret field: " + field.getName());
        }
    }

    private void noCredentialArguments() throws Exception {
        String launch = source("src/main/java/io/github/ibcmanager/runtime/LaunchScriptFactory.java");
        Assertions.notContains(launch, "/PW:", "IBC launch command must not contain password argument syntax");
        Assertions.notContains(launch, "/User:", "IBC launch command must not contain username argument syntax");
        Assertions.notContains(launch, "/FIXPW:", "IBC launch command must not contain FIX password syntax");
        String task = source("src/main/java/io/github/ibcmanager/task/TaskSchedulerCommandBuilder.java");
        Assertions.notContains(task.toUpperCase(Locale.ROOT), "/RU SYSTEM",
                "startup task must not run as LocalSystem");
        Assertions.contains(task, "/IT", "startup task must require an interactive desktop");
        Assertions.contains(task, "/RL", "startup task must specify privilege level");
        Assertions.contains(task, "LIMITED", "startup task must use limited privilege");
    }

    private void noGlobalAutomation() throws Exception {
        String all = allMainSource();
        Assertions.notContains(all, "java.awt.Robot", "global keyboard/mouse automation is forbidden");
        Assertions.notContains(all, "ProcessHandle.allProcesses", "global process enumeration is forbidden");
        Assertions.notContains(all, "EnumWindows", "global title-based window enumeration is forbidden");
        Assertions.notContains(all, "SetForegroundWindow", "global focus manipulation is forbidden");
        Assertions.notContains(all, "pyautogui", "Python screen automation must not be introduced");
        Assertions.contains(all, "StartIBC.bat", "official IBC launcher delegation must remain present");
    }

    private void quietCommandServerMonitoring() throws Exception {
        String controller = source("src/main/java/io/github/ibcmanager/runtime/ProfileRuntimeController.java");
        String refresh = methodBody(controller, "public synchronized void refresh()",
                "public synchronized IbcCommandResult restartSession()");
        Assertions.notContains(refresh, "profile.commandServerPort()",
                "periodic refresh must not connect to the IBC command server");
        Assertions.contains(refresh, "commandServerOpenForRefresh()",
                "refresh must derive command readiness through the quiet lifecycle tracker");

        String sendCommand = methodBody(controller, "private IbcCommandResult sendCommand(",
                "private synchronized void handleProcessExit()");
        Assertions.notContains(sendCommand, "portProbe.isOpen",
                "a real IBC command must not be preceded by a redundant TCP probe");
        Assertions.contains(controller, "commandProbeFallbackPending",
                "reattached processes must retain one bounded compatibility fallback");

        String parser = source("src/main/java/io/github/ibcmanager/runtime/IbcLogStateParser.java");
        Assertions.contains(parser, "commandserver started and is ready to accept commands",
                "command readiness must be recognized from official IBC lifecycle output");
        Assertions.contains(parser, "commandserver closing",
                "command shutdown must invalidate cached readiness");
    }

    private void passiveApiMonitoring() throws Exception {
        String probe = source("src/main/java/io/github/ibcmanager/runtime/ListeningPortProbe.java");
        Assertions.notContains(probe, "java.net.Socket",
                "listener monitoring must not use a client socket");
        Assertions.notContains(probe, "new Socket(",
                "listener monitoring must not instantiate a client socket");
        Assertions.notContains(probe, ".connect(",
                "listener monitoring must not connect to the IB API port");
        Assertions.contains(probe, "netstat.exe",
                "Windows listener monitoring must inspect the operating-system socket table");
        Assertions.contains(probe, "/proc/net/tcp",
                "Linux listener monitoring must inspect the operating-system socket table");

        String services = source("src/main/java/io/github/ibcmanager/app/AppServices.java");
        Assertions.contains(services, "PortProbe portProbe = new ListeningPortProbe",
                "all profile controllers must share one cached passive listener probe");
        Assertions.notContains(services, "new TcpPortProbe",
                "the former connect-and-close API probe must not be wired into production");

        String controller = source("src/main/java/io/github/ibcmanager/runtime/ProfileRuntimeController.java");
        Assertions.contains(controller, "portProbe.invalidate()",
                "launch preflight must force a fresh passive listener snapshot");
    }

    private void sessionActionConfirmationWiring() throws Exception {
        String mainFrame = source("src/main/java/io/github/ibcmanager/ui/MainFrame.java");
        Assertions.contains(mainFrame, "startButton.addActionListener(event -> confirmStartSelected())",
                "manual Start must pass through its confirmation handler");
        Assertions.contains(mainFrame, "ProfileSessionAction.STOP.configureButton(stopButton)",
                "Stop must use the same emphasized session-button presentation as the other actions");
        Assertions.contains(mainFrame, "stopButton.addActionListener(event -> confirmStopSelected())",
                "manual Stop must pass through its confirmation handler");
        Assertions.contains(mainFrame,
                "if (!ProfileSessionAction.STOP.confirm(this, controller.profile())) return;",
                "Stop must stop immediately when confirmation is declined");
        Assertions.contains(mainFrame, "stop(controller, false)",
                "confirmed normal Stop must remain a graceful stop operation");
        Assertions.notContains(mainFrame, "stopButton.addActionListener(event -> stopSelected(false))",
                "normal Stop must not bypass confirmation");
        Assertions.contains(mainFrame, "ProfileSessionAction.RESTART, \"Restart\"",
                "manual Restart must pass through its confirmation handler");
        Assertions.contains(mainFrame, "ProfileSessionAction.PAUSE, \"Pause\"",
                "manual Pause must pass through its confirmation handler");
        Assertions.contains(mainFrame, "if (!ProfileSessionAction.START.confirm(this, controller.profile())) return;",
                "Start must stop immediately when confirmation is declined");
        Assertions.contains(mainFrame, "if (!action.confirm(this, controller.profile())) return;",
                "Restart and Pause must stop immediately when confirmation is declined");
        Assertions.notContains(mainFrame,
                "restartButton.addActionListener(event -> runCommand(\"Restart\"",
                "Restart must not bypass confirmation");
        Assertions.notContains(mainFrame,
                "pauseButton.addActionListener(event -> runCommand(\"Pause\"",
                "Pause must not bypass confirmation");
        String policy = source("src/main/java/io/github/ibcmanager/ui/ProfileSessionAction.java");
        Assertions.contains(policy, "Object[] options = {prompt.confirmationLabel(), \"Cancel\"}",
                "session confirmations must always provide an explicit Cancel option");
        Assertions.contains(policy, "options, options[1]",
                "Cancel must be the initially selected session-confirmation option");
    }

    private void portableTestSubprocesses() throws Exception {
        String quotedShell = "\"ba" + "sh\"";
        String processBuilderShell = "new ProcessBuilder(" + quotedShell;
        String commandListShell = "List.of(" + quotedShell;
        String unixZeroDevice = "/dev/" + "zero";
        try (Stream<Path> stream = Files.walk(ROOT.resolve("src/test/java"))) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String testSource = Files.readString(file, StandardCharsets.UTF_8);
                Assertions.notContains(testSource, processBuilderShell,
                        file + " must not require bash for an executable test");
                Assertions.notContains(testSource, commandListShell,
                        file + " must not require bash for an executable test");
                Assertions.notContains(testSource, unixZeroDevice,
                        file + " must not require Unix device files");
            }
        }
        String support = source("src/test/java/io/github/ibcmanager/tests/TestSupport.java");
        String fixture = source("src/test/java/io/github/ibcmanager/tests/SubprocessFixture.java");
        Assertions.contains(support, "System.getProperty(\"java.home\")",
                "test subprocesses must use the current JDK instead of a platform shell");
        Assertions.contains(support, "java.class.path",
                "test subprocesses must retain the active compiled-test classpath");
        Assertions.contains(fixture, "TestSupport.javaCommand(\"sleep\"",
                "process-tree fixture must create its child through the same portable Java path");
        Assertions.contains(fixture, "spawn-child-after-signal",
                "dynamic-descendant coverage must use a portable cooperative shutdown signal");
        Assertions.notContains(fixture, "addShutdownHook",
                "process termination tests must not assume external termination runs JVM shutdown hooks");
    }

    private void noTotpImplementation() throws Exception {
        String all = allMainSource();
        Assertions.notContains(all, "javax.crypto.Mac", "no OTP HMAC implementation should be present");
        Assertions.notContains(all, "HmacSHA1", "no TOTP algorithm should be present");
        Assertions.notContains(all, "Base32", "no TOTP Base32 secret handling should be present");
        Assertions.isFalse(mainSources().stream().anyMatch(path ->
                        path.getFileName().toString().toLowerCase(Locale.ROOT).contains("totp")),
                "no TOTP implementation class should exist");
    }

    private void ibcNotices() throws Exception {
        Path retainedLicense = ROOT.resolve("third_party/ibc-3.24.2/LICENSE.txt");
        Path projectLicense = ROOT.resolve("LICENSE.txt");
        Path version = ROOT.resolve("third_party/ibc-3.24.2/version.txt");
        Assertions.fileExists(retainedLicense, "IBC license must be retained");
        Assertions.fileExists(projectLicense, "project GPL license must be present");
        Assertions.fileExists(version, "IBC compatibility-floor marker must be retained");
        String license = Files.readString(retainedLicense, StandardCharsets.UTF_8);
        Assertions.contains(license, "GNU GENERAL PUBLIC LICENSE", "retained license is not GPL");
        Assertions.contains(license, "Version 3", "retained license must be GPL version 3");
        Assertions.equals(Version.IBC_MINIMUM_SUPPORTED_VERSION, Files.readString(version, StandardCharsets.UTF_8).trim(),
                "code and retained IBC compatibility floor must agree");
        Assertions.contains(Files.readString(ROOT.resolve("NOTICE.txt"), StandardCharsets.UTF_8),
                "not affiliated", "unofficial-project notice must be explicit");
    }

    private void dynamicLatestIbcInstaller() throws Exception {
        String resolver = source(
                "src/main/java/io/github/ibcmanager/install/GithubLatestIbcReleaseResolver.java");
        String service = source(
                "src/main/java/io/github/ibcmanager/install/IbcInstallerService.java");
        String version = source("src/main/java/io/github/ibcmanager/app/Version.java");
        String installerUi = source("src/main/java/io/github/ibcmanager/ui/ProfileEditorDialog.java");

        Assertions.contains(resolver, "/repos/IbcAlpha/IBC/releases/latest",
                "installer must query GitHub's latest-release endpoint");
        Assertions.contains(resolver, "browser_download_url",
                "latest-release metadata must select the published asset URL");
        Assertions.contains(resolver, "sha256:",
                "latest-release metadata must require GitHub's published SHA-256 digest");
        Assertions.contains(version, "IBC_MINIMUM_SUPPORTED_VERSION",
                "future releases must retain a reviewed compatibility floor");
        Assertions.contains(installerUi, "Install latest IBC from GitHub...",
                "profile UI must describe the dynamic latest-release behavior");
        Assertions.notContains(service, "OFFICIAL_WINDOWS_ARCHIVE_SHA256",
                "installer must not retain a release-specific checksum pin");
        Assertions.notContains(service, "/releases/download/3.24.2/",
                "installer service must not retain a fixed 3.24.2 asset URL");
        Assertions.notContains(resolver, "/releases/download/3.24.2/",
                "latest-release resolver must not retain a fixed release URL");
    }

    private void releaseFiles() throws Exception {
        for (String file : List.of(
                "README.md", "CHANGELOG.md", "LICENSE.txt", "NOTICE.txt", "TEST_REPORT.md", "CODE_REVIEW.md", "build.xml",
                "SECURITY.md", "CONTRIBUTING.md", "RELEASE_CHECKLIST.md", ".gitignore", ".gitattributes",
                ".github/workflows/build.yml", ".github/ISSUE_TEMPLATE/bug_report.yml",
                "run.bat", "build.bat", "test.bat", "package-windows.bat", "validate-windows.bat",
                "docs/ARCHITECTURE.md", "docs/SECURITY.md", "docs/TESTING.md",
                "docs/USER_GUIDE.md", "docs/WINDOWS_VALIDATION_CHECKLIST.md",
                "scripts/bootstrap.bat", "scripts/ensure-prerequisites.ps1",
                "src/build/java/io/github/ibcmanager/build/BuildProject.java",
                "scripts/build.bat", "scripts/test.bat", "scripts/run.bat",
                "scripts/package-windows.bat", "scripts/validate-windows.bat",
                "images/GUI.png")) {
            Assertions.fileExists(ROOT.resolve(file), "missing release file: " + file);
        }
        String readme = Files.readString(ROOT.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.contains(readme, "![IBC Manager main window](images/GUI.png)",
                "README must display the packaged main-window image near its title");
        Assertions.isTrue(Files.size(ROOT.resolve("images/GUI.png")) > 0,
                "README image must not be empty");
        String security = Files.readString(ROOT.resolve("docs/SECURITY.md"), StandardCharsets.UTF_8);
        Assertions.contains(security, "complete lifetime of the official `StartIBC.bat` wrapper",
                "security documentation must describe the wrapper-lifetime runtime config");
        Assertions.notContains(security, "deleted after authentication progresses",
                "security documentation must not claim unsafe post-authentication deletion");
        String windowsChecklist = Files.readString(
                ROOT.resolve("docs/WINDOWS_VALIDATION_CHECKLIST.md"), StandardCharsets.UTF_8);
        Assertions.contains(windowsChecklist,
                "Runtime config remains present after authentication while `StartIBC.bat` is",
                "Windows checklist must validate the wrapper-lifetime runtime config");
        Assertions.notContains(windowsChecklist,
                "Runtime config is removed after the second-factor/running state",
                "Windows checklist must not require the incompatible early deletion behavior");
    }

    private void bufferedLogArchitecture() throws Exception {
        String appLog = Files.readString(
                ROOT.resolve("src/main/java/io/github/ibcmanager/logging/AppLog.java"), StandardCharsets.UTF_8);
        String launcher = Files.readString(
                ROOT.resolve("src/main/java/io/github/ibcmanager/runtime/DefaultProcessLauncher.java"), StandardCharsets.UTF_8);
        String relay = Files.readString(
                ROOT.resolve("src/main/java/io/github/ibcmanager/runtime/BufferedProcessRelay.java"), StandardCharsets.UTF_8);
        Assertions.contains(appLog, "DISK_FLUSH_INTERVAL = Duration.ofSeconds(60)",
                "application logs must use a 60-second production disk interval");
        Assertions.contains(appLog, "PeriodicFileHandler",
                "application logs must use the periodic in-memory handler");
        Assertions.contains(launcher, "DISK_FLUSH_INTERVAL = Duration.ofSeconds(60)",
                "profile process logs must use a 60-second production disk interval");
        Assertions.contains(launcher, "BufferedProcessRelay.class.getName()",
                "profile process output must be handled by the detached buffered relay");
        Assertions.notContains(launcher, "Redirect.appendTo",
                "profile process output must not write directly to disk on every child write");
        Assertions.contains(relay, "PeriodicByteLog",
                "detached relay must use the periodic byte logger");
    }

    private void windowsPackagingScripts() throws Exception {
        String wrapper = Files.readString(ROOT.resolve("package-windows.bat"), StandardCharsets.UTF_8);
        String canonical = Files.readString(ROOT.resolve("scripts/package-windows.bat"), StandardCharsets.UTF_8);
        Assertions.contains(wrapper, "scripts\\package-windows.bat",
                "root packaging launcher must delegate to the canonical script");
        Assertions.contains(wrapper, "%*", "root packaging launcher must forward arguments");
        Assertions.contains(wrapper, "exit /b %ERRORLEVEL%",
                "root packaging launcher must preserve the canonical script exit code");
        Assertions.contains(canonical, "bootstrap.bat\" Package",
                "packaging must use the permission-based prerequisite bootstrap");
        Assertions.contains(canonical, "%IBC_MANAGER_JAVA_EXE%",
                "packaging must invoke the bootstrap-selected Java executable");
        Assertions.contains(canonical,
                "src\\build\\java\\io\\github\\ibcmanager\\build\\BuildProject.java",
                "packaging must use the dependency-free JDK-native build driver");
        Assertions.notContains(canonical, "IBC_MANAGER_ANT_BAT",
                "Windows packaging must not depend on an Apache Ant download");
        Assertions.contains(canonical, "%IBC_MANAGER_JPACKAGE_EXE%",
                "packaging must invoke the verified jpackage path");
        Assertions.contains(canonical, "clean test jar smoke gui-smoke dist",
                "packaging must run all automated build and smoke gates");
        Assertions.contains(canonical, "--type app-image",
                "packaging must produce a self-contained application image");
        Assertions.contains(canonical,
                "set \"JLINK_OPTIONS=--strip-debug --no-man-pages --no-header-files\"",
                "Windows packaging must override jpackage defaults without stripping native commands");
        Assertions.equals(2, count(canonical, "--jlink-options \"%JLINK_OPTIONS%\""),
                "both portable and installer runtimes must retain Java native commands");
        Assertions.notContains(canonical, "--strip-native-commands",
                "Windows packaging must retain runtime/bin/java.exe for the process relay");
        Assertions.contains(canonical, "runtime\\bin\\java.exe",
                "packaging must validate the portable Java process launcher before release assembly");
        Assertions.contains(canonical, "--type exe", "packaging must attempt an EXE installer");
        int exePackaging = canonical.indexOf("--type exe");
        int releaseArchive = canonical.indexOf("windows-release-zip");
        Assertions.isTrue(releaseArchive > exePackaging,
                "the Windows release ZIP must be assembled only after EXE installer creation");
        Assertions.contains(canonical, "IBC_Manager_1.0.21_Release_windows.zip",
                "Windows release ZIP must use the requested versioned filename");
        Assertions.contains(canonical, "--main-class io.github.ibcmanager.app.IbcManagerApp",
                "packaging must use the production entry point");
        Assertions.contains(canonical, "IBC-Manager-1.0.21.jar",
                "packaging must use the versioned release JAR");
        String driver = Files.readString(
                ROOT.resolve("src/build/java/io/github/ibcmanager/build/BuildProject.java"),
                StandardCharsets.UTF_8);
        Assertions.contains(driver, "_Release_windows.zip",
                "the build driver must create the Windows release archive name");
        Assertions.contains(driver, "findSingleWindowsInstaller",
                "Windows release assembly must reject missing or ambiguous installers");
        Assertions.contains(driver, "copyTree(appImageDirectory, stageBase.resolve(\"IBC Manager\"))",
                "Windows release archive must contain the complete portable application folder");
        Assertions.contains(driver, "runtime/bin/java.exe",
                "Windows archive validation must require the Java launcher used by the detached relay");
        Assertions.contains(driver, "Rebuild the app image without jlink --strip-native-commands",
                "missing portable Java launcher errors must identify the jlink packaging cause");
        Assertions.notContains(driver, "copyTree(normalReleaseRoot, stageRoot)",
                "Windows release archive must not duplicate the normal release files");
        Assertions.contains(driver, "zip.getEntry(\"SHA256SUMS.txt\") == null",
                "Windows release archive must exclude extra checksum and documentation files");
        Assertions.contains(driver, "verifyWindowsReleaseArchiveAssembly",
                "the executable build-driver self-test must assemble and inspect a synthetic Windows archive");
        Assertions.notContains(canonical, "/PW:", "packaging script must not accept an IBC password");
        Assertions.notContains(canonical, "/User:", "packaging script must not accept an IBC username");
    }

    private void noGeneratedArtifacts() throws Exception {
        for (Path sourceRoot : List.of(ROOT.resolve("src"), ROOT.resolve("docs"), ROOT.resolve("scripts"))) {
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    Assertions.isFalse(lower.endsWith(".class") || lower.endsWith(".exe")
                                    || lower.endsWith(".dll") || lower.endsWith(".jar")
                                    || lower.endsWith(".tmp") || lower.endsWith(".pyc"),
                            "generated binary found in source/documentation tree: " + file);
                }
            }
        }
    }

    private void java17Bytecode() throws Exception {
        try (InputStream raw = Version.class.getResourceAsStream("/io/github/ibcmanager/app/Version.class")) {
            Assertions.isTrue(raw != null, "compiled Version.class resource must be available");
            try (DataInputStream input = new DataInputStream(raw)) {
                Assertions.equals(0xCAFEBABE, input.readInt(), "invalid class-file magic");
                input.readUnsignedShort();
                int major = input.readUnsignedShort();
                Assertions.equals(61, major, "production bytecode must target Java 17");
            }
        }
    }

    private void allSuitesRegistered() throws Exception {
        String runner = source("src/test/java/io/github/ibcmanager/tests/TestRunner.java");
        List<Path> suiteFiles;
        try (Stream<Path> stream = Files.walk(ROOT.resolve("src/test/java"))) {
            suiteFiles = stream.filter(path -> path.getFileName().toString().endsWith("Tests.java"))
                    .sorted().toList();
        }
        Assertions.isTrue(suiteFiles.size() >= 12, "expected broad suite coverage");
        for (Path file : suiteFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!source.contains("implements TestSuite")) continue;
            Matcher matcher = PACKAGE.matcher(source);
            Assertions.isTrue(matcher.find(), file + " package declaration missing");
            String className = file.getFileName().toString().replace(".java", "");
            String fullyQualified = matcher.group(1) + "." + className;
            Assertions.contains(runner, '"' + fullyQualified + '"',
                    "test suite is not registered: " + fullyQualified);
        }
    }

    private void defaultConfigResource() throws Exception {
        Path resource = ROOT.resolve("src/main/resources/default-config.ini");
        Assertions.fileExists(resource, "default IBC config resource missing");
        String text = Files.readString(resource, StandardCharsets.UTF_8);
        Assertions.isTrue(text.length() > 10_000, "retained config template appears truncated");
        Assertions.contains(text, "IbLoginId=", "config template lacks login setting");
        Assertions.contains(text, "CommandServerPort=", "config template lacks command server setting");
        Assertions.contains(text, "TradingMode=", "config template lacks trading mode setting");
        Class<?> documentType = Class.forName("io.github.ibcmanager.config.IbcConfigDocument");
        Object parsed = documentType.getMethod("parse", String.class).invoke(null, text);
        String rendered = (String) documentType.getMethod("render").invoke(parsed);
        Assertions.equals(text, rendered, "unmodified template must parse/render exactly");
    }

    private void noBundledIbcJar() throws Exception {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(ROOT)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().equalsIgnoreCase("IBC.jar")) matches.add(path);
            }
        }
        Assertions.equals(List.of(), matches, "IBC binary must remain a separate user installation");
    }

    private void transientCleanupRetry() throws Exception {
        AtomicInteger transientAttempts = new AtomicInteger();
        TestSupport.retryTransientFileOperation(() -> {
            if (transientAttempts.incrementAndGet() < 3) {
                throw new FileSystemException("temporarily locked");
            }
        }, 4, 0);
        Assertions.equals(3, transientAttempts.get(),
                "transient file-system failures must be retried until cleanup succeeds");

        AtomicInteger permanentAttempts = new AtomicInteger();
        Assertions.throwsType(IOException.class, () ->
                TestSupport.retryTransientFileOperation(() -> {
                    permanentAttempts.incrementAndGet();
                    throw new IOException("permanent failure");
                }, 4, 0), "non-file-system failures must not be retried");
        Assertions.equals(1, permanentAttempts.get(),
                "a permanent cleanup failure must stop after the first attempt");

        AtomicInteger exhaustedAttempts = new AtomicInteger();
        Assertions.throwsType(FileSystemException.class, () ->
                TestSupport.retryTransientFileOperation(() -> {
                    exhaustedAttempts.incrementAndGet();
                    throw new FileSystemException("still locked");
                }, 3, 0), "transient cleanup must remain bounded");
        Assertions.equals(3, exhaustedAttempts.get(),
                "bounded cleanup retry must stop at the configured attempt limit");
    }

    private void eventuallyCountsOnce() throws Exception {
        Assertions.eventually(Duration.ofMillis(100), () -> true,
                "immediate asynchronous condition should pass");
        if (Assertions.count() != 1) {
            Assertions.fail("eventually must count one logical assertion, actual=" + Assertions.count());
        }
    }

    private static List<Path> javaSources() throws Exception {
        List<Path> result = new ArrayList<>(mainSources());
        for (Path sourceRoot : List.of(ROOT.resolve("src/test/java"), ROOT.resolve("src/build/java"))) {
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                result.addAll(stream.filter(path -> path.getFileName().toString().endsWith(".java")).toList());
            }
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static List<Path> mainSources() throws Exception {
        try (Stream<Path> stream = Files.walk(ROOT.resolve("src/main/java"))) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted().toList();
        }
    }

    private static String allMainSource() throws Exception {
        StringBuilder result = new StringBuilder();
        for (Path file : mainSources()) result.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        return result.toString();
    }

    private static int count(String source, String token) {
        int result = 0;
        int from = 0;
        while (true) {
            int index = source.indexOf(token, from);
            if (index < 0) return result;
            result++;
            from = index + token.length();
        }
    }

    private static String methodBody(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        if (start < 0) throw new AssertionError("Missing source token: " + startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        if (end < 0) throw new AssertionError("Missing source token: " + endToken);
        return source.substring(start, end);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("src/main/java")) && Files.isRegularFile(current.resolve("build.xml"))) {
                return current;
            }
        }
        throw new IllegalStateException("Could not locate IBC Manager source root");
    }
}
