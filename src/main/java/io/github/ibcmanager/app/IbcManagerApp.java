package io.github.ibcmanager.app;

import io.github.ibcmanager.logging.AppLog;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.ui.MainFrame;
import io.github.ibcmanager.ui.UiTheme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IbcManagerApp {
    private static final Logger LOG = AppLog.get(IbcManagerApp.class);

    private IbcManagerApp() { }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0 || contains(args, "--headless-smoke") || contains(args, "--version")) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        Arguments parsed;
        try {
            parsed = Arguments.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            printUsage();
            return 2;
        }
        if (parsed.version) {
            System.out.println(Version.APPLICATION_NAME + " " + Version.VERSION
                    + " (Engine: " + Version.IBC_RELEASE_CHANNEL
                    + "; upstream " + Version.IBC_MINIMUM_SUPPORTED_VERSION + ")");
            return 0;
        }
        AppPaths paths = parsed.dataDirectory == null
                ? AppPaths.systemDefault() : new AppPaths(parsed.dataDirectory);
        if (parsed.headlessSmoke) return headlessSmoke(paths);
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            System.err.println("IBC Manager 2.0 supports Windows only; non-Windows hosts may run --headless-smoke for build validation.");
            return 3;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A graphical desktop session is required. Use --headless-smoke for non-GUI validation.");
            return 3;
        }

        try {
            SingleInstanceLock lock = SingleInstanceLock.acquire(paths.lockFile());
            AppLog appLog;
            try {
                appLog = AppLog.initialize(paths);
            } catch (IOException | RuntimeException failure) {
                try { lock.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            AppServices services = initializeServices(paths, appLog, lock);
            AtomicBoolean closed = new AtomicBoolean();
            SwingUtilities.invokeLater(() -> {
                try {
                    UiTheme.install();
                    MainFrame frame = new MainFrame(services);
                    frame.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            if (!closed.compareAndSet(false, true)) return;
                            services.close();
                            appLog.close();
                            try { lock.close(); }
                            catch (IOException ex) { LOG.log(Level.WARNING, "Could not release single-instance lock", ex); }
                        }
                    });
                    frame.setVisible(true);
                    if (parsed.autoStart) frame.startAutoProfiles();
                } catch (Throwable ex) {
                    LOG.log(Level.SEVERE, "Could not initialize the user interface", ex);
                    JOptionPane.showMessageDialog(null, String.valueOf(ex.getMessage()),
                            "IBC Manager startup failed", JOptionPane.ERROR_MESSAGE);
                    services.close();
                    appLog.close();
                    try { lock.close(); }
                    catch (IOException ignored) { }
                }
            });
            return 0;
        } catch (IOException | RuntimeException ex) {
            System.err.println("IBC Manager could not start: " + ex.getMessage());
            return 4;
        }
    }

    static AppServices initializeServices(AppPaths paths, AppLog appLog, SingleInstanceLock lock)
            throws IOException {
        try {
            return AppServices.create(paths);
        } catch (IOException | RuntimeException failure) {
            try { appLog.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            try { lock.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static int headlessSmoke(AppPaths paths) {
        try (AppServices services = AppServices.create(paths)) {
            ProfileRepository.LoadResult result = services.profileRepository().loadAll();
            services.runtimeRegistry().setProfiles(result.profiles());
            long invalid = result.profiles().stream()
                    .filter(profile -> !services.profileValidator().validate(profile, false).isValid())
                    .count();
            System.out.println("IBC Manager headless smoke test: OK");
            System.out.println("Data directory: " + paths.root());
            System.out.println("Profiles loaded: " + result.profiles().size());
            System.out.println("Profiles with validation errors: " + invalid);
            System.out.println("Recovery warnings: " + result.warnings().size());
            return invalid == 0 ? 0 : 5;
        } catch (IOException | RuntimeException ex) {
            System.err.println("Headless smoke test failed: " + ex.getMessage());
            return 6;
        }
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) if (value.equals(arg)) return true;
        return false;
    }

    private static void printUsage() {
        System.err.println("Usage: IBCManager [--autostart] [--data-dir <directory>] [--version] [--headless-smoke]");
    }

    private static final class Arguments {
        private final boolean autoStart;
        private final boolean version;
        private final boolean headlessSmoke;
        private final Path dataDirectory;

        private Arguments(boolean autoStart, boolean version, boolean headlessSmoke, Path dataDirectory) {
            this.autoStart = autoStart;
            this.version = version;
            this.headlessSmoke = headlessSmoke;
            this.dataDirectory = dataDirectory;
        }

        private static Arguments parse(String[] args) {
            boolean autoStart = false;
            boolean version = false;
            boolean smoke = false;
            Path dataDirectory = null;
            List<String> values = new ArrayList<>(List.of(args));
            for (int index = 0; index < values.size(); index++) {
                String argument = values.get(index);
                switch (argument) {
                    case "--autostart" -> autoStart = true;
                    case "--version" -> version = true;
                    case "--headless-smoke" -> smoke = true;
                    case "--data-dir" -> {
                        if (++index >= values.size()) throw new IllegalArgumentException("--data-dir requires a directory");
                        try {
                            dataDirectory = Path.of(values.get(index)).toAbsolutePath().normalize();
                        } catch (java.nio.file.InvalidPathException ex) {
                            throw new IllegalArgumentException("--data-dir contains a path that is invalid on this operating system", ex);
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            return new Arguments(autoStart, version, smoke, dataDirectory);
        }
    }
}
