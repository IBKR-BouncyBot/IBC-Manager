package io.github.ibcmanager.model;

public enum TargetType {
    GATEWAY("IB Gateway", "/Gateway"),
    TWS("Unsupported legacy TWS profile", "");

    private final String displayName;
    private final String launcherSwitch;

    TargetType(String displayName, String launcherSwitch) {
        this.displayName = displayName;
        this.launcherSwitch = launcherSwitch;
    }

    public String displayName() {
        return displayName;
    }

    public String launcherSwitch() {
        return launcherSwitch;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
