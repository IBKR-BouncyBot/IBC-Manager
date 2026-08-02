package io.github.ibcmanager.task;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.security.CommandExecutor;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class TaskSchedulerTests implements TestSuite {
    @Override public String name() { return "Windows Task Scheduler integration"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("quotes Windows executable and argument values correctly", this::windowsQuoting),
                new NamedTest("rejects control characters in scheduled-task arguments", this::invalidArguments),
                new NamedTest("builds exact query and delete commands", this::queryDeleteCommands),
                new NamedTest("validates scheduled-task names", this::taskNames),
                new NamedTest("builds an interactive delayed logon task", this::createCommand),
                new NamedTest("task action survives Windows command-line parsing as one argument",
                        this::taskActionRoundTrip),
                new NamedTest("supports an immediate logon task without a delay switch", this::zeroDelay),
                new NamedTest("validates startup delay bounds and precision", this::delayValidation),
                new NamedTest("reports Task Scheduler availability by operating system", this::availability),
                new NamedTest("queries installed and absent tasks", this::queryService),
                new NamedTest("reports query timeout", this::queryTimeout),
                new NamedTest("installs a limited interactive startup task", this::installSuccess),
                new NamedTest("redacts scheduler errors during installation", this::installFailure),
                new NamedTest("reports installation timeout", this::installTimeout),
                new NamedTest("removes existing and already-absent tasks", this::removeSuccess),
                new NamedTest("reports non-missing removal failures with redaction", this::removeFailure),
                new NamedTest("reports removal timeout", this::removeTimeout),
                new NamedTest("fails closed on non-Windows systems", this::nonWindows),
                new NamedTest("propagates command execution interruption", this::interruption));
    }

    private void windowsQuoting() {
        Assertions.equals("\"plain\"", TaskSchedulerCommandBuilder.windowsQuote("plain"),
                "ordinary values must still be quoted");
        Assertions.equals("\"C:\\Program Files\\IBC Manager.exe\"",
                TaskSchedulerCommandBuilder.windowsQuote("C:\\Program Files\\IBC Manager.exe"),
                "spaces must remain within quotes");
        Assertions.equals("\"C:\\path\\\\\"", TaskSchedulerCommandBuilder.windowsQuote("C:\\path\\"),
                "trailing backslashes must be doubled before the closing quote");
        Assertions.equals("\"a\\\"b\"", TaskSchedulerCommandBuilder.windowsQuote("a\"b"),
                "embedded quotes must be escaped");
        Assertions.equals("\"a\\\\\\\"b\"", TaskSchedulerCommandBuilder.windowsQuote("a\\\"b"),
                "backslashes immediately before quotes must be doubled plus one");
        Assertions.equals("\"\"", TaskSchedulerCommandBuilder.windowsQuote(""),
                "empty arguments must remain explicit");
    }

    private void invalidArguments() {
        for (String invalid : List.of("line\nfeed", "carriage\rreturn", "nul\0value",
                "unicode\u2028line", "unicode\u2029line")) {
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> TaskSchedulerCommandBuilder.windowsQuote(invalid),
                    "control character must be rejected");
        }
    }

    private void queryDeleteCommands() {
        Assertions.equals(List.of("schtasks.exe", "/Query", "/TN", "IBC Manager"),
                TaskSchedulerCommandBuilder.query("  IBC Manager  "), "query command must trim task name");
        Assertions.equals(List.of("schtasks.exe", "/Delete", "/TN", "IBC Manager", "/F"),
                TaskSchedulerCommandBuilder.delete("IBC Manager"), "delete command must force without prompting");
    }

    private void taskNames() {
        for (String invalid : List.of("", "   ", "bad\nname", "bad\rname", "bad\0name",
                "bad\u2028name", "bad\u2029name", "x".repeat(201))) {
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> TaskSchedulerCommandBuilder.query(invalid), "invalid task name must be rejected");
        }
        Assertions.throwsType(NullPointerException.class,
                () -> TaskSchedulerCommandBuilder.query(null), "null task name must be rejected");
    }

    private void createCommand() {
        Path executable = Path.of("C:\\Program Files\\IBC Manager\\IBC Manager.exe");
        List<String> command = TaskSchedulerCommandBuilder.createAtLogon("IBC Manager", executable,
                List.of("--autostart", "--data-dir", "C:\\Users\\Trader\\IBC Data"), Duration.ofMinutes(1));
        Assertions.equals("schtasks.exe", command.get(0), "schtasks executable must be used directly");
        Assertions.equals("/Create", command.get(1), "create action must be selected");
        String action = parseSingleWindowsArgument(command.get(command.indexOf("/TR") + 1));
        Assertions.contains(action,
                TaskSchedulerCommandBuilder.windowsQuote(executable.toAbsolutePath().normalize().toString()),
                "action must quote the normalized executable path");
        Assertions.contains(action, "\"--data-dir\" \"C:\\Users\\Trader\\IBC Data\"",
                "action must quote each argument independently");
        Assertions.isTrue(command.get(command.indexOf("/TR") + 1).startsWith("\"\\\""),
                "the /TR argument must escape its interior quotes so ProcessBuilder cannot split it");
        Assertions.equals("0001:00", command.get(command.indexOf("/DELAY") + 1),
                "one-minute delay must use schtasks mmmm:ss format");
        Assertions.isTrue(command.contains("/IT"), "GUI task must be interactive-only");
        Assertions.equals("LIMITED", command.get(command.indexOf("/RL") + 1),
                "startup task must not request elevation");
        Assertions.equals("/F", command.get(command.size() - 1), "installation must update an existing task safely");
    }

    private void taskActionRoundTrip() {
        Path executable = Path.of("C:\\Program Files\\Java\\bin\\javaw.exe");
        for (List<String> arguments : List.of(
                List.<String>of(),
                List.of("--autostart"),
                List.of("-jar", "C:\\Users\\Trader\\IBC Manager\\IBC-Manager.jar", "--autostart"),
                List.of("--data-dir", "C:\\Users\\Trader\\Trailing Backslash\\"))) {
            List<String> command = TaskSchedulerCommandBuilder.createAtLogon(
                    "IBC Manager", executable, arguments, Duration.ZERO);
            String onTheWire = appendWindowsArgument(command.get(command.indexOf("/TR") + 1));
            List<String> parsed = parseWindowsCommandLine(onTheWire);
            Assertions.equals(1, parsed.size(),
                    "schtasks must receive exactly one /TR argument for " + arguments);

            StringBuilder expected = new StringBuilder(
                    TaskSchedulerCommandBuilder.windowsQuote(executable.toAbsolutePath().normalize().toString()));
            for (String argument : arguments) {
                expected.append(' ').append(TaskSchedulerCommandBuilder.windowsQuote(argument));
            }
            Assertions.equals(expected.toString(), parsed.get(0),
                    "the parsed /TR argument must equal the intended action for " + arguments);
        }
    }

    /** Models how java.lang.ProcessImpl appends one argument to a Windows child command line. */
    private static String appendWindowsArgument(String argument) {
        int last = argument.length() - 1;
        boolean alreadyQuoted = last >= 1 && argument.charAt(0) == '"' && argument.charAt(last) == '"';
        if (alreadyQuoted) return argument;
        if (argument.indexOf(' ') < 0 && argument.indexOf('\t') < 0) return argument;
        return '"' + argument + (argument.endsWith("\\") ? "\\" : "") + '"';
    }

    /** Models the Microsoft C runtime argv rules that schtasks.exe uses. */
    private static List<String> parseWindowsCommandLine(String commandLine) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean started = false;
        int backslashes = 0;
        for (int index = 0; index < commandLine.length(); index++) {
            char character = commandLine.charAt(index);
            if (character == '\\') {
                backslashes++;
                continue;
            }
            if (character == '"') {
                current.append("\\".repeat(backslashes / 2));
                if (backslashes % 2 == 1) {
                    current.append('"');
                } else {
                    inQuotes = !inQuotes;
                }
                backslashes = 0;
                started = true;
                continue;
            }
            current.append("\\".repeat(backslashes));
            backslashes = 0;
            if ((character == ' ' || character == '\t') && !inQuotes) {
                if (started) arguments.add(current.toString());
                current.setLength(0);
                started = false;
                continue;
            }
            current.append(character);
            started = true;
        }
        current.append("\\".repeat(backslashes));
        if (started || current.length() > 0) arguments.add(current.toString());
        return List.copyOf(arguments);
    }

    private static String parseSingleWindowsArgument(String argument) {
        List<String> parsed = parseWindowsCommandLine(appendWindowsArgument(argument));
        Assertions.equals(1, parsed.size(), "expected exactly one parsed argument");
        return parsed.get(0);
    }

    private void zeroDelay() {
        List<String> command = TaskSchedulerCommandBuilder.createAtLogon("IBC Manager", Path.of("manager.exe"),
                List.of("--autostart"), Duration.ZERO);
        Assertions.isFalse(command.contains("/DELAY"), "zero delay must omit the switch");
        Assertions.isTrue(command.contains("/IT"), "immediate GUI task must still run interactively");
    }

    private void delayValidation() {
        for (Duration invalid : List.of(Duration.ofSeconds(-1), Duration.ofNanos(1),
                Duration.ofSeconds(9_999L * 60L + 60L))) {
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> TaskSchedulerCommandBuilder.createAtLogon("IBC", Path.of("manager.exe"), List.of(), invalid),
                    "invalid delay must be rejected: " + invalid);
        }
        Assertions.throwsType(NullPointerException.class,
                () -> TaskSchedulerCommandBuilder.createAtLogon("IBC", Path.of("manager.exe"), List.of(), null),
                "null delay must be rejected");
        List<String> maximum = TaskSchedulerCommandBuilder.createAtLogon("IBC", Path.of("manager.exe"), List.of(),
                Duration.ofSeconds(9_999L * 60L + 59L));
        Assertions.equals("9999:59", maximum.get(maximum.indexOf("/DELAY") + 1),
                "maximum documented delay format must be supported");
    }

    private void availability() {
        Assertions.isTrue(new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, new FakeExecutor()).isAvailable(),
                "service must be available on Windows");
        Assertions.isFalse(new WindowsTaskSchedulerService(OperatingSystem.LINUX, new FakeExecutor()).isAvailable(),
                "service must be unavailable on Linux");
        Assertions.isFalse(new WindowsTaskSchedulerService(OperatingSystem.MAC, new FakeExecutor()).isAvailable(),
                "service must be unavailable on macOS");
    }

    private void queryService() throws Exception {
        FakeExecutor executor = new FakeExecutor(
                new CommandResult(0, "TaskName: IBC Manager", "", false),
                new CommandResult(1, "", "ERROR: cannot find the task", false));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        Assertions.isTrue(service.isInstalled("IBC Manager"), "zero exit status must report installed");
        Assertions.isFalse(service.isInstalled("IBC Manager"), "nonzero query must report absent");
        Assertions.equals(List.of("schtasks.exe", "/Query", "/TN", "IBC Manager"),
                executor.calls.get(0).command(), "query must use exact task name");
        Assertions.equals("", executor.calls.get(0).stdin(), "scheduler commands need no stdin");
        Assertions.equals(Duration.ofSeconds(30), executor.calls.get(0).timeout(), "query timeout must be bounded");
    }

    private void queryTimeout() {
        FakeExecutor executor = new FakeExecutor(new CommandResult(-1, "", "", true));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        IOException error = Assertions.throwsType(IOException.class,
                () -> service.isInstalled("IBC Manager"), "query timeout must throw");
        Assertions.contains(error.getMessage(), "timed out", "timeout error must be explicit");
    }

    private void installSuccess() throws Exception {
        FakeExecutor executor = new FakeExecutor(new CommandResult(0, "SUCCESS", "", false));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        service.installAtLogon("IBC Manager", Path.of("C:\\Apps\\Manager.exe"), List.of("--autostart"),
                Duration.ofSeconds(45));
        Call call = executor.calls.get(0);
        Assertions.isTrue(call.command().contains("/Create"), "install must create the task");
        Assertions.equals("0000:45", call.command().get(call.command().indexOf("/DELAY") + 1),
                "45-second delay must not be rounded to a minute");
        Assertions.equals("", call.stdin(), "install must not send credentials through stdin");
        Assertions.notContains(String.join(" ", call.command()), "/PW:", "startup task must contain no IBC password switch");
    }

    private void installFailure() {
        FakeExecutor executor = new FakeExecutor(new CommandResult(5, "", "StartIBC /PW:TaskSecret denied", false));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        IOException error = Assertions.throwsType(IOException.class,
                () -> service.installAtLogon("IBC Manager", Path.of("manager.exe"), List.of(), Duration.ZERO),
                "failed task creation must throw");
        Assertions.notContains(error.getMessage(), "TaskSecret", "scheduler error must redact password-like values");
        Assertions.contains(error.getMessage(), "[REDACTED]", "redaction must remain visible");
    }

    private void installTimeout() {
        FakeExecutor executor = new FakeExecutor(new CommandResult(-1, "", "", true));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        IOException error = Assertions.throwsType(IOException.class,
                () -> service.installAtLogon("IBC", Path.of("manager.exe"), List.of(), Duration.ZERO),
                "installation timeout must throw");
        Assertions.contains(error.getMessage(), "timed out", "timeout error must be explicit");
    }

    private void removeSuccess() throws Exception {
        FakeExecutor executor = new FakeExecutor(
                new CommandResult(0, "SUCCESS", "", false),
                new CommandResult(1, "", "ERROR: The system cannot find the file specified.", false));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        service.remove("IBC Manager");
        service.remove("IBC Manager");
        Assertions.equals(List.of("schtasks.exe", "/Delete", "/TN", "IBC Manager", "/F"),
                executor.calls.get(0).command(), "remove must be noninteractive");
    }

    private void removeFailure() {
        FakeExecutor executor = new FakeExecutor(new CommandResult(5, "", "StartIBC /PW:RemoveSecret denied", false));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        IOException error = Assertions.throwsType(IOException.class,
                () -> service.remove("IBC Manager"), "unexpected removal errors must throw");
        Assertions.notContains(error.getMessage(), "RemoveSecret", "removal error must redact password-like values");
        Assertions.contains(error.getMessage(), "could not remove", "error must identify the failed operation");
    }

    private void removeTimeout() {
        FakeExecutor executor = new FakeExecutor(new CommandResult(-1, "", "", true));
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        IOException error = Assertions.throwsType(IOException.class,
                () -> service.remove("IBC"), "removal timeout must throw");
        Assertions.contains(error.getMessage(), "timed out", "timeout error must be explicit");
    }

    private void nonWindows() {
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.LINUX, new FakeExecutor());
        Assertions.throwsType(IOException.class, () -> service.isInstalled("IBC"), "query must fail closed off Windows");
        Assertions.throwsType(IOException.class,
                () -> service.installAtLogon("IBC", Path.of("manager"), List.of(), Duration.ZERO),
                "install must fail closed off Windows");
        Assertions.throwsType(IOException.class, () -> service.remove("IBC"), "remove must fail closed off Windows");
    }

    private void interruption() {
        FakeExecutor executor = new FakeExecutor();
        executor.interrupt = true;
        WindowsTaskSchedulerService service = new WindowsTaskSchedulerService(OperatingSystem.WINDOWS, executor);
        Assertions.throwsType(InterruptedException.class, () -> service.isInstalled("IBC"),
                "interruption must propagate to caller");
    }

    private record Call(List<String> command, String stdin, Duration timeout) {
        private Call {
            command = List.copyOf(command);
        }
    }

    private static final class FakeExecutor implements CommandExecutor {
        private final Deque<CommandResult> results = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();
        private boolean interrupt;

        private FakeExecutor(CommandResult... values) {
            results.addAll(List.of(values));
        }

        @Override
        public CommandResult execute(List<String> command, String stdin, Duration timeout)
                throws IOException, InterruptedException {
            calls.add(new Call(command, stdin, timeout));
            if (interrupt) throw new InterruptedException("test interruption");
            if (results.isEmpty()) return new CommandResult(0, "", "", false);
            return results.removeFirst();
        }
    }
}
