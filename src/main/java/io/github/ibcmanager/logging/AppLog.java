package io.github.ibcmanager.logging;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.security.SecretRedactor;
import io.github.ibcmanager.security.FilePermissionHardener;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class AppLog implements AutoCloseable {
    public static final Duration DISK_FLUSH_INTERVAL = Duration.ofSeconds(60);
    private static final Logger LOGGER = Logger.getLogger("io.github.ibcmanager");
    private final Handler handler;
    private final Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private final Thread.UncaughtExceptionHandler installedUncaughtHandler;

    private AppLog(Handler handler, Thread.UncaughtExceptionHandler previousUncaughtHandler,
            Thread.UncaughtExceptionHandler installedUncaughtHandler) {
        this.handler = handler;
        this.previousUncaughtHandler = previousUncaughtHandler;
        this.installedUncaughtHandler = installedUncaughtHandler;
    }

    public static AppLog initialize(AppPaths paths) throws IOException {
        return initialize(paths, DISK_FLUSH_INTERVAL);
    }

    public static AppLog initialize(AppPaths paths, Duration flushInterval) throws IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(flushInterval, "flushInterval");
        FilePermissionHardener.hardenDirectory(paths.logs());
        // Do not forward records to the JVM root logger. The root ConsoleHandler
        // does not use SafeFormatter and could therefore expose credentials in a
        // console or redirected stderr even though the application log is redacted.
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.INFO);
        PeriodicFileHandler fileHandler = new PeriodicFileHandler(
                paths.logs().resolve("ibc-manager-%g.log").toString(),
                2 * 1024 * 1024,
                5,
                flushInterval,
                () -> PathPatternHardener.harden(paths));
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(new SafeFormatter());
        LOGGER.addHandler(fileHandler);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler installed = (thread, throwable) ->
                LOGGER.log(Level.SEVERE, "Unhandled exception on thread " + thread.getName(), throwable);
        Thread.setDefaultUncaughtExceptionHandler(installed);
        return new AppLog(fileHandler, previous, installed);
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    @Override
    public void close() {
        LOGGER.removeHandler(handler);
        handler.close();
        if (Thread.getDefaultUncaughtExceptionHandler() == installedUncaughtHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
        }
    }

    private static final class PathPatternHardener {
        private static void harden(AppPaths paths) {
            try {
                FilePermissionHardener.hardenDirectory(paths.logs());
                try (var stream = Files.newDirectoryStream(paths.logs(), "ibc-manager-*.log*")) {
                    for (var file : stream) FilePermissionHardener.hardenFile(file);
                }
            } catch (IOException ignored) {
                // Logging must remain available even when ACL hardening is unsupported.
            }
        }
    }

    private static final class SafeFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String message = SecretRedactor.redact(formatMessage(record));
            StringBuilder output = new StringBuilder(256)
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(' ')
                    .append(record.getLevel().getName())
                    .append(' ')
                    .append(record.getLoggerName())
                    .append(" - ")
                    .append(message)
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                output.append(record.getThrown().getClass().getName())
                        .append(": ")
                        .append(SecretRedactor.redact(String.valueOf(record.getThrown().getMessage())))
                        .append(System.lineSeparator());
                StackTraceElement[] stack = record.getThrown().getStackTrace();
                int limit = Math.min(stack.length, 256);
                for (int index = 0; index < limit; index++) {
                    output.append("    at ").append(stack[index]).append(System.lineSeparator());
                }
                if (stack.length > limit) {
                    output.append("    ... ").append(stack.length - limit)
                            .append(" additional frame(s) omitted").append(System.lineSeparator());
                }
            }
            return output.toString();
        }
    }
}
