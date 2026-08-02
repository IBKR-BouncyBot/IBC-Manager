package io.github.ibcmanager.ui;

import io.github.ibcmanager.model.Profile;

import java.util.Arrays;
import java.util.Objects;

public final class ProfileEditResult implements AutoCloseable {
    private final Profile profile;
    private final char[] password;

    public ProfileEditResult(Profile profile, char[] password) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.password = password == null ? new char[0] : Arrays.copyOf(password, password.length);
    }

    public Profile profile() {
        return profile;
    }

    public char[] passwordCopy() {
        return Arrays.copyOf(password, password.length);
    }

    public boolean hasPassword() {
        return password.length > 0;
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
