package io.github.ibcmanager.app;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS,
    LINUX,
    MAC,
    OTHER;

    public static OperatingSystem current() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("mac") || value.contains("darwin")) return MAC;
        if (value.contains("win")) return WINDOWS;
        if (value.contains("nux") || value.contains("nix") || value.contains("aix")) return LINUX;
        return OTHER;
    }
}
