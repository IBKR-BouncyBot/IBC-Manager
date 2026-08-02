package io.github.ibcmanager.app;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record StartupCommand(Path executable, List<String> arguments) {
    public StartupCommand {
        executable = executable.toAbsolutePath().normalize();
        arguments = List.copyOf(arguments);
    }

    public static Optional<StartupCommand> detect() {
        String packaged = System.getProperty("jpackage.app-path", "").trim();
        if (!packaged.isEmpty()) return Optional.of(new StartupCommand(Path.of(packaged), List.of("--autostart")));
        try {
            Path location = Path.of(StartupCommand.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(location) && location.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                String javaHome = System.getProperty("java.home", "");
                Path java = Path.of(javaHome, "bin", OperatingSystem.current() == OperatingSystem.WINDOWS ? "javaw.exe" : "java");
                List<String> arguments = new ArrayList<>();
                arguments.add("-jar");
                arguments.add(location.toString());
                arguments.add("--autostart");
                return Optional.of(new StartupCommand(java, arguments));
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Running from loose classes is intentionally not registered as a startup task.
        }
        return Optional.empty();
    }
}
