package io.github.ibcmanager.runtime;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Executes the maintained exit dispatcher with inert labels; never launches Gateway. */
public final class TokenExpiryWrapperTests implements TestSuite {
    private static final String SESSION = "230fixture";
    @Override public String name() { return "Token-expiry cold restart: actual Windows wrapper exit dispatcher"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("explicit expiry precedes descriptor discovery and retains existing retry branches", this::routing),
                new NamedTest("Windows expiry cold start consumes once and retains unrelated restart data", () -> nativeCases("expired", "expired-nonzero", "warm", "cold", "pause", "normal")),
                new NamedTest("Windows expiry refuses malformed conflicting or repeated recovery markers", () -> nativeCases("directory", "readonly", "pause-conflict", "repeated", "pending-directory")),
                new NamedTest("Windows failed fresh login cannot fall back to a stale descriptor", () -> nativeCases("pending", "twofa-retry", "display-retry", "acknowledged")));
    }
    private static String script() throws Exception {
        return Files.readString(Path.of("engine/resources/scripts/StartIBC.bat")).replace("\r\n", "\n");
    }
    private void routing() throws Exception {
        String s = script();
        int expiry = s.indexOf("echo Check for token-expiry cold restart");
        int retries = s.indexOf("echo Check for 2FA dialog timed out", expiry);
        int pending = s.indexOf("Fresh authentication did not complete after token-expiry recovery", retries);
        int discovery = s.indexOf("call :GetAutoRestartOption", retries);
        Assertions.isTrue(expiry > 0 && expiry < retries && retries < pending && pending < discovery,
                "expiry takes precedence; established 2FA retry policy precedes failed-fresh guard; descriptor scan is last");
        String branch = s.substring(expiry, retries);
        Assertions.contains(branch, "set AUTORESTART_OPTION=\n", "explicit expiry clears warm mode");
        Assertions.contains(branch, "set RESTART_NEEDED=\n", "no old restart decision survives");
        Assertions.notContains(branch, "call :GetAutoRestartOption", "fresh replacement cannot rediscover a saved token");
        Assertions.notContains(branch, "del /s", "no recursive removal of another profile's data");
        Assertions.notContains(branch, "COLDRESTART%IBC_SESSION_ID%", "generic cold-timer marker remains separate");
        Assertions.contains(s.substring(pending), "Check for pause only", "ordinary warm PAUSE path retained");
    }
    private void nativeCases(String... modes) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            System.out.println("    [PLATFORM LIMIT] Native CMD expiry dispatcher requires Windows: " + String.join(", ", modes));
            return;
        }
        for (String mode : modes) nativeCase(mode);
    }
    private void nativeCase(String mode) throws Exception {
        Path root = TestSupport.tempDirectory("expiry-cmd-" + mode);
        Process process = null;
        try {
            Path settings = Files.createDirectories(root.resolve("settings with spaces"));
            Path token = settings.resolve("TOKENEXPIRED" + SESSION);
            Path pending = settings.resolve("TOKENEXPIREDPENDING" + SESSION);
            Path pause = settings.resolve("PAUSE" + SESSION);
            Path unrelated = Files.createDirectories(settings.resolve("unrelated-session")).resolve("autorestart");
            Files.writeString(unrelated, "preserve-auth-fixture");
            boolean tokenMode = List.of("expired", "expired-nonzero", "directory", "readonly", "pause-conflict", "repeated").contains(mode);
            if (tokenMode) {
                if (mode.equals("directory")) Files.createDirectory(token);
                else Files.createFile(token);
            }
            if (List.of("pending", "twofa-retry", "display-retry", "repeated", "acknowledged").contains(mode)) Files.createFile(pending);
            if (mode.equals("pending-directory")) Files.createDirectory(pending);
            if (mode.equals("acknowledged")) Files.delete(pending); // Successful engine login acknowledgment.
            if (mode.equals("readonly")) Files.setAttribute(token, "dos:readonly", true);
            if (mode.equals("pause") || mode.equals("pause-conflict")) Files.createFile(pause);
            if (mode.equals("cold")) Files.createFile(settings.resolve("COLDRESTART" + SESSION));

            String dispatcher = script();
            dispatcher = dispatcher.substring(dispatcher.indexOf("echo Check for token-expiry cold restart"), dispatcher.indexOf("\n:NormalExit\n"));
            String warm = List.of("warm", "pause", "acknowledged").contains(mode) ? "yes" : "";
            int exit = mode.equals("twofa-retry") ? 1111 : mode.equals("display-retry") ? 1112 : mode.equals("expired-nonzero") ? 17 : 0;
            String batch = "@echo off\r\nsetlocal EnableExtensions EnableDelayedExpansion\r\n"
                    + "set \"TWS_SETTINGS_PATH=" + settings + "\"\r\n"
                    + "set IBC_SESSION_ID=\"" + SESSION + "\"\r\n"
                    + "set E_TOKEN_EXPIRY_RECOVERY=1009\r\nset E_2FA_DIALOG_TIMED_OUT=1111\r\n"
                    + "set E_LOGIN_DIALOG_DISPLAY_TIMEOUT=1112\r\nset TWOFA_TO_ACTION=RESTART\r\n"
                    + "set \"FIXTURE_WARM=" + warm + "\"\r\n"
                    + "set \"AUTORESTART_OPTION=" + (tokenMode ? "-Drestart=old-expired" : "") + "\"\r\n"
                    + "cmd /d /c exit " + exit + "\r\n"
                    + dispatcher.replace("\n", "\r\n")
                    + ":NormalExit\r\necho FIXTURE_NORMAL_EXIT\r\nexit /b 0\r\n"
                    + ":startIBC\r\necho FIXTURE_LAUNCH:!AUTORESTART_OPTION!\r\nexit /b 0\r\n"
                    + ":GetAutoRestartOption\r\necho FIXTURE_DESCRIPTOR_LOOKUP\r\nset AUTORESTART_OPTION=\r\nset RESTART_NEEDED=\r\n"
                    + "if defined FIXTURE_WARM (\r\nset AUTORESTART_OPTION=-Drestart=valid\r\nset RESTART_NEEDED=yes\r\n)\r\ngoto :EOF\r\n"
                    + ":err\r\necho FIXTURE_ERROR:!ERROR_MESSAGE!\r\nexit /b !ERROR!\r\n";
            Path command = root.resolve("dispatch.bat"); Files.writeString(command, batch, StandardCharsets.UTF_8);
            process = new ProcessBuilder("cmd.exe", "/d", "/c", command.toString()).redirectErrorStream(true).start();
            Assertions.isTrue(process.waitFor(15, TimeUnit.SECONDS), mode + ": dispatcher terminates");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean failed = List.of("directory", "readonly", "pause-conflict", "repeated", "pending", "pending-directory").contains(mode);
            Assertions.equals(failed ? 1009 : 0, process.exitValue(), mode + ": exact dispatcher outcome: " + output);
            Assertions.equals("preserve-auth-fixture", Files.readString(unrelated), mode + ": unrelated descriptor untouched");
            if (failed) {
                Assertions.contains(output, "FIXTURE_ERROR:", mode + ": actionable failure");
                Assertions.notContains(output, "FIXTURE_LAUNCH:", mode + ": failure cannot relaunch");
                Assertions.notContains(output, "FIXTURE_DESCRIPTOR_LOOKUP", mode + ": failure cannot reuse stale token");
            } else if (List.of("expired", "expired-nonzero", "cold", "twofa-retry", "display-retry").contains(mode)) {
                Assertions.contains(output, "FIXTURE_LAUNCH:\r\n", mode + ": child starts without restart token");
                if (!mode.equals("cold")) Assertions.notContains(output, "FIXTURE_DESCRIPTOR_LOOKUP", mode + ": no saved token discovery");
                if (mode.startsWith("expired")) {
                    Assertions.isFalse(Files.exists(token), "expiry request consumed once");
                    Assertions.isTrue(Files.isRegularFile(pending), "pending remains until actual login");
                }
            } else if (mode.equals("pause")) {
                Assertions.contains(output, "IBC is paused", "normal PAUSE confirmed");
                Assertions.notContains(output, "FIXTURE_LAUNCH:", "pause never relaunches");
            } else if (mode.equals("normal")) Assertions.contains(output, "FIXTURE_NORMAL_EXIT", "ordinary stop retained");
            else Assertions.contains(output, "FIXTURE_LAUNCH:-Drestart=valid", mode + ": normal warm restart retained");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Path token = root.resolve("settings with spaces/TOKENEXPIRED" + SESSION);
            if (Files.isRegularFile(token)) Files.setAttribute(token, "dos:readonly", false);
            TestSupport.deleteTree(root);
        }
    }
}
