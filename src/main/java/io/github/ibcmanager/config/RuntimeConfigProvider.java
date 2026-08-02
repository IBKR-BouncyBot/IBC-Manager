package io.github.ibcmanager.config;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.SecureChars;

import java.io.IOException;

public interface RuntimeConfigProvider {
    RuntimeConfigLease create(Profile profile, SecureChars password) throws IOException;
    void cleanStale(Profile profile) throws IOException;
}
