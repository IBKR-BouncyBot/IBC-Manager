package io.github.ibcmanager.build;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Dependency-free build driver for IBC Manager.
 *
 * <p>The file is intentionally executable through Java's source-file launcher:
 * {@code java src/build/java/io/github/ibcmanager/build/BuildProject.java clean test jar smoke dist}.
 * This keeps the Windows build path dependent only on the verified JDK and avoids
 * a second downloaded build tool.</p>
 */
public final class BuildProject {
    private static final long ARCHIVE_TIMESTAMP_MILLIS = Instant.parse("1980-01-01T00:00:00Z").toEpochMilli();
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "\\bVERSION\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern BUILD_XML_VERSION_PATTERN = Pattern.compile(
            "name=\"app.version\"\\s+value=\"([^\"]+)\"");
    private static final List<String> RELEASE_ROOT_FILES = List.of(
            "README.md", "LICENSE.txt", "NOTICE.txt", "CHANGELOG.md", "TEST_REPORT.md", "CODE_REVIEW.md");
    private static final List<String> RELEASE_SCRIPT_FILES = List.of(
            "run.bat", "bootstrap.bat", "ensure-prerequisites.ps1", "pause-after-run.bat");

    private final Path root;
    private final Path buildDirectory;
    private final Path classesDirectory;
    private final Path testClassesDirectory;
    private final Path distributionDirectory;
    private final String version;
    private final Path jarFile;
    private final Set<String> completed = new HashSet<>();

    private BuildProject(Path root) throws IOException {
        this.root = root;
        this.buildDirectory = root.resolve("build");
        this.classesDirectory = buildDirectory.resolve("classes");
        this.testClassesDirectory = buildDirectory.resolve("test-classes");
        this.distributionDirectory = root.resolve("dist");
        this.version = readApplicationVersion(root);
        this.jarFile = distributionDirectory.resolve("IBC-Manager-" + version + ".jar");
    }

    public static void main(String[] arguments) {
        try {
            Path root = locateProjectRoot(Path.of("").toAbsolutePath().normalize());
            BuildProject build = new BuildProject(root);
            if (arguments.length == 0) {
                build.printUsage();
                System.exit(2);
            }
            if (requiresBuildLock(arguments)) {
                try (BuildLock buildLock = BuildLock.acquire(root, Duration.ofMinutes(15))) {
                    buildLock.ensureHeld();
                    build.executeAll(arguments);
                }
            } else {
                build.executeAll(arguments);
            }
        } catch (Throwable failure) {
            System.err.println("[IBC Manager build] FAILED: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void executeAll(String[] arguments) throws Exception {
        for (String target : arguments) {
            execute(target.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean requiresBuildLock(String[] arguments) {
        for (String argument : arguments) {
            String target = argument.toLowerCase(Locale.ROOT);
            if (!target.equals("self-test") && !target.equals("version")) {
                return true;
            }
        }
        return false;
    }

    private void printUsage() {
        System.out.println("IBC Manager build driver " + version);
        System.out.println("Targets: clean, compile, compile-tests, test, jar, smoke, gui-smoke, gui-screenshot, dist, "
                + "windows-release-zip, self-test, version");
    }

    private void execute(String target) throws Exception {
        if (!completed.add(target)) {
            return;
        }
        switch (target) {
            case "clean" -> clean();
            case "compile" -> compileMain();
            case "compile-tests" -> {
                execute("compile");
                compileTests();
            }
            case "test" -> {
                execute("compile-tests");
                runTests();
            }
            case "jar" -> {
                execute("compile");
                createJar();
            }
            case "smoke" -> {
                execute("jar");
                runSmokeTests();
            }
            case "gui-screenshot" -> {
                execute("compile-tests");
                runJava(List.of("-Djava.awt.headless=false", "-classpath", testClasspath(),
                        "io.github.ibcmanager.tests.GuiSmokeRunner", "--screenshot", buildDirectory.resolve("GUI.png").toString()));
            }
            case "gui-smoke" -> {
                execute("compile-tests");
                runGuiSmoke();
            }
            case "dist" -> {
                execute("test");
                execute("jar");
                execute("smoke");
                createDistributions();
            }
            case "windows-release-zip" -> createWindowsReleaseZip();
            case "self-test" -> selfTest();
            case "version" -> System.out.println(version);
            default -> throw new IllegalArgumentException("Unknown build target: " + target);
        }
    }

    private void clean() throws IOException {
        deleteTree(buildDirectory);
        deleteTree(distributionDirectory);
        System.out.println("[IBC Manager build] Cleaned build and dist directories.");
    }

    private void compileMain() throws IOException {
        deleteTree(classesDirectory);
        deleteTree(testClassesDirectory);
        Files.createDirectories(classesDirectory);
        compileEngine();
        List<Path> sources = javaSources(root.resolve("src/main/java"));
        compile(sources, classesDirectory, List.of());
        copyTree(root.resolve("src/main/resources"), classesDirectory);
        Path legal = classesDirectory.resolve("legal");
        Files.createDirectories(legal);
        Files.copy(root.resolve("LICENSE.txt"), legal.resolve("LICENSE.txt"));
        Files.copy(root.resolve("engine/LICENSE.txt"), legal.resolve("IBC-LICENSE.txt"));
        Files.copy(root.resolve("NOTICE.txt"), legal.resolve("NOTICE.txt"));
        Files.copy(root.resolve("engine/PROVENANCE.json"), legal.resolve("ENGINE_PROVENANCE.json"));
        System.out.println("[IBC Manager build] Compiled " + sources.size() + " production Java files.");
    }

    /** Build the maintained engine from source, then embed its exact payload in the application. */
    private void compileEngine() throws IOException {
        Path engineClasses = buildDirectory.resolve("engine-classes");
        deleteTree(engineClasses);
        Files.createDirectories(engineClasses);
        List<Path> sources = javaSources(root.resolve("engine/src/main/java"));
        compile(sources, engineClasses, List.of());
        Path payload = buildDirectory.resolve("engine-payload");
        deleteTree(payload);
        copyTree(root.resolve("engine/resources"), payload);
        Path engineJar = payload.resolve("IBC.jar");
        try (OutputStream out = Files.newOutputStream(engineJar); JarOutputStream jar = new JarOutputStream(out)) {
            for (Path file : regularFiles(engineClasses)) {
                JarEntry entry = new JarEntry(zipName(engineClasses.relativize(file)));
                entry.setTime(ARCHIVE_TIMESTAMP_MILLIS);
                jar.putNextEntry(entry);
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        StringBuilder checksums = new StringBuilder();
        for (Path file : regularFiles(payload)) {
            checksums.append(HexFormat.of().formatHex(sha256(Files.readAllBytes(file))))
                    .append("  ").append(zipName(payload.relativize(file))).append('\n');
        }
        Path resources = classesDirectory.resolve("integrated-engine");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("SHA256SUMS.txt"), checksums.toString(), StandardCharsets.UTF_8);
        writeZip(payload, resources.resolve("payload.zip"), ignored -> true, "");
        System.out.println("[IBC Manager build] Compiled " + sources.size()
                + " maintained IBC engine Java files and embedded the verified payload.");
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 unavailable", ex); }
    }

    private void compileTests() throws IOException {
        deleteTree(testClassesDirectory);
        Files.createDirectories(testClassesDirectory);
        List<Path> sources = new ArrayList<>(javaSources(root.resolve("src/test/java")));
        Path engineTests = root.resolve("engine/src/test/java");
        if (Files.isDirectory(engineTests)) sources.addAll(javaSources(engineTests));
        compile(sources, testClassesDirectory, List.of("-classpath",
                classesDirectory + System.getProperty("path.separator") + buildDirectory.resolve("engine-classes")));
        System.out.println("[IBC Manager build] Compiled " + sources.size() + " test Java files.");
    }

    private void compile(List<Path> sources, Path destination, List<String> additionalOptions) throws IOException {
        if (sources.isEmpty()) {
            throw new IOException("No Java source files found for " + destination);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("The selected Java installation has no system compiler. Use a full JDK 17 or newer.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(sources);
            List<String> options = new ArrayList<>(List.of(
                    "--release", "17",
                    "-encoding", "UTF-8",
                    "-g",
                    "-Xlint:all",
                    "-Werror",
                    "-d", destination.toString()));
            options.addAll(additionalOptions);
            Boolean success = compiler.getTask(null, manager, diagnostics, options, null, units).call();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                String source = diagnostic.getSource() == null
                        ? "<unknown>"
                        : Path.of(diagnostic.getSource().toUri()).toString();
                System.err.printf(Locale.ROOT, "%s:%d:%d: %s: %s%n",
                        source, diagnostic.getLineNumber(), diagnostic.getColumnNumber(),
                        diagnostic.getKind(), diagnostic.getMessage(Locale.ROOT));
            }
            if (!Boolean.TRUE.equals(success)) {
                throw new IOException("Java compilation failed.");
            }
        }
    }

    private void runTests() throws IOException, InterruptedException {
        runJava(List.of(
                "-Djava.awt.headless=true",
                "-classpath", testClasspath(),
                "io.github.ibcmanager.tests.TestRunner"));
    }

    private void runGuiSmoke() throws IOException, InterruptedException {
        runJava(List.of(
                "-Djava.awt.headless=false",
                "-classpath", testClasspath(),
                "io.github.ibcmanager.tests.GuiSmokeRunner"));
        runJava(List.of("-Djava.awt.headless=false", "-classpath", testClasspath(),
                "ibcalpha.ibc.EngineGuiSmoke"));
        runJava(List.of("-Djava.awt.headless=false", "-classpath", testClasspath(),
                "ibcalpha.ibc.SecondFactorRetryGuiSmoke"));
    }

    private String testClasspath() {
        return String.join(System.getProperty("path.separator"),
                classesDirectory.toString(), testClassesDirectory.toString(),
                buildDirectory.resolve("engine-classes").toString(),
                root.resolve("src/main/resources").toString());
    }

    private void createJar() throws IOException {
        Files.createDirectories(distributionDirectory);
        Files.deleteIfExists(jarFile);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, "io.github.ibcmanager.app.IbcManagerApp");
        attributes.putValue("Implementation-Title", "IBC Manager");
        attributes.putValue("Implementation-Version", version);
        attributes.putValue("Automatic-Module-Name", "io.github.ibcmanager");

        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(jarFile));
                JarOutputStream jar = new JarOutputStream(output)) {
            addDirectoryEntry(jar, "META-INF/");
            JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
            manifestEntry.setTime(ARCHIVE_TIMESTAMP_MILLIS);
            jar.putNextEntry(manifestEntry);
            manifest.write(jar);
            jar.closeEntry();

            for (Path file : regularFiles(classesDirectory)) {
                String entryName = zipName(classesDirectory.relativize(file));
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entryName)) {
                    continue;
                }
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(ARCHIVE_TIMESTAMP_MILLIS);
                jar.putNextEntry(entry);
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        System.out.println("[IBC Manager build] Created " + root.relativize(jarFile));
    }

    private static void addDirectoryEntry(JarOutputStream jar, String name) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(ARCHIVE_TIMESTAMP_MILLIS);
        jar.putNextEntry(entry);
        jar.closeEntry();
    }

    private void runSmokeTests() throws IOException, InterruptedException {
        runJava(List.of("-jar", jarFile.toString(), "--version"));
        Path smokeData = buildDirectory.resolve("smoke-data");
        deleteTree(smokeData);
        runJava(List.of(
                "-Djava.awt.headless=true",
                "-jar", jarFile.toString(),
                "--headless-smoke", "--data-dir", smokeData.toString()));
    }

    private void createDistributions() throws IOException {
        Path releaseBase = buildDirectory.resolve("release");
        Path releaseRoot = releaseBase.resolve("IBC_Manager_" + version);
        deleteTree(releaseBase);
        Files.createDirectories(releaseRoot);
        Files.copy(jarFile, releaseRoot.resolve(jarFile.getFileName()));
        for (String file : RELEASE_ROOT_FILES) {
            Files.copy(root.resolve(file), releaseRoot.resolve(file));
        }
        copyTree(root.resolve("docs"), releaseRoot.resolve("docs"));
        copyTree(root.resolve("images"), releaseRoot.resolve("images"));
        Files.copy(root.resolve("run.bat"), releaseRoot.resolve("run.bat"));
        Path releaseScripts = releaseRoot.resolve("scripts");
        Files.createDirectories(releaseScripts);
        for (String file : RELEASE_SCRIPT_FILES) {
            Files.copy(root.resolve("scripts").resolve(file), releaseScripts.resolve(file));
        }
        copyTree(root.resolve("third_party"), releaseRoot.resolve("third_party"));

        Files.createDirectories(distributionDirectory);
        Path releaseZip = distributionDirectory.resolve("IBC_Manager_" + version + "_Release.zip");
        Path sourceZip = distributionDirectory.resolve("IBC_Manager_" + version + "_Source.zip");
        writeZip(releaseBase, releaseZip, ignored -> true, "");
        writeZip(root, sourceZip, this::includeInSourceArchive,
                "IBC_Manager_" + version + "_Source/");
        System.out.println("[IBC Manager build] Created " + root.relativize(releaseZip));
        System.out.println("[IBC Manager build] Created " + root.relativize(sourceZip));
    }

    private void createWindowsReleaseZip() throws IOException {
        Path result = createWindowsReleaseArchive(buildDirectory, distributionDirectory, version);
        System.out.println("[IBC Manager build] Created " + root.relativize(result));
    }

    private static Path createWindowsReleaseArchive(
            Path projectBuildDirectory,
            Path projectDistributionDirectory,
            String releaseVersion) throws IOException {
        Path windowsDirectory = projectDistributionDirectory.resolve("windows");
        Path appImageDirectory = windowsDirectory.resolve("IBC Manager");
        Path appImageLauncher = appImageDirectory.resolve("IBC Manager.exe");
        Path appImageJar = appImageDirectory.resolve("app/IBC-Manager-" + releaseVersion + ".jar");
        Path appImageJava = appImageDirectory.resolve("runtime/bin/java.exe");
        requireRegularNonemptyFile(appImageLauncher,
                "The jpackage application image launcher is missing or empty: " + appImageLauncher);
        requireRegularNonemptyFile(appImageJar,
                "The portable application payload is missing or empty: " + appImageJar);
        requireRegularNonemptyFile(appImageJava,
                "The portable Java process launcher is missing or empty: " + appImageJava
                        + ". Rebuild the app image without jlink --strip-native-commands.");

        Path installer = findSingleWindowsInstaller(windowsDirectory, releaseVersion);
        Path stageBase = projectBuildDirectory.resolve("windows-release");
        deleteTree(stageBase);
        Files.createDirectories(stageBase);
        copyTree(appImageDirectory, stageBase.resolve("IBC Manager"));
        Files.copy(installer, stageBase.resolve(installer.getFileName()));

        Files.createDirectories(projectDistributionDirectory);
        Path destination = projectDistributionDirectory.resolve(
                "IBC_Manager_" + releaseVersion + "_Release_windows.zip");
        Path temporary = Files.createTempFile(
                projectDistributionDirectory,
                ".IBC_Manager_" + releaseVersion + "_Release_windows-",
                ".zip.tmp");
        try {
            writeZip(stageBase, temporary, ignored -> true, "");
            validateWindowsReleaseArchive(temporary, installer.getFileName().toString(), releaseVersion);
            replaceFile(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
        requireRegularNonemptyFile(destination, "The Windows release ZIP was not activated: " + destination);
        return destination;
    }

    private static Path findSingleWindowsInstaller(Path windowsDirectory, String releaseVersion) throws IOException {
        if (!Files.isDirectory(windowsDirectory)) {
            throw new IOException("The jpackage output directory does not exist: " + windowsDirectory);
        }
        List<Path> installers;
        try (Stream<Path> stream = Files.list(windowsDirectory)) {
            installers = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
        if (installers.size() != 1) {
            throw new IOException("Expected exactly one jpackage EXE installer directly under "
                    + windowsDirectory + ", but found " + installers.size() + ".");
        }
        Path installer = installers.get(0);
        requireRegularNonemptyFile(installer, "The jpackage EXE installer is empty: " + installer);
        String expectedName = "IBC Manager-" + releaseVersion + ".exe";
        if (!installer.getFileName().toString().equals(expectedName)) {
            throw new IOException("Expected the jpackage EXE installer to be named " + expectedName
                    + ", but found " + installer.getFileName() + ".");
        }
        return installer;
    }

    private static void validateWindowsReleaseArchive(
            Path archive,
            String installerName,
            String releaseVersion) throws IOException {
        requireRegularNonemptyFile(archive, "The Windows release ZIP was not created: " + archive);
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            require(zip.getEntry(installerName) != null,
                    "Windows release ZIP does not contain its EXE installer at the archive root.");
            requireNonemptyZipEntry(zip, "IBC Manager/IBC Manager.exe",
                    "Windows release ZIP does not contain a nonempty portable application launcher.");
            requireNonemptyZipEntry(zip,
                    "IBC Manager/app/IBC-Manager-" + releaseVersion + ".jar",
                    "Windows release ZIP does not contain the versioned portable application payload.");
            requireNonemptyZipEntry(zip, "IBC Manager/runtime/bin/java.exe",
                    "Windows release ZIP does not contain the Java launcher required by the process relay.");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                require(name.equals(installerName) || name.startsWith("IBC Manager/"),
                        "Windows release ZIP contains an unexpected file: " + name);
            }
            require(zip.getEntry("README.md") == null,
                    "Windows release ZIP must not duplicate normal release documentation.");
            require(zip.getEntry("run.bat") == null,
                    "Windows release ZIP must not duplicate the normal JAR launcher.");
            require(zip.getEntry("SHA256SUMS.txt") == null,
                    "Windows release ZIP must contain only the installer and portable application folder.");
        }
    }


    private static void requireNonemptyZipEntry(ZipFile zip, String name, String message)
            throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException(message);
        try (InputStream input = zip.getInputStream(entry)) {
            if (input.read() < 0) throw new IOException(message);
        }
    }

    private static void requireRegularNonemptyFile(Path path, String message) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) <= 0) {
            throw new IOException(message);
        }
    }

    private static void replaceFile(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean includeInSourceArchive(Path relative) {
        String name = zipName(relative);
        if (name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("build") || lower.startsWith("build/")
                || lower.equals("dist") || lower.startsWith("dist/")
                || lower.equals(".git") || lower.startsWith(".git/")
                || lower.equals(".idea") || lower.startsWith(".idea/")
                || lower.equals(".vscode") || lower.startsWith(".vscode/")) {
            return false;
        }
        return !lower.endsWith(".log") && !lower.endsWith(".tmp") && !lower.endsWith(".class");
    }

    private static void writeZip(Path base, Path destination, Predicate<Path> include, String prefix)
            throws IOException {
        Files.deleteIfExists(destination);
        List<Path> files = regularFiles(base).stream()
                .filter(file -> include.test(base.relativize(file)))
                .toList();
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination));
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                String name = prefix + zipName(base.relativize(file));
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(ARCHIVE_TIMESTAMP_MILLIS);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void runJava(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .inheritIO()
                .start();
        int result = process.waitFor();
        if (result != 0) {
            throw new IOException("Java subprocess failed with exit code " + result + ": " + String.join(" ", arguments));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path javaExecutable() throws IOException {
        String executable = isWindows() ? "java.exe" : "java";
        Path result = Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
        if (!Files.isRegularFile(result)) {
            throw new IOException("Could not locate the selected Java executable: " + result);
        }
        return result;
    }

    private void selfTest() throws IOException, InterruptedException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Build-driver self-test requires a full JDK.");
        }
        String buildXmlVersion = readBuildXmlVersion(root);
        require(version.equals(buildXmlVersion),
                "Application version and build.xml version differ: " + version + " versus " + buildXmlVersion);
        require(includeInSourceArchive(Path.of("src/main/java/App.java")), "production source must be archived");
        require(includeInSourceArchive(Path.of("src/build/java/BuildProject.java")), "build driver must be archived");
        for (String excluded : List.of(
                "build/classes/App.class", "dist/app.jar", ".git/config", ".idea/workspace.xml",
                ".vscode/settings.json", "validation.log", "scratch.tmp", "src/App.class")) {
            require(!includeInSourceArchive(Path.of(excluded)), "source exclusion failed for " + excluded);
        }
        require("a/b/c.txt".equals(zipName(Path.of("a", "b", "c.txt"))), "ZIP path normalization failed");
        require(Files.isRegularFile(root.resolve("scripts/ensure-prerequisites.ps1")),
                "runtime prerequisite bootstrap is missing");
        requireRegularNonemptyFile(root.resolve("images/GUI.png"),
                "README screenshot is missing or empty: images/GUI.png");
        require(Files.readString(root.resolve("README.md"), StandardCharsets.UTF_8)
                        .contains("![IBC Manager main window](images/GUI.png)"),
                "README must display images/GUI.png near its title.");
        Path compileOutput = Files.createTempDirectory("ibc-manager-build-driver-self-test-");
        Path lockRoot = Files.createTempDirectory("ibc-manager-build-lock-self-test-");
        Path windowsReleaseRoot = Files.createTempDirectory("ibc-manager-windows-release-self-test-");
        try {
            compile(List.of(root.resolve("src/build/java/io/github/ibcmanager/build/BuildProject.java")),
                    compileOutput, List.of());
            verifyBuildLockSerialization(lockRoot);
            verifyWindowsReleaseArchiveAssembly(windowsReleaseRoot);
        } finally {
            deleteTree(compileOutput);
            try {
                Files.deleteIfExists(BuildLock.buildLockPath(lockRoot));
            } finally {
                deleteTree(lockRoot);
            }
            deleteTree(windowsReleaseRoot);
        }
        System.out.println("[IBC Manager build] Build-driver self-test passed for version " + version + ".");
    }

    private static void verifyWindowsReleaseArchiveAssembly(Path testRoot) throws IOException {
        String testVersion = "9.8.7";
        Path projectRoot = testRoot.resolve("project");
        Path projectDist = projectRoot.resolve("dist");
        Path appImageLauncher = projectDist.resolve("windows/IBC Manager/IBC Manager.exe");
        Files.createDirectories(appImageLauncher.getParent());
        Files.write(appImageLauncher, new byte[] {0x4d, 0x5a, 0x01});
        Path portableJar = projectDist.resolve("windows/IBC Manager/app/IBC-Manager-" + testVersion + ".jar");
        Files.createDirectories(portableJar.getParent());
        Files.write(portableJar, new byte[] {0x50, 0x4b, 0x03, 0x04});
        Path portableRuntime = projectDist.resolve("windows/IBC Manager/runtime/bin/java.exe");
        Files.createDirectories(portableRuntime.getParent());
        Files.write(portableRuntime, new byte[] {0x4d, 0x5a, 0x05});
        Path installer = projectDist.resolve("windows/IBC Manager-" + testVersion + ".exe");
        Files.write(installer, new byte[] {0x4d, 0x5a, 0x02, 0x03});

        Path archive = createWindowsReleaseArchive(projectRoot.resolve("build"), projectDist, testVersion);
        require(archive.equals(projectDist.resolve("IBC_Manager_9.8.7_Release_windows.zip")),
                "Windows release ZIP filename is incorrect: " + archive);
        require(Files.isRegularFile(archive), "Windows release ZIP self-test did not create an archive.");

        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            require(zip.getEntry("IBC Manager-9.8.7.exe") != null,
                    "Windows release ZIP self-test lost its installer.");
            require(zip.getEntry("IBC Manager/IBC Manager.exe") != null,
                    "Windows release ZIP self-test lost the portable launcher.");
            require(zip.getEntry("IBC Manager/app/IBC-Manager-9.8.7.jar") != null,
                    "Windows release ZIP self-test lost the portable application payload.");
            require(zip.getEntry("IBC Manager/runtime/bin/java.exe") != null,
                    "Windows release ZIP self-test lost the Java process launcher.");
            require(zip.getEntry("README.md") == null,
                    "Windows release ZIP self-test included normal release documentation.");
            require(zip.getEntry("run.bat") == null,
                    "Windows release ZIP self-test included the normal JAR launcher.");
        }

        Files.delete(portableRuntime);
        boolean missingRuntimeRejected = false;
        try {
            createWindowsReleaseArchive(projectRoot.resolve("build"), projectDist, testVersion);
        } catch (IOException expected) {
            missingRuntimeRejected = expected.getMessage() != null
                    && expected.getMessage().contains("portable Java process launcher");
        }
        require(missingRuntimeRejected,
                "Windows release ZIP assembly must reject an app image without runtime/bin/java.exe.");
        Files.write(portableRuntime, new byte[] {0x4d, 0x5a, 0x05});

        Path secondInstaller = projectDist.resolve("windows/unexpected.exe");
        Files.write(secondInstaller, new byte[] {0x4d, 0x5a, 0x04});
        boolean rejected = false;
        try {
            createWindowsReleaseArchive(projectRoot.resolve("build"), projectDist, testVersion);
        } catch (IOException expected) {
            rejected = expected.getMessage() != null
                    && expected.getMessage().contains("exactly one jpackage EXE installer");
        }
        require(rejected, "Windows release ZIP assembly must reject ambiguous EXE installer output.");
    }

    private static void verifyBuildLockSerialization(Path lockRoot) throws IOException, InterruptedException {
        AtomicBoolean secondAcquired = new AtomicBoolean();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        BuildLock first = BuildLock.acquire(lockRoot, Duration.ofSeconds(2), false);
        Thread waiter = new Thread(() -> {
            secondStarted.countDown();
            try (BuildLock second = BuildLock.acquire(lockRoot, Duration.ofSeconds(3), false)) {
                second.ensureHeld();
                secondAcquired.set(true);
            } catch (Throwable failure) {
                secondFailure.set(failure);
            }
        }, "ibc-manager-build-lock-self-test");
        waiter.setDaemon(true);
        try {
            waiter.start();
            require(secondStarted.await(2, TimeUnit.SECONDS), "the waiting build-lock thread did not start");
            Thread.sleep(150);
            require(!secondAcquired.get(), "a second build lock was acquired before the first was released");
        } finally {
            first.close();
        }
        waiter.join(4_000);
        require(!waiter.isAlive(), "a waiting build lock did not complete after release");
        require(secondFailure.get() == null, "a waiting build lock failed: " + secondFailure.get());
        require(secondAcquired.get(), "a waiting build lock was not acquired after release");
    }

    private static final class BuildLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private BuildLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static BuildLock acquire(Path root, Duration timeout) throws IOException, InterruptedException {
            return acquire(root, timeout, true);
        }

        private static BuildLock acquire(Path root, Duration timeout, boolean reportWait)
                throws IOException, InterruptedException {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Build-lock timeout must be positive.");
            }
            Path lockFile = buildLockPath(root);
            Files.createDirectories(lockFile.getParent());
            FileChannel channel = FileChannel.open(lockFile, CREATE, WRITE);
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean announced = false;
            try {
                while (true) {
                    FileLock lock = null;
                    try {
                        lock = channel.tryLock();
                    } catch (OverlappingFileLockException ignored) {
                        // Another build in this JVM owns the same project lock.
                    }
                    if (lock != null) {
                        if (announced && reportWait) {
                            System.out.println("[IBC Manager build] Acquired the project build lock.");
                        }
                        return new BuildLock(channel, lock);
                    }
                    if (!announced) {
                        if (reportWait) {
                            System.out.println("[IBC Manager build] Another build is active for this source tree; waiting...");
                        }
                        announced = true;
                    }
                    if (System.nanoTime() >= deadline) {
                        throw new IOException("Timed out waiting for another build of this source tree to finish.");
                    }
                    Thread.sleep(200);
                }
            } catch (IOException | InterruptedException | RuntimeException | Error failure) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private static Path buildLockPath(Path root) throws IOException {
            Path canonicalRoot = root.toRealPath();
            String identity = isWindows()
                    ? canonicalRoot.toString().toLowerCase(Locale.ROOT)
                    : canonicalRoot.toString();
            byte[] digest;
            try {
                digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IOException("SHA-256 is unavailable for the build lock.", impossible);
            }
            String suffix = HexFormat.of().formatHex(digest, 0, 16);
            return Path.of(System.getProperty("java.io.tmpdir"), "ibc-manager-build-" + suffix + ".lock");
        }

        void ensureHeld() {
            if (!lock.isValid()) {
                throw new IllegalStateException("The project build lock is not held.");
            }
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static List<Path> javaSources(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static List<Path> regularFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> zipName(directory.relativize(path))))
                    .toList();
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String zipName(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path locateProjectRoot(Path start) throws IOException {
        Path current = start;
        for (int index = 0; index < 10 && current != null; index++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("src/main/java"))
                    && Files.isRegularFile(current.resolve("build.xml"))
                    && Files.isRegularFile(current.resolve("src/main/java/io/github/ibcmanager/app/Version.java"))) {
                return current;
            }
        }
        throw new IOException("Could not locate the IBC Manager project root from " + start);
    }

    private static String readApplicationVersion(Path root) throws IOException {
        String source = Files.readString(
                root.resolve("src/main/java/io/github/ibcmanager/app/Version.java"), StandardCharsets.UTF_8);
        Matcher matcher = VERSION_PATTERN.matcher(source);
        if (!matcher.find()) {
            throw new IOException("Could not read the application version from Version.java");
        }
        return matcher.group(1);
    }

    private static String readBuildXmlVersion(Path root) throws IOException {
        String source = Files.readString(root.resolve("build.xml"), StandardCharsets.UTF_8);
        Matcher matcher = BUILD_XML_VERSION_PATTERN.matcher(source);
        if (!matcher.find()) {
            throw new IOException("Could not read app.version from build.xml");
        }
        return matcher.group(1);
    }
}
