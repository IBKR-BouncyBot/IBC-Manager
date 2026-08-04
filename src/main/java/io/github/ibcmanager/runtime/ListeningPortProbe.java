package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.logging.AppLog;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CommandExecutor;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.security.DefaultCommandExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects local TCP listeners without opening a client connection to them.
 *
 * <p>Windows observations retain the local address and owning PID reported by
 * {@code netstat -ano}. Other platforms retain the address where practical and
 * report ownership as unavailable rather than claiming that the listener belongs
 * to the managed IBC/Gateway process.</p>
 */
public final class ListeningPortProbe implements PortProbe {
    private static final Logger LOG = AppLog.get(ListeningPortProbe.class);
    private static final Duration DEFAULT_CACHE_LIFETIME = Duration.ofSeconds(5);
    private static final Duration DEFAULT_MAXIMUM_STALE_AGE = Duration.ofSeconds(15);
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_PROC_BYTES = 8 * 1024 * 1024;

    /** Backward-compatible test source that reports only port numbers. */
    @FunctionalInterface
    interface ListeningPortSource {
        Set<Integer> listeningPorts(Duration timeout) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface ListenerEndpointSource {
        Set<ListenerEndpoint> listeningEndpoints(Duration timeout) throws IOException, InterruptedException;
    }

    record ListenerEndpoint(String localAddress, int port, long owningPid) {
        ListenerEndpoint {
            localAddress = normalizeAddress(localAddress);
            if (port < 1 || port > 65_535) throw new IllegalArgumentException("port is out of range");
            if (owningPid < -1) throw new IllegalArgumentException("owningPid is out of range");
        }
    }

    private final ListenerEndpointSource source;
    private final long cacheLifetimeNanos;
    private final long maximumStaleAgeNanos;
    private final long failureLogIntervalNanos;
    private final LongSupplier nanoTime;

    private Set<ListenerEndpoint> cachedEndpoints = Set.of();
    private boolean snapshotAvailable;
    private boolean freshSnapshotRequired;
    private long lastAttemptNanos = Long.MIN_VALUE;
    private long lastSuccessNanos = Long.MIN_VALUE;
    private long lastFailureLogNanos = Long.MIN_VALUE;

    public ListeningPortProbe() {
        this(OperatingSystem.current(), new DefaultCommandExecutor());
    }

    public ListeningPortProbe(OperatingSystem operatingSystem, CommandExecutor commandExecutor) {
        this(sourceFor(Objects.requireNonNull(operatingSystem, "operatingSystem"),
                        Objects.requireNonNull(commandExecutor, "commandExecutor")),
                DEFAULT_CACHE_LIFETIME, DEFAULT_MAXIMUM_STALE_AGE,
                FAILURE_LOG_INTERVAL, System::nanoTime, true);
    }

    ListeningPortProbe(ListeningPortSource source, Duration cacheLifetime,
            Duration maximumStaleAge, Duration failureLogInterval, LongSupplier nanoTime) {
        this(timeout -> {
            Set<Integer> ports = source.listeningPorts(timeout);
            HashSet<ListenerEndpoint> endpoints = new HashSet<>();
            for (Integer port : ports == null ? Set.<Integer>of() : ports) {
                if (port != null && port >= 1 && port <= 65_535) {
                    endpoints.add(new ListenerEndpoint("0.0.0.0", port, -1));
                }
            }
            return Set.copyOf(endpoints);
        }, cacheLifetime, maximumStaleAge, failureLogInterval, nanoTime, true);
    }

    private ListeningPortProbe(ListenerEndpointSource source, Duration cacheLifetime,
            Duration maximumStaleAge, Duration failureLogInterval, LongSupplier nanoTime,
            boolean ignored) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cacheLifetime, "cacheLifetime");
        Objects.requireNonNull(maximumStaleAge, "maximumStaleAge");
        Objects.requireNonNull(failureLogInterval, "failureLogInterval");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (cacheLifetime.isNegative()) throw new IllegalArgumentException("cacheLifetime must not be negative");
        if (maximumStaleAge.isNegative()) throw new IllegalArgumentException("maximumStaleAge must not be negative");
        if (failureLogInterval.isNegative()) throw new IllegalArgumentException("failureLogInterval must not be negative");
        this.cacheLifetimeNanos = cacheLifetime.toNanos();
        this.maximumStaleAgeNanos = maximumStaleAge.toNanos();
        this.failureLogIntervalNanos = failureLogInterval.toNanos();
    }

    @Override
    public PortListenerState inspect(String host, int port, Duration timeout) {
        return observe(host, port, timeout).state();
    }

    @Override
    public ListenerObservation observe(String host, int port, Duration timeout) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535 || timeout == null
                || timeout.isZero() || timeout.isNegative()) {
            return ListenerObservation.unknown(port >= 1 && port <= 65_535 ? port : -1);
        }
        ListenerSnapshot snapshot = snapshot(timeout);
        if (!snapshot.available()) return ListenerObservation.unknown(port);

        String requested = normalizeAddress(host);
        List<ListenerEndpoint> candidates = snapshot.endpoints().stream()
                .filter(endpoint -> endpoint.port() == port)
                .filter(endpoint -> addressMatches(requested, endpoint.localAddress()))
                .sorted(Comparator
                        .comparing((ListenerEndpoint endpoint) -> !endpoint.localAddress().equals(requested))
                        .thenComparing(endpoint -> endpoint.owningPid() <= 0))
                .toList();
        if (candidates.isEmpty()) return ListenerObservation.notListening(requested, port);
        ListenerEndpoint selected = candidates.get(0);
        return ListenerObservation.listening(selected.localAddress(), port, selected.owningPid());
    }

    @Override
    public synchronized void invalidate() {
        lastAttemptNanos = Long.MIN_VALUE;
        freshSnapshotRequired = true;
    }

    private synchronized ListenerSnapshot snapshot(Duration timeout) {
        long now = nanoTime.getAsLong();
        if (lastAttemptNanos != Long.MIN_VALUE && elapsed(now, lastAttemptNanos) < cacheLifetimeNanos) {
            return new ListenerSnapshot(cachedEndpoints, snapshotAvailable);
        }
        boolean forceFresh = freshSnapshotRequired;
        freshSnapshotRequired = false;
        lastAttemptNanos = now;
        try {
            Set<ListenerEndpoint> observed = source.listeningEndpoints(timeout);
            HashSet<ListenerEndpoint> validated = new HashSet<>();
            for (ListenerEndpoint endpoint : observed == null ? Set.<ListenerEndpoint>of() : observed) {
                if (endpoint != null) validated.add(endpoint);
            }
            cachedEndpoints = Collections.unmodifiableSet(validated);
            snapshotAvailable = true;
            lastSuccessNanos = now;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handleFailure(now, forceFresh, ex);
        } catch (IOException | RuntimeException ex) {
            handleFailure(now, forceFresh, ex);
        }
        return new ListenerSnapshot(cachedEndpoints, snapshotAvailable);
    }

    private void handleFailure(long now, boolean forceFresh, Exception failure) {
        if (forceFresh || lastSuccessNanos == Long.MIN_VALUE
                || elapsed(now, lastSuccessNanos) > maximumStaleAgeNanos) {
            snapshotAvailable = false;
        }
        if (lastFailureLogNanos == Long.MIN_VALUE
                || elapsed(now, lastFailureLogNanos) >= failureLogIntervalNanos) {
            lastFailureLogNanos = now;
            String message = failure.getMessage();
            LOG.log(Level.WARNING, "Could not inspect TCP listeners passively: {0}",
                    message == null || message.isBlank()
                            ? failure.getClass().getSimpleName()
                            : message.replace('\r', ' ').replace('\n', ' '));
        }
    }

    private record ListenerSnapshot(Set<ListenerEndpoint> endpoints, boolean available) { }

    private static long elapsed(long now, long then) {
        long difference = now - then;
        return difference < 0 ? Long.MAX_VALUE : difference;
    }

    private static ListenerEndpointSource sourceFor(OperatingSystem operatingSystem,
            CommandExecutor commandExecutor) {
        return switch (operatingSystem) {
            case WINDOWS -> timeout -> windowsListeningEndpoints(commandExecutor, timeout);
            case LINUX -> timeout -> linuxListeningEndpoints(
                    Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"));
            case MAC -> timeout -> unixNetstatListeningEndpoints(commandExecutor, timeout,
                    List.of("/usr/sbin/netstat", "-anv", "-p", "tcp"));
            case OTHER -> timeout -> {
                throw new IOException("Passive TCP-listener inspection is unsupported on this operating system");
            };
        };
    }

    private static Set<ListenerEndpoint> windowsListeningEndpoints(CommandExecutor executor, Duration timeout)
            throws IOException, InterruptedException {
        List<String> command = windowsNetstatCommand();
        if (command.isEmpty()) throw new IOException("Trusted Windows netstat.exe was not found");
        CommandResult result = executor.execute(command, "", timeout);
        if (result.timedOut()) throw new IOException("Windows netstat timed out");
        if (result.exitCode() != 0) throw new IOException("Windows netstat failed with exit code " + result.exitCode());
        return parseWindowsNetstatEndpoints(result.stdout());
    }

    private static List<String> windowsNetstatCommand() {
        for (String variable : List.of("SystemRoot", "WINDIR")) {
            String root = System.getenv(variable);
            if (root == null || root.isBlank()) continue;
            try {
                Path candidate = Path.of(root).resolve("System32").resolve("netstat.exe")
                        .toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(candidate)) {
                    return List.of(candidate.toString(), "-a", "-n", "-o", "-p", "tcp");
                }
            } catch (RuntimeException ignored) {
                // Continue to the next trusted Windows directory candidate.
            }
        }
        return List.of();
    }

    static Set<Integer> parseWindowsNetstat(String output) {
        HashSet<Integer> ports = new HashSet<>();
        for (ListenerEndpoint endpoint : parseWindowsNetstatEndpoints(output)) ports.add(endpoint.port());
        return Set.copyOf(ports);
    }

    static Set<ListenerEndpoint> parseWindowsNetstatEndpoints(String output) {
        if (output == null || output.isBlank()) return Set.of();
        HashSet<ListenerEndpoint> endpoints = new HashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 4 || !fields[0].equalsIgnoreCase("TCP")) continue;
            Endpoint local = parseEndpoint(fields[1]);
            Endpoint remote = parseEndpoint(fields[2]);
            if (local.port() < 1) continue;
            boolean explicitListening = false;
            for (int index = 3; index < fields.length; index++) {
                String state = fields[index].toUpperCase(Locale.ROOT);
                if (state.equals("LISTENING") || state.equals("LISTEN")) {
                    explicitListening = true;
                    break;
                }
            }
            if (!explicitListening && remote.port() != 0) continue;
            long pid = parsePositiveLong(fields[fields.length - 1]);
            endpoints.add(new ListenerEndpoint(local.address(), local.port(), pid));
        }
        return Set.copyOf(endpoints);
    }

    private static Set<ListenerEndpoint> linuxListeningEndpoints(Path tcp, Path tcp6) throws IOException {
        HashSet<ListenerEndpoint> endpoints = new HashSet<>();
        boolean sourcePresent = false;
        for (Path path : List.of(tcp, tcp6)) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            sourcePresent = true;
            String text = BoundedFileReader.readString(path, StandardCharsets.US_ASCII,
                    MAX_PROC_BYTES, "Linux TCP listener table");
            endpoints.addAll(parseLinuxProcNetEndpoints(text, path.equals(tcp6)));
        }
        if (!sourcePresent) throw new IOException("Linux TCP listener tables are unavailable");
        return Set.copyOf(endpoints);
    }

    static Set<Integer> parseLinuxProcNet(String output) {
        HashSet<Integer> ports = new HashSet<>();
        for (ListenerEndpoint endpoint : parseLinuxProcNetEndpoints(output, false)) ports.add(endpoint.port());
        return Set.copyOf(ports);
    }

    private static Set<ListenerEndpoint> parseLinuxProcNetEndpoints(String output, boolean ipv6) {
        if (output == null || output.isBlank()) return Set.of();
        HashSet<ListenerEndpoint> endpoints = new HashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("sl")) continue;
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 4 || !fields[3].equalsIgnoreCase("0A")) continue;
            Endpoint endpoint = parseHexEndpoint(fields[1], ipv6);
            if (endpoint.port() >= 1) {
                endpoints.add(new ListenerEndpoint(endpoint.address(), endpoint.port(), -1));
            }
        }
        return Set.copyOf(endpoints);
    }

    private static Set<ListenerEndpoint> unixNetstatListeningEndpoints(CommandExecutor executor,
            Duration timeout, List<String> command) throws IOException, InterruptedException {
        CommandResult result = executor.execute(command, "", timeout);
        if (result.timedOut()) throw new IOException("netstat timed out");
        if (result.exitCode() != 0) throw new IOException("netstat failed with exit code " + result.exitCode());
        return parseUnixNetstatEndpoints(result.stdout());
    }

    static Set<Integer> parseUnixNetstat(String output) {
        HashSet<Integer> ports = new HashSet<>();
        for (ListenerEndpoint endpoint : parseUnixNetstatEndpoints(output)) ports.add(endpoint.port());
        return Set.copyOf(ports);
    }

    private static Set<ListenerEndpoint> parseUnixNetstatEndpoints(String output) {
        if (output == null || output.isBlank()) return Set.of();
        HashSet<ListenerEndpoint> endpoints = new HashSet<>();
        for (String line : output.split("\\R")) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (!upper.contains("LISTEN")) continue;
            for (String token : line.trim().split("\\s+")) {
                Endpoint endpoint = parseEndpoint(token);
                if (endpoint.port() >= 1) {
                    endpoints.add(new ListenerEndpoint(endpoint.address(), endpoint.port(), -1));
                    break;
                }
            }
        }
        return Set.copyOf(endpoints);
    }

    private static Endpoint parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return new Endpoint("", -1);
        String value = endpoint.trim();
        int separator = value.lastIndexOf(':');
        if (separator < 0 || separator == value.length() - 1) separator = value.lastIndexOf('.');
        if (separator < 0 || separator == value.length() - 1) return new Endpoint("", -1);
        int port;
        try {
            port = Integer.parseInt(value.substring(separator + 1));
        } catch (NumberFormatException ex) {
            return new Endpoint("", -1);
        }
        if (port < 0 || port > 65_535) return new Endpoint("", -1);
        String address = value.substring(0, separator);
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        return new Endpoint(normalizeAddress(address), port);
    }

    private static Endpoint parseHexEndpoint(String endpoint, boolean ipv6) {
        if (endpoint == null || endpoint.isBlank()) return new Endpoint("", -1);
        int separator = endpoint.lastIndexOf(':');
        if (separator < 0 || separator == endpoint.length() - 1) return new Endpoint("", -1);
        try {
            int port = Integer.parseInt(endpoint.substring(separator + 1), 16);
            String address = decodeProcAddress(endpoint.substring(0, separator), ipv6);
            return new Endpoint(address, port);
        } catch (NumberFormatException ex) {
            return new Endpoint("", -1);
        }
    }

    private static String decodeProcAddress(String hex, boolean ipv6) {
        if (!ipv6 && hex.length() == 8) {
            List<String> octets = new ArrayList<>(4);
            for (int offset = 6; offset >= 0; offset -= 2) {
                octets.add(Integer.toString(Integer.parseInt(hex.substring(offset, offset + 2), 16)));
            }
            return String.join(".", octets);
        }
        if (ipv6 && hex.chars().allMatch(character -> character == '0')) return "::";
        return ipv6 ? "::" : "0.0.0.0";
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static boolean addressMatches(String requested, String observed) {
        String normalizedRequested = normalizeAddress(requested);
        String normalizedObserved = normalizeAddress(observed);
        if (isWildcard(normalizedObserved)) return true;
        if (normalizedRequested.equals(normalizedObserved)) return true;
        if (isLoopbackName(normalizedRequested) && isLoopbackAddress(normalizedObserved)) return true;
        return false;
    }

    private static boolean isWildcard(String value) {
        return value.isEmpty() || value.equals("0.0.0.0") || value.equals("::") || value.equals("*");
    }

    private static boolean isLoopbackName(String value) {
        return value.equals("localhost") || isLoopbackAddress(value);
    }

    private static boolean isLoopbackAddress(String value) {
        return value.equals("::1") || value.startsWith("127.");
    }

    private static String normalizeAddress(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("0:0:0:0:0:0:0:0")) return "::";
        if (normalized.equals("0:0:0:0:0:0:0:1")) return "::1";
        return normalized;
    }

    private record Endpoint(String address, int port) { }
}
