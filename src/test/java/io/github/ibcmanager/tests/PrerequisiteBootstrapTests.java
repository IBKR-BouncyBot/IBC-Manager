package io.github.ibcmanager.tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PrerequisiteBootstrapTests implements TestSuite {
    private static final Path ROOT = locateProjectRoot();
    private static final String BUILD_DRIVER =
            "src\\build\\java\\io\\github\\ibcmanager\\build\\BuildProject.java";

    @Override
    public String name() {
        return "Windows prerequisite detection, consent, installation, and launcher safety";
    }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("all Windows entry points use the shared bootstrap", this::entryPointsUseBootstrap),
                new NamedTest("Java 8 is rejected before the application is launched", this::javaEightRejected),
                new NamedTest("source run launcher builds a missing JAR on demand", this::sourceRunBuildsOnDemand),
                new NamedTest("run launcher preflights the JAR before starting javaw", this::runPreflightsJar),
                new NamedTest("installation requires explicit user consent", this::installationRequiresConsent),
                new NamedTest("declining installation is non-destructive", this::declineIsNonDestructive),
                new NamedTest("managed Java installation is isolated from system Java", this::javaInstallIsIsolated),
                new NamedTest("managed Java downloads require cryptographic verification", this::downloadsAreVerified),
                new NamedTest("WiX installation uses exact Windows Package Manager identity", this::wixInstallIsExact),
                new NamedTest("run build and package modes request only required tools", this::modeRequirements),
                new NamedTest("verified executable paths are imported without credentials", this::environmentIsPathOnly),
                new NamedTest("managed tool updates use staging serialization and rollback", this::toolInstallIsTransactional),
                new NamedTest("scripts use selected Java and the JDK-native build driver", this::scriptsUseSelectedTools),
                new NamedTest("Windows launch and build paths have no Apache Ant dependency", this::noAntDependency),
                new NamedTest("build driver enforces strict reproducible release gates", this::buildDriverSafety),
                new NamedTest("concurrent source builds are serialized per source tree", this::buildDriverSerializesBuilds),
                new NamedTest("build driver self-test executes with the current JDK", this::buildDriverExecutes),
                new NamedTest("bootstrap contains parser and escaping self-tests", this::bootstrapSelfTests),
                new NamedTest("empty candidate collections bind in Windows PowerShell",
                        this::emptyCandidateCollectionsBind),
                new NamedTest("protected HOME automatic variable is never rebound",
                        this::protectedHomeVariableIsNeverRebound),
                new NamedTest("PowerShell source has balanced lexical structure", this::powershellLexicalStructure),
                new NamedTest("PowerShell remains compatible with Windows PowerShell 5.1", this::powershellCompatibility),
                new NamedTest("Windows script files use CRLF and no UTF-8 BOM", this::windowsLineEndings),
                new NamedTest("release build includes the runtime prerequisite bootstrap", this::releaseContainsBootstrap),
                new NamedTest("Windows packaging creates the requested versioned release ZIP",
                        this::windowsReleaseZipNaming),
                new NamedTest("versioned launch and package scripts agree with application version", this::versionsAgree));
    }

    private void entryPointsUseBootstrap() throws Exception {
        Map<String, String> scripts = new LinkedHashMap<>();
        scripts.put("scripts/build.bat", "Build");
        scripts.put("scripts/test.bat", "Test");
        scripts.put("scripts/package-windows.bat", "Package");
        scripts.put("scripts/validate-windows.bat", "Validate");
        for (Map.Entry<String, String> entry : scripts.entrySet()) {
            String text = read(entry.getKey());
            Assertions.contains(text, "bootstrap.bat\" " + entry.getValue(),
                    entry.getKey() + " must invoke its exact bootstrap mode");
            Assertions.contains(text, "if not \"%RESULT%\"==\"0\" exit /b %RESULT%",
                    entry.getKey() + " must preserve bootstrap refusal/failure status");
        }

        String run = read("scripts/run.bat");
        Assertions.contains(run, "bootstrap.bat\" Run",
                "release-mode run must request only runtime prerequisites");
        Assertions.contains(run, "bootstrap.bat\" Build",
                "source-mode run must request build prerequisites when the JAR is absent");
        Assertions.isTrue(count(run, "if not \"%RESULT%\"==\"0\"") >= 3,
                "run must preserve prerequisite, build, and preflight failures");

        for (String name : List.of("run", "build", "test", "package-windows", "validate-windows")) {
            String wrapper = read(name + ".bat");
            Assertions.contains(wrapper, "scripts\\" + name + ".bat",
                    name + " root launcher must delegate to its canonical script");
            Assertions.contains(wrapper, "%*", name + " root launcher must forward arguments");
            Assertions.contains(wrapper, "exit /b %ERRORLEVEL%",
                    name + " root launcher must preserve the canonical exit status");
        }
    }

    private void javaEightRejected() throws Exception {
        String bootstrap = read("scripts/ensure-prerequisites.ps1");
        String run = read("scripts/run.bat");
        Assertions.contains(bootstrap, "$MinimumJavaMajor = 17",
                "minimum runtime must remain Java 17");
        Assertions.contains(bootstrap, "'java version \"1.8.0_401\"' = 8",
                "legacy Java version parsing must have a regression case");
        Assertions.contains(bootstrap, "$major -lt $MinimumJavaMajor",
                "incompatible Java must be rejected during discovery");
        Assertions.contains(run, "call \"%~dp0bootstrap.bat\" Run",
                "run must check Java before starting the release JAR");
        Assertions.contains(run, "%IBC_MANAGER_JAVAW_EXE%",
                "run must use the verified javaw executable");
        Assertions.notContains(run, "start \"IBC Manager\" javaw",
                "run must not trust the first javaw on PATH");
    }

    private void sourceRunBuildsOnDemand() throws Exception {
        String run = read("scripts/run.bat");
        int missingJar = run.indexOf("if exist \"%JAR%\" goto bootstrap_runtime");
        int sourceCheck = run.indexOf("if not exist \"build.xml\" goto jar_missing");
        int driverCheck = run.indexOf("if not exist \"%BUILD_DRIVER%\" goto jar_missing");
        int buildBootstrap = run.indexOf("call \"%~dp0bootstrap.bat\" Build");
        int nativeBuild = run.indexOf("\"%IBC_MANAGER_JAVA_EXE%\" -Dfile.encoding=UTF-8 \"%BUILD_DRIVER%\" self-test clean jar smoke");
        int builtJarCheck = run.indexOf("if not exist \"%JAR%\" (", nativeBuild);
        Assertions.isTrue(missingJar >= 0, "run must distinguish an existing release JAR from source mode");
        Assertions.isTrue(sourceCheck > missingJar, "source tree must be validated only after the JAR is absent");
        Assertions.isTrue(driverCheck > sourceCheck, "source build driver must be checked before build bootstrap");
        Assertions.isTrue(buildBootstrap > driverCheck, "source JDK prerequisites must be checked before building");
        Assertions.isTrue(nativeBuild > buildBootstrap, "the selected Java must execute the source build driver");
        Assertions.isTrue(builtJarCheck > nativeBuild, "run must verify that the source build produced the JAR");
        Assertions.contains(run, BUILD_DRIVER, "source launcher must use the in-repository build driver");
        Assertions.contains(run, "build the JAR without Apache Ant",
                "automatic source build must explain that no Ant download is required");
        Assertions.notContains(run, "Build the source with build.bat",
                "run must not stop with an avoidable manual-build instruction");
    }

    private void runPreflightsJar() throws Exception {
        String run = read("scripts/run.bat");
        int preflight = run.indexOf("\"%IBC_MANAGER_JAVA_EXE%\" -Dfile.encoding=UTF-8 -jar \"%JAR%\" --version");
        int graphicalStart = run.indexOf("start \"IBC Manager\" \"%IBC_MANAGER_JAVAW_EXE%\"");
        Assertions.isTrue(preflight >= 0, "run must verify the selected Java can load the JAR");
        Assertions.isTrue(graphicalStart > preflight, "JAR preflight must happen before the console is detached");
        Assertions.contains(run, "The Java runtime could not load %JAR%.",
                "JAR or bytecode failures must remain visible in the console");
    }

    private void installationRequiresConsent() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        int mainBlock = script.lastIndexOf("if ($missing.Count -gt 0)");
        int consent = script.indexOf("Confirm-PrerequisiteInstallation", mainBlock);
        int javaInstall = script.indexOf("Install-ManagedJdk", consent);
        int wixInstall = script.indexOf("Install-WixWithWinget", consent);
        Assertions.isTrue(mainBlock > 0, "main missing-prerequisite block must exist");
        Assertions.isTrue(consent > mainBlock, "consent must be requested in the main installation block");
        Assertions.isTrue(javaInstall > consent, "Java installation must occur after consent");
        Assertions.isTrue(wixInstall > consent, "WiX installation must occur after consent");
        Assertions.contains(script, "Read-Host 'Proceed with prerequisite installation? [Y/N]'",
                "permission prompt must clearly require a yes/no response");
        Assertions.contains(script, "'^(?i:y|yes)$'",
                "only an explicit affirmative answer may authorize installation");
    }

    private void declineIsNonDestructive() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "Prerequisite installation was declined. No changes were made by this script.",
                "decline path must state that no changes were made");
        Assertions.contains(script, "exit 3", "decline must return a distinct nonzero status");
        Assertions.contains(script, "Existing Java 8 installations and the system PATH will not be removed or changed.",
                "prompt must accurately describe non-destructive behavior");
        Assertions.notContains(script, "SetEnvironmentVariable(",
                "bootstrap must not persistently alter user or machine environment variables");
        Assertions.notContains(script.toLowerCase(), "winget uninstall",
                "bootstrap must not uninstall packages");
        Assertions.notContains(script.toLowerCase(), "setx ",
                "bootstrap must not write persistent PATH values");
    }

    private void javaInstallIsIsolated() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "$env:LOCALAPPDATA", "managed tools must prefer current-user storage");
        Assertions.contains(script, "IBCManager\\tools", "managed tools must use a dedicated directory");
        Assertions.contains(script, "$ManagedJdkHome = Join-Path $ManagedToolsRoot 'microsoft-jdk-17'",
                "managed Java must have a stable private location");
        Assertions.contains(script, "No Java 8 installation was removed.",
                "failure output must explicitly preserve Java 8");
        Assertions.notContains(script, "msiexec", "Java installation must not modify machine-wide MSI state");
        Assertions.notContains(script, "Remove-Item -LiteralPath $env:JAVA_HOME",
                "existing JAVA_HOME installations must never be removed");
    }

    private void downloadsAreVerified() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "https://aka.ms/download-jdk/microsoft-jdk-17-windows-$architecture.zip",
                "Java must come from Microsoft's stable OpenJDK endpoint");
        Assertions.contains(script, ".sha256sum.txt", "Microsoft's published SHA-256 file must be downloaded");
        Assertions.contains(script, "Assert-FileChecksum $archive 'SHA256' $expected",
                "Java archive must be verified before extraction");
        int install = script.indexOf("function Install-ManagedJdk");
        int verify = script.indexOf("Assert-FileChecksum $archive 'SHA256' $expected", install);
        int extract = script.indexOf("Expand-Archive -LiteralPath $archive", install);
        Assertions.isTrue(install >= 0 && verify > install && extract > verify,
                "Java checksum verification must precede extraction");
        Assertions.contains(script, "Invoke-WebRequest -UseBasicParsing",
                "managed downloads must use the bounded Windows PowerShell-compatible helper");
    }

    private void wixInstallIsExact() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "$WixPackageId = 'WiXToolset.WiXToolset'",
                "WiX must use the official exact package identifier");
        Assertions.contains(script, "'install', '--id', $WixPackageId, '--exact', '--source', 'winget'",
                "winget must use exact-ID installation from its named source");
        Assertions.contains(script, "--accept-package-agreements",
                "winget package agreement handling must be explicit after user consent");
        Assertions.contains(script, "--accept-source-agreements",
                "winget source agreement handling must be explicit after user consent");
        Assertions.contains(script, "Windows elevation prompt may appear",
                "the user must be warned about possible UAC elevation");
        Assertions.contains(script, "winget) is unavailable",
                "missing Windows Package Manager must fail clearly before partial installation");
        Assertions.notContains(script, "Invoke-Expression", "downloaded or constructed commands must not be evaluated");
    }

    private void modeRequirements() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "$requiresBuildTools = $Mode -ne 'Run'",
                "run mode must not require compiler/build tools");
        Assertions.contains(script, "$requiresJpackage = $Mode -eq 'Package'",
                "jpackage must be required only for packaging");
        Assertions.contains(script, "$requiresWix = $Mode -eq 'Package'",
                "WiX must be required only for Windows installer packaging");
        Assertions.contains(script, "Java 17 or newer runtime",
                "run-mode requirement must be described accurately");
        Assertions.contains(script, "Java 17 or newer JDK with javac and jar",
                "build-mode requirement must be described accurately");
        Assertions.contains(script, "Java 17 or newer JDK with javac, jar, and jpackage",
                "package-mode requirement must be described accurately");
        Assertions.contains(script, "Apache Ant is intentionally not a prerequisite.",
                "bootstrap must explicitly document the removed Ant requirement");
    }

    private void environmentIsPathOnly() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        for (String name : List.of(
                "IBC_MANAGER_JAVA_EXE", "IBC_MANAGER_JAVAW_EXE", "IBC_MANAGER_JAVAC_EXE",
                "IBC_MANAGER_JAR_EXE", "IBC_MANAGER_JPACKAGE_EXE")) {
            Assertions.contains(script, name, "verified environment must export " + name);
        }
        Assertions.notContains(script, "IBC_MANAGER_ANT_BAT",
                "temporary environment must not export a removed Ant dependency");
        String writer = between(script, "function Write-EnvironmentBatchFile", "function Invoke-SelfTest");
        Assertions.notContains(writer.toLowerCase(), "password", "temporary environment file must not contain passwords");
        Assertions.notContains(writer.toLowerCase(), "credential", "temporary environment file must not contain credentials");
        Assertions.notContains(writer.toLowerCase(), "username", "temporary environment file must not contain usernames");
        Assertions.contains(script, "[Console]::OutputEncoding.CodePage",
                "environment data must use the active console code page");
        Assertions.contains(script, "[Text.EncoderFallback]::ExceptionFallback",
                "unrepresentable paths must fail rather than be silently corrupted");
        Assertions.contains(script, "Run chcp 65001",
                "encoding failures must provide an actionable UTF-8 recovery instruction");
        String batch = read("scripts/bootstrap.bat");
        Assertions.contains(batch, "del /f /q \"%IBC_MANAGER_BOOTSTRAP_ENV%\"",
                "temporary environment file must be deleted");
        Assertions.contains(batch, "if exist \"%IBC_MANAGER_BOOTSTRAP_ENV%\" del",
                "temporary environment file must also be deleted on failure");
    }

    private void toolInstallIsTransactional() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "Local\\IBCManager-PrerequisiteBootstrap-v1",
                "concurrent installers must be serialized by a named mutex");
        Assertions.contains(script, "AbandonedMutexException",
                "abandoned installers must not permanently block recovery");
        Assertions.contains(script, "Complete-ManagedDirectoryInstall",
                "managed installs must use one transaction helper");
        Assertions.contains(script, ".old-", "existing installations must be retained for rollback");
        Assertions.contains(script, ".new-", "downloads must stage outside the active installation");
        Assertions.contains(script, "Move-Item -LiteralPath $backup -Destination $Destination",
                "failed activation must attempt to restore the prior installation");
        Assertions.contains(script, "Failure to delete an obsolete backup must not roll back",
                "post-activation cleanup failure must not discard the verified replacement");
        Assertions.contains(script, "The new managed tool is active, but its obsolete backup could not be removed",
                "post-activation cleanup failure must be visible and non-destructive");
        Assertions.contains(script, "finally {", "temporary resources must have finally cleanup");
    }

    private void scriptsUseSelectedTools() throws Exception {
        String combined = String.join("\n", List.of(
                read("scripts/run.bat"), read("scripts/build.bat"), read("scripts/test.bat"),
                read("scripts/package-windows.bat"), read("scripts/validate-windows.bat")));
        Assertions.notContains(combined.toLowerCase(), "where java", "entry points must not trust PATH Java");
        Assertions.notContains(combined.toLowerCase(), "where ant", "entry points must not inspect PATH Ant");
        Assertions.notContains(combined.toLowerCase(), "where jpackage", "entry points must not trust PATH jpackage");
        Assertions.contains(combined, "%IBC_MANAGER_JAVA_EXE%", "build and validation must use selected Java");
        Assertions.contains(combined, "%IBC_MANAGER_JAVAW_EXE%", "GUI launch must use selected javaw");
        Assertions.contains(combined, BUILD_DRIVER, "build entry points must use the JDK-native build driver");
        Assertions.contains(combined, "%IBC_MANAGER_JPACKAGE_EXE%", "packaging must use selected jpackage");
        Assertions.notContains(combined, "IBC_MANAGER_ANT_BAT", "scripts must not use an Ant environment path");
        Assertions.notContains(combined, " /PW:", "scripts must never pass an IBC password");
        Assertions.notContains(combined, " /User:", "scripts must never pass an IBC username");
    }

    private void noAntDependency() throws Exception {
        String bootstrap = read("scripts/ensure-prerequisites.ps1");
        String entryPoints = String.join("\n", List.of(
                read("scripts/run.bat"), read("scripts/build.bat"), read("scripts/test.bat"),
                read("scripts/package-windows.bat"), read("scripts/validate-windows.bat")));
        for (String forbidden : List.of(
                "Install-ManagedAnt", "ManagedAnt", "apache-ant-", "ant/binaries/",
                "IBC_MANAGER_ANT", "Get-Ant", "Find-CompatibleAnt")) {
            Assertions.notContains(bootstrap, forbidden,
                    "bootstrap retains removed Apache Ant dependency: " + forbidden);
            Assertions.notContains(entryPoints, forbidden,
                    "entry point retains removed Apache Ant dependency: " + forbidden);
        }
        Assertions.contains(entryPoints, "BuildProject.java",
                "the JDK-native driver must replace Ant in every build path");
        String compatibilityBuild = read("build.xml");
        Assertions.contains(compatibilityBuild, "<project name=\"IBC Manager\"",
                "optional build.xml compatibility must remain available for existing Ant users");
        Assertions.contains(compatibilityBuild, "BuildProject.java",
                "optional Ant compatibility must delegate to the same JDK-native driver");
        Assertions.notContains(compatibilityBuild, "<javac",
                "optional Ant compatibility must not maintain a second unlocked compiler path");
    }

    private void buildDriverSafety() throws Exception {
        String driver = read("src/build/java/io/github/ibcmanager/build/BuildProject.java");
        Assertions.contains(driver, "ToolProvider.getSystemJavaCompiler()",
                "driver must require the selected full JDK compiler");
        Assertions.contains(driver, "\"--release\", \"17\"",
                "driver must target Java 17 bytecode");
        Assertions.contains(driver, "\"-Xlint:all\"",
                "driver must enable all compiler warnings");
        Assertions.contains(driver, "\"-Werror\"",
                "driver must fail on compiler warnings");
        Assertions.contains(driver, "Instant.parse(\"1980-01-01T00:00:00Z\")",
                "driver must pin archive timestamps for reproducibility");
        Assertions.contains(driver, "createDistributions()",
                "driver must produce release and source archives");
        Assertions.contains(driver, "createWindowsReleaseArchive",
                "driver must assemble the post-jpackage Windows release ZIP");
        Assertions.contains(driver, "IBC_Manager_\" + releaseVersion + \"_Release_windows.zip",
                "Windows release archive must use the requested filename suffix");
        Assertions.contains(driver, "validateWindowsReleaseArchive",
                "driver must inspect the completed Windows release ZIP");
        Assertions.contains(driver, "sha256(installer)",
                "Windows release archive must publish the installer checksum");
        Assertions.contains(driver, "includeInSourceArchive",
                "driver must filter generated files from the source archive");
        for (String excluded : List.of("build/", "dist/", ".git/", ".idea/", ".vscode/", ".log", ".tmp", ".class")) {
            Assertions.contains(driver, excluded,
                    "source archive exclusion missing for " + excluded);
        }
        Assertions.contains(driver, "runSmokeTests()", "driver must run packaged JAR smoke checks");
        Assertions.contains(driver, "Build-driver self-test passed", "driver must expose deterministic self-tests");
        Assertions.contains(driver, "readBuildXmlVersion", "driver must cross-check optional build.xml version");
    }

    private void buildDriverSerializesBuilds() throws Exception {
        String driver = read("src/build/java/io/github/ibcmanager/build/BuildProject.java");
        Assertions.contains(driver, "requiresBuildLock(arguments)",
                "all mutating build invocations must pass through the project build lock");
        Assertions.contains(driver, "BuildLock.acquire(root, Duration.ofMinutes(15))",
                "normal builds must wait a bounded time for the source-tree lock");
        Assertions.contains(driver, "!target.equals(\"self-test\") && !target.equals(\"version\")",
                "only non-mutating self-test and version targets may bypass the main lock");
        Assertions.contains(driver, "channel.tryLock()",
                "build serialization must use an operating-system file lock");
        Assertions.contains(driver, "OverlappingFileLockException",
                "same-JVM concurrent builds must be treated as lock contention");
        Assertions.contains(driver, "Another build is active for this source tree; waiting...",
                "lock contention must be visible rather than appearing hung");
        Assertions.contains(driver, "Timed out waiting for another build of this source tree to finish.",
                "build-lock waiting must have a bounded failure path");
        Assertions.contains(driver, "verifyBuildLockSerialization(lockRoot)",
                "the executable driver self-test must exercise lock serialization");
        Assertions.contains(driver, "MessageDigest.getInstance(\"SHA-256\")",
                "lock identity must be derived from the canonical source-tree path");
        Assertions.contains(driver, "System.getProperty(\"java.io.tmpdir\")",
                "the lock file must live outside cleanable build output");
        Assertions.contains(driver, "Files.deleteIfExists(BuildLock.buildLockPath(lockRoot))",
                "the executable lock self-test must remove its temporary lock file");
        Assertions.contains(driver, "buildLock.ensureHeld()",
                "try-with-resources must retain and validate the acquired lock");
    }

    private void buildDriverExecutes() throws Exception {
        String executableName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executableName)
                .toAbsolutePath().normalize();
        Path driver = ROOT.resolve("src/build/java/io/github/ibcmanager/build/BuildProject.java");
        Assertions.fileExists(java, "current JDK Java executable must exist");
        Assertions.fileExists(driver, "JDK-native build driver must exist");
        Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8",
                driver.toString(), "self-test")
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Assertions.fail("JDK-native build-driver self-test timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.equals(0, process.exitValue(), "build-driver self-test failed: " + output);
        Assertions.contains(output, "Build-driver self-test passed for version 1.0.8",
                "build-driver self-test must report the current release version");
    }

    private void emptyCandidateCollectionsBind() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        String function = between(script, "function Add-CandidateHome", "function Get-JavaCandidateHomes");
        Assertions.equals(2, count(function, "[AllowEmptyCollection()]"),
                "both initially empty generic collections must explicitly allow empty binding");
        Assertions.contains(function, "[System.Collections.Generic.List[string]] $List",
                "candidate list parameter must remain strongly typed");
        Assertions.contains(function, "[System.Collections.Generic.HashSet[string]] $Seen",
                "candidate de-duplication set must remain strongly typed");
        Assertions.contains(script,
                "Add-CandidateHome -List $candidateHomes -Seen $candidateSeen -CandidateHome $ProjectRoot",
                "PowerShell self-test must execute the formerly failing empty-collection binding path");
        Assertions.contains(script,
                "Empty candidate-collection binding or duplicate suppression self-test failed.",
                "PowerShell self-test must verify both insertion and duplicate suppression");
    }

    private void protectedHomeVariableIsNeverRebound() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.isFalse(java.util.regex.Pattern.compile(
                        "(?i)\\$(?:\\{)?home(?:\\})?(?![A-Za-z0-9_])")
                .matcher(script).find(),
                "PowerShell source must not bind, assign, or iterate with the protected HOME name");
        Assertions.contains(script, "[string] $CandidateHome",
                "candidate-path input must use a non-reserved parameter name");
        Assertions.contains(script, "[string] $JavaHome",
                "Java inspection must use a non-reserved parameter name");
        Assertions.contains(script, "foreach ($candidateHome in Get-JavaCandidateHomes)",
                "Java candidate iteration must not target the HOME automatic variable");
        Assertions.contains(script, "Bootstrap source uses the protected HOME automatic-variable name.",
                "the executable PowerShell self-test must reject future HOME collisions");
        Assertions.contains(script, "Invoke-SelfTest -Quiet",
                "every normal launcher path must run bootstrap invariants before discovery");
    }

    private void bootstrapSelfTests() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        String validation = read("scripts/validate-windows.bat");
        Assertions.contains(script, "function Invoke-SelfTest", "bootstrap must expose deterministic self-tests");
        Assertions.contains(script, "1.8.0_401", "self-tests must cover legacy Java 8 syntax");
        Assertions.contains(script, "17.0.12", "self-tests must cover Java 17 syntax");
        Assertions.contains(script, "21.0.5", "self-tests must cover later LTS Java syntax");
        Assertions.contains(script, "Windows Installer XML Toolset Compiler version 3.14.1.8722",
                "self-tests must cover WiX output parsing");
        Assertions.contains(script, "Batch escaping self-test failed",
                "self-tests must cover batch metacharacter escaping");
        Assertions.contains(script, "Runtime prerequisite description self-test failed",
                "self-tests must cover runtime-mode requirement selection");
        Assertions.contains(script, "Packaging prerequisite description self-test failed",
                "self-tests must cover package-mode requirement selection");
        Assertions.contains(validation, "-SelfTest",
                "Windows validation must execute the PowerShell self-tests");
    }

    private void powershellLexicalStructure() throws Exception {
        String source = read("scripts/ensure-prerequisites.ps1");
        Deque<Character> delimiters = new ArrayDeque<>();
        LexState state = LexState.NORMAL;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (state == LexState.LINE_COMMENT) {
                if (current == '\n') state = LexState.NORMAL;
                continue;
            }
            if (state == LexState.BLOCK_COMMENT) {
                if (current == '#' && next == '>') {
                    state = LexState.NORMAL;
                    index++;
                }
                continue;
            }
            if (state == LexState.SINGLE_QUOTE) {
                if (current == '\'' && next == '\'') {
                    index++;
                } else if (current == '\'') {
                    state = LexState.NORMAL;
                }
                continue;
            }
            if (state == LexState.DOUBLE_QUOTE) {
                if (current == '`') {
                    index++;
                } else if (current == '"') {
                    state = LexState.NORMAL;
                }
                continue;
            }

            if (current == '<' && next == '#') {
                state = LexState.BLOCK_COMMENT;
                index++;
            } else if (current == '#') {
                state = LexState.LINE_COMMENT;
            } else if (current == '\'') {
                state = LexState.SINGLE_QUOTE;
            } else if (current == '"') {
                state = LexState.DOUBLE_QUOTE;
            } else if (current == '(' || current == '[' || current == '{') {
                delimiters.push(current);
            } else if (current == ')' || current == ']' || current == '}') {
                Assertions.isFalse(delimiters.isEmpty(), "unmatched PowerShell closing delimiter at " + index);
                char opening = delimiters.pop();
                Assertions.isTrue(matches(opening, current),
                        "mismatched PowerShell delimiters " + opening + " and " + current + " at " + index);
            }
        }
        Assertions.isTrue(state == LexState.NORMAL || state == LexState.LINE_COMMENT,
                "PowerShell source ends inside a quote or block comment: " + state);
        Assertions.equals(List.of(), new ArrayList<>(delimiters),
                "PowerShell source has unclosed structural delimiters");
    }

    private void powershellCompatibility() throws Exception {
        String script = read("scripts/ensure-prerequisites.ps1");
        Assertions.contains(script, "Set-StrictMode -Version 2.0",
                "bootstrap must use a mode supported by Windows PowerShell 5.1");
        Assertions.contains(script, "Invoke-WebRequest -UseBasicParsing",
                "download implementation must be compatible with Windows PowerShell 5.1");
        Assertions.contains(script, "-TimeoutSec $TimeoutSeconds",
                "network downloads must have a bounded timeout");
        Assertions.notContains(script, "ForEach-Object -Parallel",
                "PowerShell 7-only parallel syntax is forbidden");
        Assertions.notContains(script, "??", "PowerShell 7 null-coalescing syntax is forbidden");
        Assertions.notContains(script, "&&", "PowerShell 7 pipeline-chain syntax is forbidden");
        Assertions.notContains(script, "pwsh", "Windows scripts must not require separately installed PowerShell 7");
        Assertions.notContains(script, "Start-Process -Verb RunAs",
                "the bootstrap itself must not silently elevate");
    }

    private void windowsLineEndings() throws Exception {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(ROOT, 2)) {
            files.addAll(stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bat")
                            || path.getFileName().toString().endsWith(".ps1"))
                    .toList());
        }
        Assertions.isTrue(files.size() >= 11, "expected all root and canonical Windows scripts");
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            Assertions.isFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF
                            && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF,
                    file + " must not have a UTF-8 BOM");
            for (int index = 0; index < bytes.length; index++) {
                if (bytes[index] == '\n') {
                    Assertions.isTrue(index > 0 && bytes[index - 1] == '\r',
                            file + " contains a non-CRLF line ending at byte " + index);
                }
            }
        }
    }

    private void releaseContainsBootstrap() throws Exception {
        String antBuild = read("build.xml");
        String nativeBuild = read("src/build/java/io/github/ibcmanager/build/BuildProject.java");
        Assertions.contains(antBuild, "<macrodef name=\"run-build-driver\"",
                "optional Ant compatibility must use one shared driver macro");
        Assertions.contains(antBuild, "<run-build-driver targets=\"dist\"/>",
                "optional Ant dist must delegate archive generation to the native driver");
        Assertions.notContains(antBuild, "<zip ",
                "optional Ant compatibility must not maintain a second archive implementation");
        Assertions.notContains(antBuild, "<jar ",
                "optional Ant compatibility must not maintain a second JAR implementation");
        Assertions.contains(nativeBuild, "RELEASE_SCRIPT_FILES = List.of(",
                "native release builder must declare its runtime scripts");
        for (String script : List.of("run.bat", "bootstrap.bat", "ensure-prerequisites.ps1")) {
            Assertions.contains(nativeBuild, '"' + script + '"',
                    "native release archive must include " + script);
        }
        Assertions.contains(nativeBuild, "IBC_Manager_\" + version + \"_Source/",
                "native source archive must retain a versioned root");
    }

    private void windowsReleaseZipNaming() throws Exception {
        String pack = read("scripts/package-windows.bat");
        String driver = read("src/build/java/io/github/ibcmanager/build/BuildProject.java");
        Assertions.contains(pack, "windows-release-zip",
                "package-windows.bat must invoke Windows release ZIP assembly");
        Assertions.contains(pack,
                "set \"WINDOWS_RELEASE_ZIP=dist\\IBC_Manager_1.0.8_Release_windows.zip\"",
                "Windows release ZIP filename must equal the normal release name plus _windows");
        Assertions.contains(driver,
                "IBC_Manager_\" + releaseVersion + \"_Release_windows.zip",
                "build driver must derive the Windows release filename from the application version");
        Assertions.contains(driver, "copyTree(normalReleaseRoot, stageRoot)",
                "Windows release ZIP must retain the normal release payload");
        Assertions.contains(driver, "IBC Manager-\" + releaseVersion + \".exe",
                "Windows release ZIP must require the matching versioned EXE installer");
        Assertions.contains(driver, "verifyWindowsReleaseArchiveAssembly",
                "build-driver self-test must exercise Windows release ZIP assembly");
    }

    private void versionsAgree() throws Exception {
        String version = read("src/main/java/io/github/ibcmanager/app/Version.java");
        String build = read("build.xml");
        String run = read("scripts/run.bat");
        String pack = read("scripts/package-windows.bat");
        Assertions.contains(version, "VERSION = \"1.0.8\"", "application version must be 1.0.8");
        Assertions.contains(build, "name=\"app.version\" value=\"1.0.8\"",
                "optional Ant release version must be 1.0.8");
        Assertions.contains(run, "IBC-Manager-1.0.8.jar", "run JAR must be version 1.0.8");
        Assertions.contains(pack, "--app-version 1.0.8", "Windows package version must be 1.0.8");
        Assertions.contains(pack, "IBC-Manager-1.0.8.jar", "packaged JAR must be version 1.0.8");
        Assertions.contains(pack, "IBC_Manager_1.0.8_Release_windows.zip",
                "Windows release ZIP must be version 1.0.8");
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

    private static boolean matches(char opening, char closing) {
        return opening == '(' && closing == ')'
                || opening == '[' && closing == ']'
                || opening == '{' && closing == '}';
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            Assertions.fail("could not locate source section between " + start + " and " + end);
        }
        return source.substring(from, to);
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int index = 0; index < 8 && current != null; index++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("src/main/java"))
                    && Files.isRegularFile(current.resolve("build.xml"))) {
                return current;
            }
        }
        throw new IllegalStateException("Could not locate IBC Manager source root");
    }

    private enum LexState {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
