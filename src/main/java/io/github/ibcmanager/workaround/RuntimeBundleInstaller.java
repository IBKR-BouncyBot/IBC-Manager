package io.github.ibcmanager.workaround;

import io.github.ibcmanager.security.CommandExecutor;
import io.github.ibcmanager.security.DefaultCommandExecutor;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Stage runtimes before edits; record unavailable publisher checksums or executable probes honestly. */
public final class RuntimeBundleInstaller {
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private final RuntimeDownloads downloads;
    private final CommandExecutor commands;
    public RuntimeBundleInstaller() { this(RuntimeDownloads.official(), new DefaultCommandExecutor()); }
    public RuntimeBundleInstaller(RuntimeDownloads downloads, CommandExecutor commands) {
        this.downloads = downloads; this.commands = commands;
    }
    public record Installed(String jbrVersion, String javafxVersion) { }
    public Installed install(Path stage, Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        if (io.github.ibcmanager.app.OperatingSystem.current() == io.github.ibcmanager.app.OperatingSystem.WINDOWS
                && !List.of("amd64", "x86_64").contains(System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT))) {
            throw new IOException("The managed JBR/JavaFX workaround currently requires Windows x64");
        }
        requireNotCancelled(cancelled);
        progress.accept("Preparing pinned JBR 25.0.4-b508.27 and JavaFX 25.0.4 (Windows x64)...");
        RuntimeCatalog.Selection selected = new RuntimeCatalog(downloads).resolve();
        progress.accept("Downloading and verifying JBR " + selected.jbrVersion() + "...");
        StringBuilder provenance = new StringBuilder("Publisher artifacts selected on explicit user request.\n")
                .append("Pinned compatibility versions: JBR ").append(RuntimeCatalog.JBR_VERSION)
                .append("; JavaFX ").append(RuntimeCatalog.JAVAFX_VERSION).append("\n");
        Path zip = stage.resolve("runtime.zip");
        Verification jbrVerification = checksum(URI.create(selected.jbrZip() + ".checksum"), "SHA-512", cancelled);
        int uncheckedArtifacts = jbrVerification.published().isEmpty() ? 1 : 0;
        download(selected.jbrZip(), jbrVerification, zip, progress, cancelled);
        provenance.append("JBR ").append(selected.jbrVersion()).append("\n");
        recordProvenance(provenance, selected.jbrZip(), jbrVerification, zip);
        Path extracted = stage.resolve("extracted"); Files.createDirectory(extracted);
        extract(zip, extracted);
        Path javaHome;
        try (var stream = Files.walk(extracted, 4)) {
            List<Path> homes = stream.filter(p -> p.getFileName().toString().equals("java.exe")
                    && p.getParent().getFileName().toString().equals("bin")).toList();
            if (homes.size() != 1) throw new IOException("JBR archive must contain exactly one Java launcher");
            javaHome = homes.get(0).getParent().getParent();
        }
        Files.move(javaHome, stage.resolve("jbr"), StandardCopyOption.ATOMIC_MOVE);
        SecureFileOperations.deleteTree(extracted); Files.delete(zip);
        String release = Files.readString(stage.resolve("jbr/release"), StandardCharsets.UTF_8);
        if (!Pattern.compile("(?m)^JAVA_VERSION=\"25\\.0\\.4\"\r?$").matcher(release).find() || !release.contains("JetBrains")) {
            throw new IOException("Downloaded runtime is not the pinned JetBrains Java 25.0.4 build");
        }
        var buildFields = Pattern.compile("(?m)^(JAVA_RUNTIME_VERSION|IMPLEMENTOR_VERSION)=(.*)$").matcher(release);
        boolean foundBuildIdentity = false;
        while (buildFields.find()) {
            String expected = buildFields.group(1).equals("JAVA_RUNTIME_VERSION")
                    ? "\"25\\.0\\.4\\+1-b508\\.27\""
                    : "\"JBR-25\\.0\\.4\\+1-b?508\\.27(?:-nomod)?\"";
            if (!buildFields.group(2).matches(expected)) {
                throw new IOException("Downloaded runtime identity does not match pinned JBR " + RuntimeCatalog.JBR_VERSION
                        + ": " + buildFields.group(1));
            }
            foundBuildIdentity = true;
        }
        if (!foundBuildIdentity) {
            throw new IOException("Downloaded runtime has no build identity for pinned JBR " + RuntimeCatalog.JBR_VERSION);
        }
        requireWindowsX64(stage.resolve("jbr/bin/java.exe"));
        Path javafx = stage.resolve("javafx"); Files.createDirectory(javafx);
        for (String module : RuntimeCatalog.FX_MODULES) {
            progress.accept("Downloading and verifying JavaFX " + selected.javafxVersion() + " - " + module + "...");
            String base = RuntimeCatalog.artifactUrl(module, selected.javafxVersion());
            Path jar = javafx.resolve(RuntimeCatalog.moduleName(module) + ".jar");
            Verification verification = javafxChecksum(URI.create(base), progress, cancelled);
            if (verification.published().isEmpty()) uncheckedArtifacts++;
            download(URI.create(base), verification, jar, progress, cancelled);
            recordProvenance(provenance, URI.create(base), verification, jar);
            requireJavafxModule(jar, module);
        }
        boolean runtimeVerified = probeRuntime(stage.resolve("jbr/bin/java.exe"), javafx,
                provenance, progress, cancelled);
        requireNotCancelled(cancelled);
        if (uncheckedArtifacts > 0) {
            progress.accept((runtimeVerified ? "Runtime compatibility checks passed. " : "Runtime check unavailable; setup will continue. ")
                    + "Publisher checksum verification was SKIPPED for " + uncheckedArtifacts
                    + " artifact(s). See download-provenance.txt.");
        }
        Files.writeString(stage.resolve("download-provenance.txt"), provenance.toString(), StandardCharsets.UTF_8);
        return new Installed(selected.jbrVersion(), selected.javafxVersion());
    }
    /** Check actual module identity for both downloaded and already-owned payloads. */
    static void requireJavafxModule(Path jar, String module) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            if (archive.getEntry("module-info.class") == null) throw new IOException("JavaFX artifact has no module descriptor: " + module);
            // Inspect the downloaded descriptor rather than assuming a filename proves
            // that the module exists. This also rejects Maven's unclassified Empty JAR.
            try (var input = archive.getInputStream(archive.getEntry("module-info.class"))) {
                java.lang.module.ModuleDescriptor descriptor = java.lang.module.ModuleDescriptor.read(input);
                if (!descriptor.name().equals(RuntimeCatalog.moduleName(module))
                        || !RuntimeCatalog.JAVAFX_VERSION.equals(descriptor.rawVersion().orElse(""))) {
                    throw new IOException("Wrong JavaFX module identity/version: " + module + "; expected " + RuntimeCatalog.JAVAFX_VERSION);
                }
            } catch (java.lang.module.InvalidModuleDescriptorException ex) {
                throw new IOException("Invalid JavaFX module descriptor: " + module, ex);
            }
            // Native binaries remain in the vendor's module JAR and are loaded by JavaFX.
            String nativeName = switch (module) { case "graphics" -> "glass.dll"; case "media" -> "jfxmedia.dll"; case "web" -> "jfxwebkit.dll"; default -> null; };
            if (nativeName != null && archive.stream().noneMatch(e -> e.getName().equals(nativeName) || e.getName().endsWith("/" + nativeName))) {
                throw new IOException("JavaFX Windows native library is missing: " + nativeName);
            }
        }
    }

    /** The inventory subprocess is a best-effort setup check, not a required application launch. */
    private boolean probeRuntime(Path executable, Path javafx, StringBuilder provenance,
            Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        requireNotCancelled(cancelled);
        progress.accept("Checking the downloaded Java runtime and JavaFX module resolution (optional)...");
        // Progress callbacks can cancel setup before the subprocess is created.
        requireNotCancelled(cancelled);
        io.github.ibcmanager.security.CommandResult result;
        try {
            result = commands.execute(List.of(executable.toString(), "--module-path", javafx.toString(),
                    "--add-modules", moduleList(), "--list-modules"), "", Duration.ofSeconds(30));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Runtime verification interrupted", ex);
        } catch (IOException | SecurityException ex) {
            requireNotCancelled(cancelled);
            return unavailableProbe("verification command could not be completed",
                    ex.getClass().getSimpleName() + ": " + diagnosticText(ex.getMessage()),
                    provenance, progress, cancelled);
        }
        requireNotCancelled(cancelled);
        String details = "stdout: " + diagnosticText(result.stdout())
                + "\nstderr: " + diagnosticText(result.stderr());
        // A returned JVM module-resolution failure is positive evidence, not an
        // unavailable check. Do not commit a runtime already known to be unusable.
        if (!result.timedOut() && result.exitCode() != 0) {
            Optional<String> failure = java.util.stream.Stream.concat(result.stdout().lines(), result.stderr().lines())
                    .map(io.github.ibcmanager.runtime.JavaBootstrapFailure::message)
                    .flatMap(Optional::stream).reduce((first, last) -> last);
            if (failure.isPresent()) {
                throw new IOException("Downloaded Java/JavaFX cannot start: " + failure.get());
            }
        }
        if (result.timedOut() || result.exitCode() != 0) {
            return unavailableProbe(result.timedOut() ? "verification command timed out"
                    : "verification command returned exit code " + result.exitCode(),
                    details, provenance, progress, cancelled);
        }
        String inventory = result.stdout() + "\n" + result.stderr();
        // A completed subprocess with no recognizable inventory is not proof that modules
        // are missing. Do not turn an unavailable result into a blocking setup error.
        if (!Pattern.compile("(?m)^(?:java\\.base|javafx\\.[a-z][a-z0-9]*)@[^\\s]+(?:[ \\t]+[^\\r\\n]+)?[ \\t]*$")
                .matcher(inventory).find()) {
            return unavailableProbe("verification command returned no usable module inventory",
                    details, provenance, progress, cancelled);
        }
        // A usable inventory that explicitly lacks required modules is different from
        // an unavailable check. Keep this proven compatibility failure blocking.
        for (String module : RuntimeCatalog.FX_MODULES) {
            if (!Pattern.compile("(?m)^" + Pattern.quote(RuntimeCatalog.moduleName(module))
                    + "@" + Pattern.quote(RuntimeCatalog.JAVAFX_VERSION) + "(?:[ \\t]+[^\\r\\n]+)?[ \\t]*$").matcher(inventory).find()) {
                throw new IOException("Pinned JavaFX " + RuntimeCatalog.JAVAFX_VERSION + " module could not be resolved: " + module);
            }
        }
        provenance.append("Runtime compatibility verification PASSED: all eight OpenJFX 25.0.4 modules resolved (including jdk.jsobject).\n");
        return true;
    }

    private static boolean unavailableProbe(String reason, String details, StringBuilder provenance,
            Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        String message = "WARNING: Runtime compatibility verification SKIPPED: " + reason
                + ". Setup will continue; runtime compatibility is not confirmed.";
        provenance.append(message).append("\n").append(details).append("\n");
        java.util.logging.Logger.getLogger(RuntimeBundleInstaller.class.getName()).warning(message + "\n" + details);
        progress.accept(message);
        requireNotCancelled(cancelled);
        return false;
    }

    private static String diagnosticText(String text) {
        if (text == null || text.isBlank()) return "(no output)";
        // Omitting the whole oversized field avoids cutting quoted secrets before redaction.
        if (text.length() > 4096) return "(output omitted: exceeds 4096 characters)";
        return io.github.ibcmanager.security.SecretRedactor.redact(text)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?").strip();
    }

    private record Checksum(URI uri, String algorithm, String expected) { }
    private record Verification(Optional<Checksum> published, List<String> unavailable) {
        Verification { unavailable = List.copyOf(unavailable); }
    }

    private Verification javafxChecksum(URI source, Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        Verification sha256 = checksum(URI.create(source + ".sha256"), "SHA-256", cancelled);
        if (sha256.published().isPresent()) return sha256;
        requireNotCancelled(cancelled);
        progress.accept("JavaFX SHA-256 checksum unavailable; trying Maven Central's SHA-1 checksum over HTTPS...");
        Verification sha1 = checksum(URI.create(source + ".sha1"), "SHA-1", cancelled);
        List<String> unavailable = new java.util.ArrayList<>(sha256.unavailable());
        unavailable.addAll(sha1.unavailable());
        return new Verification(sha1.published(), unavailable);
    }

    private Verification checksum(URI uri, String algorithm, BooleanSupplier cancelled) throws IOException {
        requireNotCancelled(cancelled);
        String text;
        try {
            text = downloads.text(uri, 8192);
        } catch (RuntimeDownloads.HttpStatusException ex) {
            requireNotCancelled(cancelled);
            if (ex.statusCode() < 400 || ex.statusCode() > 599
                    || !sameChecksum(uri, ex.requestedUri(), ex.responseUri())) throw ex;
            return new Verification(Optional.empty(), List.of(algorithm + ": HTTP " + ex.statusCode() + " at " + uri));
        } catch (RuntimeDownloads.TransportException ex) {
            requireNotCancelled(cancelled);
            if (!sameChecksum(uri, ex.requestedUri(), ex.responseUri())) throw ex;
            return new Verification(Optional.empty(), List.of(algorithm + ": checksum transfer unavailable at " + uri));
        }
        requireNotCancelled(cancelled);
        String expected = text.trim().split("\\s+")[0];
        int digits = switch (algorithm) { case "SHA-512" -> 128; case "SHA-256" -> 64; case "SHA-1" -> 40;
            default -> throw new IOException("Unsupported publisher checksum algorithm: " + algorithm); };
        // Parsing is intentionally outside the unavailable-download handler: a published
        // malformed hash or an eventual payload mismatch can never authorize a bypass.
        if (!expected.matches("[a-fA-F0-9]{" + digits + "}")) throw new IOException("Publisher did not provide a valid " + algorithm + " checksum");
        return new Verification(Optional.of(new Checksum(uri, algorithm, expected.toLowerCase(java.util.Locale.ROOT))), List.of());
    }

    private static boolean sameChecksum(URI expected, URI requested, URI response) throws IOException {
        if (!expected.equals(requested)) return false;
        RuntimeDownloads.requireAllowedRedirect(expected, response);
        if (expected.equals(response)) return true;
        String prefix = "/intellij-jbr/";
        if (!"cache-redirector.jetbrains.com".equalsIgnoreCase(expected.getHost())
                || !expected.getRawPath().startsWith(prefix) || expected.getRawQuery() != null
                || response.getRawQuery() != null) return false;
        String file = expected.getRawPath().substring(prefix.length());
        if (!file.matches("jbr-25(?:\\.[0-9]+){0,3}-windows-x64-b[0-9]+(?:\\.[0-9]+)*\\.zip\\.checksum")) return false;
        // Only an equivalent, approved CDN path counts as the same missing checksum.
        // An HTTP/network failure at a changed Maven or JBR artifact remains fatal.
        return switch (response.getHost().toLowerCase(java.util.Locale.ROOT)) {
            case "d2xrhe97vsfxuc.cloudfront.net", "intellij-jbr.s3.amazonaws.com" ->
                    response.getRawPath().equals("/" + file);
            case "cache-redirector.jetbrains.com" -> response.getRawPath().equals(prefix + file);
            case "download.jetbrains.com", "download-cdn.jetbrains.com" ->
                    List.of("/jbr/" + file, "/idea/jbr/" + file, prefix + file).contains(response.getRawPath());
            default -> false;
        };
    }

    private void download(URI source, Verification verification, Path output,
            Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        requireNotCancelled(cancelled);
        if (verification.published().isEmpty()) {
            String message = "WARNING: Publisher checksum verification SKIPPED for " + output.getFileName()
                    + ": checksum downloads unavailable. Using approved HTTPS without a publisher checksum; "
                    + "the local audit hash does not establish authenticity.";
            progress.accept(message);
            java.util.logging.Logger.getLogger(RuntimeBundleInstaller.class.getName()).warning(message);
        }
        // The progress callback may have cancelled the operation.
        requireNotCancelled(cancelled);
        // Never catch a required ZIP/JAR failure as if it were an optional checksum.
        downloads.file(source, output, MAX_DOWNLOAD, cancelled);
        requireNotCancelled(cancelled);
        if (verification.published().isPresent()) {
            Checksum checksum = verification.published().get();
            if (!hash(output, checksum.algorithm()).equalsIgnoreCase(checksum.expected())) {
                throw new IOException("Runtime download checksum mismatch; no changes applied: " + output.getFileName());
            }
        }
    }

    private static void recordProvenance(StringBuilder provenance, URI source,
            Verification verification, Path output) throws IOException {
        provenance.append(source).append("\n");
        for (String reason : verification.unavailable()) provenance.append("Unavailable checksum: ").append(reason).append("\n");
        if (verification.published().isPresent()) {
            Checksum checksum = verification.published().get();
            provenance.append("Checksum source ").append(checksum.uri())
                    .append("\nPublished ").append(checksum.algorithm()).append(" ").append(checksum.expected()).append("\n");
        } else {
            provenance.append("Publisher checksum verification SKIPPED: checksum downloads unavailable.\n")
                    .append("Downloaded over approved HTTPS; publisher authenticity is NOT verified by a checksum.\n");
        }
        provenance.append("Local SHA-256 ").append(hash(output, "SHA-256")).append("\n");
    }

    private static void requireNotCancelled(BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("Runtime setup cancelled");
    }
    public static String moduleList() { return RuntimeCatalog.FX_MODULES.stream().map(RuntimeCatalog::moduleName).collect(java.util.stream.Collectors.joining(",")); }
    public static String hash(Path p, String algorithm) throws IOException {
        try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) >= 0) digest.update(bytes, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IOException(algorithm + " is unavailable", ex); }
    }
    static void extract(Path archive, Path destination) throws IOException {
        long total = 0; int count = 0; Set<String> seen = new HashSet<>();
        java.util.Map<String, String> componentCases = new java.util.HashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 30000) throw new IOException("Runtime archive has too many entries");
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IOException("Unsafe runtime ZIP entry");
                String prefix = "";
                for (String part : name.split("/")) {
                    if (part.isEmpty()) throw new IOException("Empty runtime ZIP path component");
                    prefix += part + "/";
                    String lower = prefix.toLowerCase(java.util.Locale.ROOT);
                    String prior = componentCases.putIfAbsent(lower, prefix);
                    if (prior != null && !prior.equals(prefix)) throw new IOException("Case-ambiguous runtime ZIP directory");
                    if (part.equals("..") || part.equals(".") || part.endsWith(".") || part.endsWith(" ")
                            || part.matches("(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
                            || part.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)) throw new IOException("Ambiguous Windows runtime ZIP entry");
                }
                if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) throw new IOException("Duplicate runtime ZIP entry");
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination)) throw new IOException("Runtime ZIP entry escapes its directory");
                if (entry.isDirectory()) { SecureFileOperations.ensureDirectory(target); continue; }
                if (entry.getSize() > MAX_DOWNLOAD) throw new IOException("Expanded runtime ZIP entry exceeds limit");
                SecureFileOperations.ensureDirectory(target.getParent());
                try (var in = zip.getInputStream(entry); var output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536]; int n; long file = 0;
                    while ((n = in.read(buffer)) >= 0) {
                        file += n; total += n;
                        if (file > MAX_DOWNLOAD || total > 2L * 1024 * 1024 * 1024) throw new IOException("Expanded runtime archive exceeds limit");
                        output.write(buffer, 0, n);
                    }
                }
            }
        }
    }
    private static void requireWindowsX64(Path exe) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(exe, java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (channel.read(header) != 64 || header.get(0) != 'M' || header.get(1) != 'Z') throw new IOException("JBR launcher is not a Windows executable");
            int offset = header.getInt(60);
            if (offset < 64 || offset > 1024 * 1024) throw new IOException("Invalid JBR executable header");
            channel.position(offset); header.clear().limit(6);
            if (channel.read(header) != 6 || header.getInt(0) != 0x4550 || Short.toUnsignedInt(header.getShort(4)) != 0x8664) throw new IOException("JBR launcher is not Windows x64");
        }
    }
}
