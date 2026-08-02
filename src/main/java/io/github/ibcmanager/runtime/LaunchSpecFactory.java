package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.Profile;

import java.io.IOException;
import java.nio.file.Path;

public interface LaunchSpecFactory {
    LaunchSpec create(Profile profile, Path runtimeConfig) throws IOException;
}
