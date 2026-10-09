package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
public interface RecoveryDiagnosticCapture {
    Path create(Profile profile, ProfileStatus status, List<String> liveRuntimeLog) throws IOException;

    static RecoveryDiagnosticCapture noop() {
        return (profile, status, liveRuntimeLog) -> null;
    }
}
