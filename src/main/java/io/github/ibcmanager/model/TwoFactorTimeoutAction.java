package io.github.ibcmanager.model;

public enum TwoFactorTimeoutAction {
    EXIT("Exit", "exit"),
    RESTART("Restart login", "restart");

    private final String displayName;
    private final String ibcValue;

    TwoFactorTimeoutAction(String displayName, String ibcValue) {
        this.displayName = displayName;
        this.ibcValue = ibcValue;
    }

    public String ibcValue() {
        return ibcValue;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
