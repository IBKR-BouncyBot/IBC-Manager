package io.github.ibcmanager.engine;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.runtime.IbcLogStateParser;
import io.github.ibcmanager.runtime.LaunchScriptFactory;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarFile;

public final class EmbeddedEngineTests implements TestSuite {
    @Override public String name() { return "Integrated source-built engine and structured lifecycle"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("bundled engine contains actual compiled handlers and no IBKR binaries", this::payload),
                new NamedTest("engine cache is reused only when every byte matches", this::reuse),
                new NamedTest("tampered engine JAR is rejected without overwrite", this::tamper),
                new NamedTest("missing engine helper is rejected", this::missing),
                new NamedTest("unexpected cache file is rejected", this::extra),
                new NamedTest("external IBC profile path never reaches launch", this::externalIgnored),
                new NamedTest("legacy TWS profile cannot launch", this::twsRejected),
                new NamedTest("engine events require completed login AND main window", this::events),
                new NamedTest("duplicate and stale engine events cannot restore readiness", this::staleEvents),
                new NamedTest("malformed events do not change state", this::badEvents),
                new NamedTest("replacement engine revokes earlier readiness", this::newGeneration),
                new NamedTest("retry phases cannot imply login success or revive an old engine", this::retryEvents));
    }

    private void payload() throws Exception {
        Path root = TestSupport.tempDirectory("engine-payload");
        try {
            EmbeddedEngine.verifyBundledPayload();
            Path engine = EmbeddedEngine.ensureUnder(root);
            Assertions.equals(Version.ENGINE_VERSION, Files.readString(engine.resolve("engine-version")).trim(), "revision");
            try (JarFile jar = new JarFile(engine.resolve("IBC.jar").toFile())) {
                for (String name : List.of("IbcGateway", "IbcTws", "EngineEvents", "GatewayEntrypoint",
                        "GatewayLoginFrameHandler", "SecondFactorAuthenticationDialogHandler",
                        "GatewayMainWindowFrameHandler", "CommandDispatcher", "RestartTask",
                        "SecondFactorRetry", "SecondFactorRetryTarget")) {
                    Assertions.isTrue(jar.getEntry("ibcalpha/ibc/" + name + ".class") != null, "real engine class " + name);
                }
                Assertions.equals(null, jar.getEntry("ibgateway/GWClient.class"), "IBKR is not redistributed");
                Assertions.equals(null, jar.getEntry("jclient/LoginFrame.class"), "no proprietary stub in engine");
            }
            Assertions.fileExists(engine.resolve("scripts/StartIBC.bat"), "bundled wrapper");
            Assertions.fileExists(engine.resolve("scripts/getExtraJavaOptions.ps1"), "newer Gateway helper");
        } finally { TestSupport.deleteTree(root); }
    }

    private void reuse() throws Exception {
        Path root = TestSupport.tempDirectory("engine-reuse");
        try {
            Path first = EmbeddedEngine.ensureUnder(root);
            var modified = Files.getLastModifiedTime(first.resolve("IBC.jar"));
            Assertions.equals(first, EmbeddedEngine.ensureUnder(root), "stable cache");
            Assertions.equals(modified, Files.getLastModifiedTime(first.resolve("IBC.jar")), "no repeated rewrite");
        } finally { TestSupport.deleteTree(root); }
    }
    private void tamper() throws Exception {
        Path root = TestSupport.tempDirectory("engine-tamper");
        try {
            Path file = EmbeddedEngine.ensureUnder(root).resolve("IBC.jar");
            Files.writeString(file, "external-engine");
            Assertions.throwsType(IOException.class, () -> EmbeddedEngine.ensureUnder(root), "external replacement refused");
            Assertions.equals("external-engine", Files.readString(file), "failed validation never overwrites files");
        } finally { TestSupport.deleteTree(root); }
    }
    private void missing() throws Exception {
        Path root = TestSupport.tempDirectory("engine-missing");
        try {
            Files.delete(EmbeddedEngine.ensureUnder(root).resolve("scripts/getExtraJavaOptions.ps1"));
            Assertions.throwsType(IOException.class, () -> EmbeddedEngine.ensureUnder(root), "incomplete cache refused");
        } finally { TestSupport.deleteTree(root); }
    }
    private void extra() throws Exception {
        Path root = TestSupport.tempDirectory("engine-extra");
        try {
            Files.writeString(EmbeddedEngine.ensureUnder(root).resolve("unexpected.jar"), "extra");
            Assertions.throwsType(IOException.class, () -> EmbeddedEngine.ensureUnder(root), "unexpected payload refused");
        } finally { TestSupport.deleteTree(root); }
    }
    private void externalIgnored() throws Exception {
        Path root = TestSupport.tempDirectory("engine-launch");
        try {
            var profile = TestSupport.validProfile(root).toBuilder().ibcPath(root.resolve("NONEXISTENT_EXTERNAL_IBC")).build();
            Path config = profile.twsSettingsPath().resolve("runtime.ini");
            var launch = new LaunchScriptFactory(new AppPaths(root.resolve("data")), OperatingSystem.WINDOWS).create(profile, config);
            String script = Files.readString(launch.launchScript());
            Assertions.contains(script, ".ibc-manager-engine", "integrated path only");
            Assertions.notContains(script, "NONEXISTENT_EXTERNAL_IBC", "legacy field inert");
            Assertions.contains(script, "/Gateway", "Gateway switch mandatory");
            Assertions.notContains(script, "/JavaPath:", "blank override delegates Gateway runtime discovery");
        } finally { TestSupport.deleteTree(root); }
    }
    private void twsRejected() throws Exception {
        Path root = TestSupport.tempDirectory("engine-tws-reject");
        try {
            var profile = TestSupport.validProfile(root).toBuilder().targetType(TargetType.TWS).build();
            Assertions.throwsType(IOException.class,
                    () -> new LaunchScriptFactory(new AppPaths(root.resolve("data")), OperatingSystem.WINDOWS)
                            .create(profile, root.resolve("runtime.ini")), "TWS is not launchable");
        } finally { TestSupport.deleteTree(root); }
    }
    private static String event(String generation, int sequence, String name) {
        return "IBC_MANAGER_EVENT|1|" + generation + "|" + sequence + "|" + name;
    }
    private void events() {
        IbcLogStateParser parser = new IbcLogStateParser();
        String id = UUID.randomUUID().toString();
        parser.accept(event(id, 1, "ENGINE_STARTED"));
        parser.accept(event(id, 2, "GATEWAY_STARTING"));
        Assertions.isTrue(parser.applicationLaunchObserved(), "watchdog milestone");
        parser.accept(event(id, 3, "LOGIN_LOGGED_IN"));
        Assertions.isTrue(parser.loginCompleted(), "login event consumed");
        Assertions.isFalse(parser.mainWindowReady(), "no command on login alone");
        parser.accept(event(id, 4, "MAIN_WINDOW_READY"));
        Assertions.isTrue(parser.mainWindowReady(), "both gates present");
    }
    private void staleEvents() {
        var parser = new IbcLogStateParser(); String id = UUID.randomUUID().toString();
        parser.accept(event(id, 1, "ENGINE_STARTED"));
        parser.accept(event(id, 2, "LOGIN_LOGGED_IN"));
        parser.accept(event(id, 2, "MAIN_WINDOW_READY"));
        Assertions.isFalse(parser.mainWindowReady(), "duplicate sequence ignored");
        parser.accept("Starting IBC with this command:");
        parser.accept(event(id, 3, "LOGIN_LOGGED_IN"));
        parser.accept(event(id, 1, "ENGINE_STARTED"));
        Assertions.isFalse(parser.loginCompleted(), "old generation cannot regain readiness");
    }
    private void badEvents() {
        var parser = new IbcLogStateParser();
        for (String line : List.of("IBC_MANAGER_EVENT|2|abc|1|ENGINE_STARTED", "IBC_MANAGER_EVENT|1|abc|1|ENGINE_STARTED",
                event(UUID.randomUUID().toString(), -1, "ENGINE_STARTED"), event(UUID.randomUUID().toString(), 1, "SECRET=bad"))) {
            Assertions.isTrue(parser.accept(line).isEmpty(), "invalid event ignored");
        }
        Assertions.isFalse(parser.loginCompleted(), "unchanged state");
    }
    private void newGeneration() {
        var parser = new IbcLogStateParser(); String a = UUID.randomUUID().toString(); String b = UUID.randomUUID().toString();
        parser.accept(event(a, 1, "ENGINE_STARTED")); parser.accept(event(a, 2, "LOGIN_LOGGED_IN"));
        parser.accept("Starting IBC with this command:");
        parser.accept(event(b, 1, "ENGINE_STARTED")); parser.accept(event(b, 2, "LOGIN_TWO_FA_IN_PROGRESS"));
        Assertions.isFalse(parser.loginCompleted(), "replacement must log in again");
        Assertions.equals(io.github.ibcmanager.model.RuntimeState.WAITING_FOR_SECOND_FACTOR,
                parser.latest().orElseThrow().state(), "native second factor event");
    }
    private void retryEvents() {
        var parser = new IbcLogStateParser(); String id = UUID.randomUUID().toString();
        parser.accept(event(id, 1, "ENGINE_STARTED"));
        parser.accept(event(id, 2, "LOGIN_TWO_FA_IN_PROGRESS"));
        parser.accept(event(id, 3, "SECOND_FACTOR_RETRY_ARMED"));
        Assertions.equals(io.github.ibcmanager.model.RuntimeState.WAITING_FOR_SECOND_FACTOR,
                parser.latest().orElseThrow().state(), "timer is a 2FA state, not a startup stall");
        parser.accept(event(id, 4, "SECOND_FACTOR_RETRY_DUE"));
        Assertions.isFalse(parser.loginCompleted(), "due does not imply login");
        parser.accept(event(id, 5, "SECOND_FACTOR_RETRY_BLOCKED"));
        Assertions.contains(parser.latest().orElseThrow().message(), "blocked", "failure is visible");
        parser.accept(event(id, 6, "LOGIN_LOGGED_IN"));
        parser.accept(event(id, 7, "SECOND_FACTOR_RETRY_ARMED"));
        parser.accept(event(id, 8, "SECOND_FACTOR_RETRY_BLOCKED"));
        Assertions.equals(io.github.ibcmanager.model.RuntimeState.RUNNING,
                parser.latest().orElseThrow().state(), "late retry event cannot revoke completed login");
        parser.accept("Starting IBC with this command:");
        Assertions.isTrue(parser.accept(event(id, 9, "SECOND_FACTOR_RETRY_DUE")).isEmpty(), "old generation rejected");
    }

}
