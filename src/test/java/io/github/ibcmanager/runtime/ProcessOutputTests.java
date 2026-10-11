package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.SecretRedactor;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProcessOutputTests implements TestSuite {
    @Override public String name() { return "Merged stderr redaction, bounds and disk buffering"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("fragmented UTF-8 lines are redacted before either output sink", this::fragmentation),
                new NamedTest("CR LF and CRLF retain logical lines", this::lineEndings),
                new NamedTest("unterminated final stderr is redacted at EOF", this::finalLine),
                new NamedTest("oversized lines are fully omitted without secret fragments", this::oversized),
                new NamedTest("ordinary stack traces and engine events survive unchanged", this::ordinary),
                new NamedTest("inline error credentials redact idempotently", this::inlineSecrets),
                new NamedTest("combined stdout stderr reaches live and disk redacted with original exit code", this::realRelay),
                new NamedTest("all four Windows Gateway invocations preserve stderr", this::wrapperRouting));
    }
    private static String transform(String text, boolean fragmented) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream stream = new ByteArrayInputStream(input) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, fragmented ? Math.min(1, length) : length);
            }
        }) { RedactedProcessOutput.relay(stream, output::writeBytes); }
        return output.toString(StandardCharsets.UTF_8);
    }
    private void fragmentation() throws Exception {
        String text = "ordinary \u20ac \ud83d\udd10\nIbPassword=split-secret\nerror token=split-token, keep=yes\n";
        String expected = "ordinary \u20ac \ud83d\udd10\nIbPassword=[REDACTED]\nerror token=[REDACTED], keep=yes\n";
        Assertions.equals(expected, transform(text, true), "byte boundaries cannot expose a secret or break Unicode");
        Assertions.equals(expected, transform(text, false), "ordinary reads give the same output");
    }
    private void lineEndings() throws Exception {
        Assertions.equals("one\ntwo\n\nthree\nfour\n", transform("one\rtwo\r\n\rthree\nfour", true), "logical framing");
        Assertions.equals("", transform("", true), "empty input stays empty");
    }
    private void finalLine() throws Exception {
        Assertions.equals("IbPassword=[REDACTED]\n", transform("IbPassword=secret", true), "EOF completes redaction");
        Assertions.equals("\n", transform("\r\n", true), "one empty CRLF line");
    }
    private void oversized() throws Exception {
        String text = "prefix-secret-" + "x".repeat(RedactedProcessOutput.MAX_LINE_CHARACTERS)
                + "-tail-secret\r\nafter\n";
        Assertions.equals(RedactedProcessOutput.OMITTED_LINE + "\nafter\n", transform(text, false), "no prefix or tail is exposed");
        String boundary = "x".repeat(RedactedProcessOutput.MAX_LINE_CHARACTERS);
        Assertions.equals(boundary + "\n", transform(boundary, false), "exact safe limit preserved");
        Assertions.equals(RedactedProcessOutput.OMITTED_LINE + "\n", transform(boundary + "x", false), "overflow at EOF");
    }
    private void ordinary() throws Exception {
        String text = "java.awt.HeadlessException: desktop unavailable\n\tat package.Type.method(Type.java:12)\n"
                + "IBC_MANAGER_EVENT|1|00000000-0000-0000-0000-000000000001|3|GATEWAY_STARTING\n";
        Assertions.equals(text, transform(text, true), "trace and protocol content unchanged");
    }
    private void inlineSecrets() {
        String text = "warning -DIbPassword=alpha token=beta, sessionToken=gamma; api_key=delta "
                + "URL?password=epsilon&ok=yes password=\"with spaces\" user=visible";
        String safe = SecretRedactor.redact(text);
        for (String secret : List.of("alpha", "beta", "gamma", "delta", "epsilon", "with spaces")) {
            Assertions.notContains(safe, secret, "masked secret");
        }
        Assertions.contains(safe, "&ok=yes", "keep nonsecret context");
        Assertions.equals(safe, SecretRedactor.redact(safe), "repeated sink/diagnostic redaction is idempotent");
    }
    private void realRelay() throws Exception {
        Path root = TestSupport.tempDirectory("redacted-relay"); ManagedProcess process = null;
        Path release = root.resolve("release-signal"); Path log = root.resolve("logs/child.log");
        try {
            LaunchSpec spec = new LaunchSpec(TestSupport.javaCommand("stderr-redaction", release.toString()),
                    root, Map.of(), root.resolve("none"), "test");
            process = new DefaultProcessLauncher(Duration.ofHours(1)).launch(spec, log, "Password=header-secret\n");
            ManagedProcess observed = process;
            List<String> live = new ArrayList<>();
            Assertions.eventually(Duration.ofSeconds(8), () -> {
                live.addAll(observed.drainOutputLines());
                return live.stream().anyMatch("stderr-ready"::equals);
            }, "both streams are live before the disk interval");
            Assertions.isFalse(Files.exists(log), "no forced disk write for stderr");
            assertRedacted(String.join("\n", live));
            Files.writeString(release, "release");
            Assertions.isTrue(process.waitFor(Duration.ofSeconds(5)), "relay and child exit");
            Assertions.equals(23, process.exitCode().orElseThrow(), "original child exit code preserved");
            live.addAll(process.drainOutputLines());
            String disk = Files.readString(log); assertRedacted(disk); assertRedacted(String.join("\n", live));
            Assertions.contains(disk, "stdout-ready", "stdout retained");
            Assertions.contains(disk, "IllegalStateException", "stderr exception retained");
            Assertions.contains(disk, "at test.Gateway.main", "stack retained");
            Assertions.contains(disk, "IbPassword=[REDACTED]", "unterminated final stderr flushed safely");
        } finally {
            Files.writeString(release, "release");
            if (process != null && process.isAlive() && !process.waitFor(Duration.ofSeconds(5))) {
                new ProcessTreeTerminator().terminate(process, Duration.ofMillis(100), Duration.ofSeconds(3));
            }
            TestSupport.deleteTree(root);
        }
    }
    private static void assertRedacted(String text) {
        for (String secret : List.of("header-secret", "stderr-token-secret", "stderr-password-secret", "final-secret-without-newline")) {
            Assertions.notContains(text, secret, "never persisted or piped plaintext known secret");
        }
        Assertions.contains(text, "[REDACTED]", "redaction explicit");
    }
    private void wrapperRouting() throws Exception {
        String script = Files.readString(Path.of("engine/resources/scripts/StartIBC.bat"));
        List<String> invocations = script.lines().filter(line -> line.stripLeading().startsWith("\"%JAVA_PATH%\\java.exe\""))
                .filter(line -> line.contains("%ENTRY_POINT%")).toList();
        Assertions.equals(4, invocations.size(), "all credential/no-credential invocation branches checked");
        for (String line : invocations) {
            Assertions.isTrue(line.endsWith("2>&1"), "child stderr reaches the relay in every branch");
            Assertions.notContains(line, "2>NUL", "no suppressed error stream");
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        // On Windows execute the exact maintained Java invocation lines with an inert Java fixture.
        Path root = TestSupport.tempDirectory("cmd-stderr");
        try {
            List<String> command = TestSupport.javaCommand("stderr-only");
            String cp = command.get(command.indexOf("-classpath") + 1);
            for (int i = 0; i < invocations.size(); i++) {
                String content = "@echo off\r\nsetlocal DisableDelayedExpansion\r\n"
                        + "set \"JAVA_PATH=" + TestSupport.javaExecutable().getParent() + "\"\r\n"
                        + "set \"IBC_CLASSPATH=" + cp + "\"\r\n"
                        + "set \"ENTRY_POINT=io.github.ibcmanager.tests.SubprocessFixture\"\r\n"
                        + "set \"CONFIG=stderr-only\"\r\n"
                        + "set EXTRA_JAVA_OPTIONS=\r\nset JAVA_VM_OPTIONS=\r\nset AUTORESTART_OPTION=\r\nset MODE=\r\n"
                        + "set FIX_USER_ID=fixture\r\nset FIX_PASSWORD=fixture-only\r\n"
                        + "set IB_USER_ID=fixture\r\nset IB_PASSWORD=fixture-only\r\n"
                        + invocations.get(i) + "\r\nexit /b %ERRORLEVEL%\r\n";
                Path bat = root.resolve("branch-" + i + ".bat"); Files.writeString(bat, content);
                Process child = new ProcessBuilder("cmd.exe", "/d", "/c", bat.toString()).redirectErrorStream(true).start();
                try {
                    Assertions.isTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "CMD branch exits");
                    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    Assertions.equals(23, child.exitValue(), "redirection retains CMD errorlevel");
                    Assertions.contains(output, "synthetic-stderr-marker", "CMD does not discard stderr");
                } finally { if (child.isAlive()) child.destroyForcibly(); }
            }
        } finally { TestSupport.deleteTree(root); }
    }
}
