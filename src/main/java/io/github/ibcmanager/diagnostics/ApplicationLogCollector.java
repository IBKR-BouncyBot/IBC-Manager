package io.github.ibcmanager.diagnostics;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.workaround.ManagedRuntimeService;
import io.github.ibcmanager.workaround.RuntimeCatalog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;

/** Read-only, bounded collection from configured local application locations, never the whole disk. */
final class ApplicationLogCollector {
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    static final int MAX_FILES = 16;
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_DIRECTORIES = 64;
    private static final Pattern LOG = Pattern.compile(
            "(?i)(?:(?:launcher|tws|ibgateway|api)(?:[._-][a-z0-9_-]+)*\\.log(?:\\.[0-9]+)?"
                    + "|hs_err_pid[0-9]+\\.log|(?:gateway|tws)-exported-logs[^/\\\\]*\\.txt)");
    private static final Pattern LOG_DIR = Pattern.compile("(?i)(?:logs?|[a-p]{32,64}|[0-9a-f]{32,64})");
    private final Profile profile;
    private final StringBuilder inventory = new StringBuilder(
            "Read-only broker log collection. Tails only; no remote access, log decryption or source modifications.\n"
                    + "Limits: 16 external text logs, 2 MiB per log, 16 MiB total including runtime details; "
                    + "2048 directory entries, 64 directories, depth 2. Newest files first; launcher.log prioritized.\n"
                    + "Encrypted/archived application logs are listed only: they cannot be safely redacted here. "
                    + "Export them to plaintext with File > Gateway Logs (or TWS Logs), then attach the export.\n"
                    + "Passwords, tokens and common identifiers are redacted best-effort; review before sharing. Shared settings directories may contain other sessions.\n\n");
    private int inspected;
    private int directories;
    private int bytes;
    private int files;
    private final Set<Path> visited = new HashSet<>();
    private final Set<Path> added = new HashSet<>();
    private final List<Candidate> candidates = new ArrayList<>();

    private record Directory(Path path, String label, int depth) { }
    private record Candidate(Path path, String source, long modified, boolean launcher) { }

    ApplicationLogCollector(Profile profile) { this.profile = profile; }

    void collect(ZipOutputStream zip, AppPaths paths) throws IOException {
        inventory.append("Managed JBR compatibility pin: ").append(RuntimeCatalog.JBR_VERSION).append('\n');
        LinkedHashMap<Path, String> roots = new LinkedHashMap<>();
        root(roots, profile.twsSettingsPath(), "settings");
        try {
            var layout = new OfflineApplicationLayoutResolver().resolve(profile);
            root(roots, layout.programPath(), "installation");
            Path managed = ManagedRuntimeService.directory(layout.vmOptionsPath());
            detail(zip, layout.vmOptionsPath(), "vmoptions.txt");
            String identity = safe(LauncherMetadata.inspect(layout));
            DiagnosticBundleService.putText(zip, "runtime-details/launcher-identity.txt", identity);
            bytes += identity.getBytes(StandardCharsets.UTF_8).length;
            installerOptions(zip, layout.install4jPath().resolve("i4jparams.conf"));
            detail(zip, managed.resolve("payload/download-provenance.txt"), "download-provenance.txt");
            detail(zip, managed.resolve("payload/jbr/release"), "managed-java-release.txt");
            detail(zip, layout.programPath().resolve("jre/release"), "bundled-java-release.txt");
            if (!profile.ibcJavaPath().toString().isBlank() && profile.ibcJavaPath().getParent() != null) {
                detail(zip, profile.ibcJavaPath().resolveSibling("release"), "override-java-release.txt");
            }
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            inventory.append("Installation details unavailable: ").append(safe(failure.getMessage())).append('\n');
        }
        // Rotated Manager logs are relevant but other profiles' output is not.
        for (int index = 1; index <= 2; index++) {
            detail(zip, paths.logs().resolve("ibc-manager-" + index + ".log"), "manager-rotation-" + index + ".txt");
        }
        // Always consider the current root launcher before bounded directory discovery.
        // A busy settings directory must not push the most relevant file past the scan cap.
        for (var root : roots.entrySet()) {
            Path launcher = root.getKey().resolve("launcher.log");
            try {
                requireSafe(launcher, false);
                if (added.add(launcher)) candidates.add(new Candidate(launcher,
                        root.getValue() + "/launcher.log", Files.getLastModifiedTime(launcher).toMillis(), true));
            } catch (IOException | SecurityException unavailable) {
                inventory.append("Current launcher unavailable: ").append(safe(launcher.toString()))
                        .append(" (").append(unavailable.getClass().getSimpleName()).append(")\n");
            }
        }
        for (var root : roots.entrySet()) scan(root.getKey(), root.getValue());
        candidates.sort(Comparator.comparing(Candidate::launcher).reversed()
                .thenComparing(Comparator.comparingLong(Candidate::modified).reversed())
                .thenComparing(candidate -> candidate.path().toString()));
        for (Candidate candidate : candidates) {
            if (files >= MAX_FILES || bytes >= MAX_TOTAL_BYTES) {
                inventory.append("OMITTED (bundle limit): ").append(safe(candidate.source())).append('\n');
                continue;
            }
            String entry = String.format(Locale.ROOT, "application-logs/%02d-%s.txt", ++files,
                    candidate.path().getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"));
            add(zip, candidate.path(), entry, candidate.source());
        }
        if (inspected >= MAX_ENTRIES || directories >= MAX_DIRECTORIES) {
            inventory.append("Discovery limit reached; additional directories/files were not inspected.\n");
        }
        if (candidates.isEmpty()) inventory.append("No accessible matching plaintext application logs found.\n");
        DiagnosticBundleService.putText(zip, "application-log-inventory.txt", safe(inventory.toString()));
    }

    private void root(LinkedHashMap<Path, String> roots, Path path, String label) {
        if (path == null || path.toString().isBlank()) {
            inventory.append(label).append(": not configured; current directory was not scanned.\n");
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null || normalized.toString().startsWith("\\\\")) {
            inventory.append(label).append(": refusing a filesystem root or network share.\n");
            return;
        }
        roots.putIfAbsent(normalized, label);
    }

    private void scan(Path root, String label) {
        ArrayDeque<Directory> queue = new ArrayDeque<>();
        queue.add(new Directory(root, label, 0));
        while (!queue.isEmpty() && inspected < MAX_ENTRIES && directories < MAX_DIRECTORIES) {
            Directory directory = queue.removeFirst();
            if (!visited.add(directory.path())) continue;
            directories++;
            try {
                requireSafe(directory.path(), true);
                inventory.append("Searching: ").append(safe(directory.path().toString())).append('\n');
                try (var entries = Files.newDirectoryStream(directory.path())) {
                    for (Path path : entries) {
                        if (++inspected > MAX_ENTRIES) break;
                        String name = path.getFileName().toString();
                        try {
                            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            String source = label + "/" + root.relativize(path);
                            if (attributes.isSymbolicLink() || attributes.isOther()) {
                                inventory.append("SKIPPED link/special entry: ").append(safe(source)).append('\n');
                            } else if (attributes.isDirectory() && directory.depth() < 2 && LOG_DIR.matcher(name).matches()) {
                                if (queue.size() + directories < MAX_DIRECTORIES) queue.add(new Directory(path, label, directory.depth() + 1));
                                else inventory.append("SKIPPED directory limit: ").append(safe(source)).append('\n');
                            } else if (attributes.isRegularFile() && LOG.matcher(name).matches()) {
                                if (added.add(path.toAbsolutePath().normalize())) candidates.add(new Candidate(path, source,
                                        attributes.lastModifiedTime().toMillis(), name.equalsIgnoreCase("launcher.log")));
                            } else if (attributes.isRegularFile() && encryptedOrArchived(name)) {
                                inventory.append("NOT INCLUDED (encrypted/archived; export plaintext): ")
                                        .append(safe(source)).append(" [bytes=").append(attributes.size()).append("]\n");
                            }
                        } catch (IOException | SecurityException failure) {
                            inventory.append("UNREADABLE: ").append(safe(name)).append(" (")
                                    .append(safe(failure.getClass().getSimpleName())).append(")\n");
                        }
                    }
                }
            } catch (IOException | SecurityException | java.nio.file.DirectoryIteratorException failure) {
                inventory.append("Directory unavailable: ").append(safe(directory.path().toString()))
                        .append(" (").append(safe(failure.getMessage())).append(")\n");
            }
        }
    }

    private void installerOptions(ZipOutputStream zip, Path path) throws IOException {
        String selected;
        try {
            requireSafe(path, false);
            if (Files.size(path) > 1024 * 1024) throw new IOException("Installer metadata too large");
            String text = readTail(path, 1024 * 1024);
            String line = text.lines().filter(value -> value.contains("<variable name=\"javaOptions\""))
                    .findFirst().orElse("");
            var value = Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(line);
            selected = value.find() && line.length() <= 16384 ? decodeXmlAttribute(value.group(1))
                    : "No bounded literal javaOptions variable found; no value was inferred.";
            selected = safe(selected) + "\n";
        } catch (IOException | SecurityException failure) {
            inventory.append("Install4j javaOptions unavailable: ").append(safe(failure.getClass().getSimpleName())).append('\n');
            return;
        }
        DiagnosticBundleService.putText(zip, "runtime-details/install4j-java-options.txt", selected);
        bytes += selected.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Decode only XML's built-in/character references, never DTDs or external entities. */
    private static String decodeXmlAttribute(String input) throws IOException {
        var entity = Pattern.compile("&([^;]{1,32});").matcher(input);
        StringBuilder decoded = new StringBuilder();
        int end = 0;
        while (entity.find()) {
            String literal = input.substring(end, entity.start());
            if (literal.indexOf('&') >= 0) throw new IOException("Unsupported XML entity");
            decoded.append(literal);
            String name = entity.group(1);
            String replacement = switch (name) {
                case "quot" -> "\""; case "apos" -> "'"; case "amp" -> "&";
                case "lt" -> "<"; case "gt" -> ">";
                default -> {
                    try {
                        int code = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16)
                                : name.startsWith("#") ? Integer.parseInt(name.substring(1)) : -1;
                        if (!Character.isValidCodePoint(code) || code == 0 || code >= 0xD800 && code <= 0xDFFF)
                            throw new NumberFormatException();
                        yield new String(Character.toChars(code));
                    } catch (IllegalArgumentException failure) { throw new IOException("Unsupported XML entity", failure); }
                }
            };
            decoded.append(replacement); end = entity.end();
        }
        String remainder = input.substring(end);
        if (remainder.indexOf('&') >= 0) throw new IOException("Unsupported XML entity");
        return decoded.append(remainder).toString();
    }

    private static boolean encryptedOrArchived(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ibgzenc") || lower.endsWith(".ibgenc") || lower.endsWith(".log.enc")
                || lower.endsWith(".log.gz") || lower.endsWith(".log.zip");
    }

    private void detail(ZipOutputStream zip, Path path, String entry) throws IOException {
        add(zip, path, "runtime-details/" + entry, path.toString());
    }

    private void add(ZipOutputStream zip, Path path, String entry, String source) throws IOException {
        String text;
        try {
            int perFileLimit = entry.startsWith("runtime-details/") && !entry.contains("manager-rotation-")
                    ? 128 * 1024 : MAX_FILE_BYTES;
            int limit = Math.min(perFileLimit, MAX_TOTAL_BYTES - bytes);
            if (limit <= 128) { inventory.append("OMITTED (bundle limit): ").append(safe(source)).append('\n'); return; }
            text = safe(readTail(path, limit - 128));
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > limit) {
                // Filtering can expand tiny values. Keep complete already-redacted lines only.
                int start = encoded.length - limit + 80;
                while (start < encoded.length && encoded[start] != '\n') start++;
                text = "[earlier redacted lines omitted]\n" + new String(encoded, Math.min(start + 1, encoded.length),
                        Math.max(0, encoded.length - start - 1), StandardCharsets.UTF_8);
            }
        } catch (IOException | SecurityException failure) {
            inventory.append("UNAVAILABLE: ").append(safe(source)).append(" (")
                    .append(safe(failure.getMessage())).append(")\n");
            return; // Do not let one locked, missing or non-text file prevent the other diagnostics.
        }
        // ZIP failures must propagate, rather than being disguised as unavailable source files.
        DiagnosticBundleService.putText(zip, entry, text);
        bytes += text.getBytes(StandardCharsets.UTF_8).length;
        inventory.append("INCLUDED: ").append(entry).append(" <- ").append(safe(source)).append('\n');
    }

    static String readTail(Path path, int limit) throws IOException {
        requireSafe(path, false);
        try (FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            ByteBuffer prefix = ByteBuffer.allocate(4); channel.read(prefix); prefix.flip();
            Charset charset = StandardCharsets.UTF_8;
            if (prefix.remaining() >= 2) {
                int a = prefix.get() & 255, b = prefix.get() & 255;
                if (a == 255 && b == 254) charset = StandardCharsets.UTF_16LE;
                if (a == 254 && b == 255) charset = StandardCharsets.UTF_16BE;
            }
            long start = Math.max(0, size - limit);
            if (charset != StandardCharsets.UTF_8 && start % 2 != 0) start++;
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(limit, size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            buffer.flip();
            // A tail can start inside a UTF-8 character or inside a sensitive line. Drop that line.
            if (start > 0 && charset == StandardCharsets.UTF_8) {
                while (buffer.hasRemaining() && buffer.get() != '\n') { }
            }
            String text;
            try { text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString(); }
            catch (CharacterCodingException failure) { throw new IOException("Not decodable text; omitted", failure); }
            if (start > 0 && charset != StandardCharsets.UTF_8) {
                int newline = text.indexOf('\n'); text = newline < 0 ? "" : text.substring(newline + 1);
            }
            if (text.indexOf('\0') >= 0) throw new IOException("Binary content; omitted");
            StringBuilder safeLines = new StringBuilder();
            if (start > 0) safeLines.append("[earlier bytes/partial first line omitted; source bytes=").append(size).append("]\n");
            for (String line : text.replace("\uFEFF", "").split("\\R", -1)) {
                safeLines.append(line.length() > 65536 ? "[oversized line omitted]" : line).append('\n');
            }
            return safeLines.toString();
        }
    }

    static void requireSafe(Path path, boolean directory) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.toString().startsWith("\\\\")) throw new IOException("Network shares are not scanned");
        for (Path current = absolute; current != null; current = current.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Link/reparse/special source omitted");
        }
        if (!absolute.equals(absolute.toRealPath())) throw new IOException("Redirected source path omitted");
        BasicFileAttributes attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) throw new IOException("Wrong source file type");
    }

    private String safe(String text) { return DiagnosticRedactor.redact(text, profile.username()); }
}
