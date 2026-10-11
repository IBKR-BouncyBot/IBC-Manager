package io.github.ibcmanager.app;

import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Copies configuration only; never duplicates runtime state, credentials or download ownership. */
public final class ProfileCopyService {
    private ProfileCopyService() { }
    public static Profile copy(Profile source, Collection<Profile> existing) {
        Set<String> names = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        for (Profile profile : existing) {
            names.add(profile.name().toLowerCase(Locale.ROOT));
            ports.add(profile.apiPort()); ports.add(profile.commandServerPort());
        }
        String base = "Copy of " + source.name();
        if (base.length() > 65) base = base.substring(0, 65);
        String name = base;
        for (int n = 2; names.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + " (" + n + ")";
        int api = unused(source.apiPort(), ports); ports.add(api);
        int command = unused(source.commandServerPort(), ports);
        return source.toBuilder().id(UUID.randomUUID()).name(name).profileOnlyConfiguration(true)
                .enabled(false).autoStart(false).credentialMode(CredentialMode.MANUAL)
                .apiPort(api).commandServerPort(command).managedHeadlessRuntime(false)
                .ibcJavaPath(source.managedHeadlessRuntime() ? Path.of("") : source.ibcJavaPath()).build();
    }
    private static int unused(int preferred, Set<Integer> used) {
        for (int offset = 1; offset < 65535; offset++) {
            int port = 1 + (preferred - 1 + offset) % 65535;
            if (!used.contains(port)) return port;
        }
        throw new IllegalArgumentException("No unused profile port is available");
    }
}
