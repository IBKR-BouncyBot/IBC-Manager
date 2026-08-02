package io.github.ibcmanager.task;

import io.github.ibcmanager.security.TextSafety;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class TaskSchedulerCommandBuilder {
    private static final long MAX_DELAY_SECONDS = 9_999L * 60L + 59L;

    private TaskSchedulerCommandBuilder() { }

    public static List<String> query(String taskName) {
        return List.of("schtasks.exe", "/Query", "/TN", validateTaskName(taskName));
    }

    public static List<String> delete(String taskName) {
        return List.of("schtasks.exe", "/Delete", "/TN", validateTaskName(taskName), "/F");
    }

    public static List<String> createAtLogon(String taskName, Path executable, List<String> arguments,
            Duration startupDelay) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(startupDelay, "startupDelay");
        long delaySeconds;
        try {
            delaySeconds = startupDelay.toSeconds();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Startup delay is too large", ex);
        }
        if (startupDelay.isNegative() || startupDelay.minusSeconds(delaySeconds).isZero() == false
                || delaySeconds > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("Startup delay must be a whole number of seconds between 0 and 9999:59");
        }

        StringBuilder action = new StringBuilder(windowsQuote(executable.toAbsolutePath().normalize().toString()));
        for (String argument : arguments) action.append(' ').append(windowsQuote(argument));
        List<String> command = new ArrayList<>(List.of(
                "schtasks.exe", "/Create", "/TN", validateTaskName(taskName),
                "/SC", "ONLOGON", "/TR", taskRunArgument(action.toString())));
        if (delaySeconds > 0) {
            command.add("/DELAY");
            command.add(String.format(Locale.ROOT, "%04d:%02d", delaySeconds / 60, delaySeconds % 60));
        }
        command.add("/IT");
        command.add("/RL");
        command.add("LIMITED");
        command.add("/F");
        return List.copyOf(command);
    }

    static String windowsQuote(String value) {
        Objects.requireNonNull(value, "value");
        if (TextSafety.containsConfigBreakingControl(value)) {
            throw new IllegalArgumentException("Task argument contains an invalid control character");
        }
        return '"' + escapeQuotedBody(value) + '"';
    }

    /**
     * Wraps the schtasks {@code /TR} action so that it reaches schtasks.exe as a single argument.
     *
     * <p>The action itself is a command line: it starts and ends with a double quote. On Windows,
     * java.lang.ProcessImpl treats such an argument as already quoted and appends it to the child
     * command line verbatim, so schtasks' own argument parser would split it back into several
     * arguments and reject the task. Escaping the interior quotes and re-wrapping the result keeps
     * the argument recognisably quoted while still parsing back to exactly {@code action}.
     */
    static String taskRunArgument(String action) {
        Objects.requireNonNull(action, "action");
        return '"' + escapeQuotedBody(action) + '"';
    }

    private static String escapeQuotedBody(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        int backslashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                backslashes++;
            } else if (character == '"') {
                result.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                result.append("\\".repeat(backslashes)).append(character);
                backslashes = 0;
            }
        }
        result.append("\\".repeat(backslashes * 2));
        return result.toString();
    }

    private static String validateTaskName(String taskName) {
        Objects.requireNonNull(taskName, "taskName");
        String name = taskName.trim();
        if (name.isEmpty() || name.length() > 200 || TextSafety.containsConfigBreakingControl(name)) {
            throw new IllegalArgumentException("Invalid Task Scheduler task name");
        }
        return name;
    }
}
