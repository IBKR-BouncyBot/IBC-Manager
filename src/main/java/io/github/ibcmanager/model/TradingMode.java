package io.github.ibcmanager.model;

public enum TradingMode {
    LIVE("Live", "live"),
    PAPER("Paper", "paper");

    private final String displayName;
    private final String ibcValue;

    TradingMode(String displayName, String ibcValue) {
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
