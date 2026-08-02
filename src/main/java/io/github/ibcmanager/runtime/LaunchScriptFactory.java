package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LaunchScriptFactory implements LaunchSpecFactory {
    private final AppPaths paths;
    private final OperatingSystem operatingSystem;

    public LaunchScriptFactory(AppPaths paths) {
        this(paths, OperatingSystem.current());
    }

    public LaunchScriptFactory(AppPaths paths, OperatingSystem operatingSystem) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    }

    public LaunchSpec create(Profile profile, Path runtimeConfig) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        if (operatingSystem != OperatingSystem.WINDOWS) {
            throw new IOException("IBC Manager 1.0 launches IBC only on Windows");
        }
        Path runtimeDirectory = paths.runtimeDirectory(profile.id());
        FilePermissionHardener.hardenDirectory(runtimeDirectory);
        Path script = runtimeDirectory.resolve("launch.cmd");

        List<String> arguments = new ArrayList<>();
        arguments.add(profile.twsMajorVersion());
        if (profile.targetType() == TargetType.GATEWAY) arguments.add("/Gateway");
        arguments.add("/TwsPath:" + profile.twsPath());
        arguments.add("/TwsSettingsPath:" + profile.twsSettingsPath());
        arguments.add("/IbcPath:" + profile.ibcPath());
        arguments.add("/Config:" + runtimeConfig);
        arguments.add("/Mode:" + profile.tradingMode().ibcValue());
        arguments.add("/On2FATimeout:" + profile.twoFactorTimeoutAction().ibcValue());

        Path officialLauncher = profile.ibcPath().resolve("scripts").resolve("StartIBC.bat");
        StringBuilder content = new StringBuilder();
        content.append("@echo off\r\n");
        content.append("chcp 65001 >nul\r\n");
        content.append("setlocal DisableDelayedExpansion\r\n");
        content.append("call ").append(quote(officialLauncher.toString()));
        for (String argument : arguments) content.append(' ').append(quote(argument));
        content.append("\r\n");
        content.append("set \"IBC_MANAGER_EXIT=%ERRORLEVEL%\"\r\n");
        content.append("exit /b %IBC_MANAGER_EXIT%\r\n");
        AtomicFileWriter.write(script, content.toString().getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(script);

        String commandLine = "call " + quote(script.toString());
        return new LaunchSpec(
                List.of("cmd.exe", "/d", "/s", "/c", commandLine),
                runtimeDirectory,
                Map.of(),
                script,
                commandLine);
    }

    static String quote(String value) {
        Objects.requireNonNull(value, "value");
        if (value.indexOf('"') >= 0 || TextSafety.containsConfigBreakingControl(value)
                || value.indexOf('%') >= 0 || value.indexOf('!') >= 0) {
            throw new IllegalArgumentException("Value cannot be represented safely in an IBC launch script");
        }
        return '"' + value + '"';
    }
}
