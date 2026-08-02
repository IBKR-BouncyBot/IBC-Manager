package io.github.ibcmanager.model;

public enum CredentialMode {
    MANUAL("Enter password in IBKR login window"),
    ENCRYPTED("Store password encrypted for this Windows user"),
    EXISTING_CONFIG("Use credentials already present in the selected IBC config");

    private final String displayName;

    CredentialMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
