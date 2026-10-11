package io.github.ibcmanager.diagnostics;

import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only identity hints from the selected native launcher; never evaluates or applies arguments. */
final class LauncherMetadata {
    private static final int MAX_SCAN_BYTES = 16 * 1024 * 1024;
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final int OVERLAP = 4096;
    private static final int MAX_MATCHES = 64;
    private static final Pattern PROPERTY = Pattern.compile(
            "(?:[\\\"])?-D(installDir|channel|nightlyEnabled|privateLabel|installerVersion|hideTryDemo|"
                    + "installer\\.groupId|fullVersion|mediaFileArch|productName|install4j\\.launcherId|"
                    + "install4j\\.swt|exe4j\\.isInstall4j|install4jType)="
                    + "([^\\x00-\\x1f]{0,1024}?)(?=\\s+[\\\"]?-[A-Za-z]|[\\x00-\\x1f]|$)");

    private LauncherMetadata() { }

    static String inspect(OfflineApplicationLayoutResolver.Layout layout) {
        String name = layout.vmOptionsPath().getFileName().toString().replaceFirst("\\.vmoptions$", ".exe");
        Path executable = layout.programPath().resolve(name);
        StringBuilder report = new StringBuilder("Read-only launcher identity inspection. No executable was run; no arguments were applied.\n"
                + "Only allowlisted literal -D identity properties are shown. Embedded templates are not necessarily effective runtime values.\n"
                + "Paths/placeholders are not expanded. Different values are reported, not guessed.\n"
                + "Do not copy TWS identity values into Gateway or change the update channel from an example.\n\n"
                + "Selected native launcher: " + executable + "\n");
        try {
            Scan result = scan(executable);
            report.append("Scanned ").append(result.scanned()).append(" of ").append(result.size()).append(" bytes (16 MiB cap).\n");
            if (result.properties().isEmpty()) report.append("No recognized identity strings found; absence is not proof that a property is unset.\n");
            for (String property : result.properties()) report.append(property).append('\n');
            if (result.properties().size() >= MAX_MATCHES) report.append("Identity-string limit reached.\n");
        } catch (IOException | SecurityException failure) {
            report.append("Launcher unavailable: ").append(failure.getClass().getSimpleName()).append("\n");
        }
        return report.toString();
    }

    record Scan(long size, long scanned, Set<String> properties) { }

    static Scan scan(Path file) throws IOException {
        ApplicationLogCollector.requireSafe(file, false);
        Set<String> matches = new LinkedHashSet<>();
        try (FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size(), end = Math.min(size, MAX_SCAN_BYTES), offset = 0;
            while (offset < end && matches.size() < MAX_MATCHES) {
                int count = (int) Math.min(CHUNK_BYTES, end - offset);
                ByteBuffer buffer = ByteBuffer.allocate(count);
                channel.position(offset);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                byte[] bytes = buffer.array();
                int length = buffer.position();
                if (length == 0) break;
                collect(new String(bytes, 0, length, StandardCharsets.ISO_8859_1), matches, offset + length == size);
                // PE resources may contain UTF-16LE strings at either byte alignment.
                for (int shift = 0; shift < 2 && length > shift + 1; shift++) {
                    collect(new String(bytes, shift, (length - shift) & ~1, StandardCharsets.UTF_16LE), matches, offset + length == size);
                }
                if (matches.size() >= MAX_MATCHES || offset + length >= end || length <= OVERLAP) { offset += length; break; }
                offset += length - OVERLAP;
            }
            return new Scan(size, Math.min(offset, end), java.util.Collections.unmodifiableSet(matches));
        }
    }

    private static void collect(String text, Set<String> matches, boolean complete) {
        var matcher = PROPERTY.matcher(text);
        while (matches.size() < MAX_MATCHES && matcher.find()) {
            if (!complete && matcher.end() == text.length()) continue;
            String value = matcher.group(2).strip();
            if (value.endsWith("\"")) value = value.substring(0, value.length() - 1);
            if (value.startsWith("\"")) value = value.substring(1);
            if (value.length() > 1024 || !value.chars().allMatch(c -> c >= 32 && c <= 126)) continue;
            matches.add("-D" + matcher.group(1) + "=" + value);
        }
    }
}
