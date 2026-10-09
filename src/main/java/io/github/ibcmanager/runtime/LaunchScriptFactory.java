package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.engine.EmbeddedEngine;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.WindowsCommandSafety;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LaunchScriptFactory implements LaunchSpecFactory {
    private final AppPaths paths;
    private final OperatingSystem operatingSystem;
    private final IbcJavaRuntimeResolver javaRuntimeResolver;

    public LaunchScriptFactory(AppPaths paths) {
        this(paths, OperatingSystem.current(), new IbcJavaRuntimeResolver());
    }

    public LaunchScriptFactory(AppPaths paths, OperatingSystem operatingSystem) {
        this(paths, operatingSystem, new IbcJavaRuntimeResolver(operatingSystem,
                new io.github.ibcmanager.security.DefaultCommandExecutor(),
                new OfflineApplicationLayoutResolver()));
    }

    public LaunchScriptFactory(AppPaths paths, OperatingSystem operatingSystem,
            IbcJavaRuntimeResolver javaRuntimeResolver) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.javaRuntimeResolver = Objects.requireNonNull(javaRuntimeResolver, "javaRuntimeResolver");
    }

    public LaunchSpec create(Profile profile, Path runtimeConfig) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        if (operatingSystem != OperatingSystem.WINDOWS) {
            throw new IOException("IBC Manager supports Windows only");
        }
        if (profile.targetType() != TargetType.GATEWAY) {
            throw new IOException("Only IB Gateway profiles can be launched; legacy TWS profiles are retained for reference");
        }
        WindowsCommandSafety.requireSafeExternalArgument(profile.twsSettingsPath().toString());
        Path engineDirectory = EmbeddedEngine.ensureFor(profile);
        Optional<IbcJavaRuntimeResolver.ResolvedJava> resolvedJava = javaRuntimeResolver.resolve(profile);
        Path runtimeDirectory = paths.runtimeDirectory(profile.id());
        FilePermissionHardener.hardenDirectory(runtimeDirectory);
        Path script = runtimeDirectory.resolve("launch.cmd");

        List<LaunchArgument> arguments = new ArrayList<>();
        arguments.add(new LaunchArgument(profile.twsMajorVersion(), true));
        if (profile.targetType() == TargetType.GATEWAY) arguments.add(new LaunchArgument("/Gateway", true));
        arguments.add(new LaunchArgument("/TwsPath:" + profile.twsPath(), true));
        arguments.add(new LaunchArgument("/TwsSettingsPath:" + profile.twsSettingsPath(), true));
        arguments.add(new LaunchArgument("/IbcPath:" + engineDirectory, true));
        // StartIBC.bat expands CONFIG through ordinary CMD variable contexts. The runtime
        // configuration is therefore located below the already validated TWS settings path and
        // must satisfy the same strict metacharacter policy as every other external argument.
        arguments.add(new LaunchArgument("/Config:" + runtimeConfig, true));
        // When no override is configured, leave Java discovery to the official StartIBC.bat.
        // Newer IBKR installers use a different bundled-runtime layout that only the matching
        // launcher script is authoritative for.
        resolvedJava.ifPresent(java -> arguments.add(
                new LaunchArgument("/JavaPath:" + java.directory(), true)));
        arguments.add(new LaunchArgument("/Mode:" + profile.tradingMode().ibcValue(), true));
        arguments.add(new LaunchArgument("/On2FATimeout:" + profile.twoFactorTimeoutAction().ibcValue(), true));

        Path officialLauncher = engineDirectory.resolve("scripts").resolve("StartIBC.bat");
        StringBuilder content = new StringBuilder();
        content.append("@echo off\r\n");
        content.append("chcp 65001 >nul\r\n");
        content.append("setlocal DisableDelayedExpansion\r\n");
        content.append("call ").append(quoteExternal(officialLauncher.toString()));
        for (LaunchArgument argument : arguments) {
            content.append(' ').append(argument.external() ? quoteExternal(argument.value())
                    : quoteInternal(argument.value()));
        }
        content.append("\r\n");
        content.append("set \"IBC_MANAGER_EXIT=%ERRORLEVEL%\"\r\n");
        content.append("exit /b %IBC_MANAGER_EXIT%\r\n");
        AtomicFileWriter.write(script, content.toString().getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(script);

        String commandLine = "call " + quoteInternal(script.toString());
        return new LaunchSpec(
                List.of("cmd.exe", "/d", "/s", "/c", commandLine),
                runtimeDirectory,
                Map.of(),
                script,
                commandLine,
                runtimeConfig);
    }

    static String quote(String value) {
        return quoteExternal(value);
    }

    static String quoteExternal(String value) {
        return quoteChecked(value, true);
    }

    static String quoteInternal(String value) {
        return quoteChecked(value, false);
    }

    private static String quoteChecked(String value, boolean vulnerableOfficialExpansion) {
        Objects.requireNonNull(value, "value");
        if (vulnerableOfficialExpansion) WindowsCommandSafety.requireSafeExternalArgument(value);
        else WindowsCommandSafety.requireSafeInternalArgument(value);
        return '"' + value + '"';
    }

    private record LaunchArgument(String value, boolean external) {
        private LaunchArgument {
            Objects.requireNonNull(value, "value");
        }
    }
}
