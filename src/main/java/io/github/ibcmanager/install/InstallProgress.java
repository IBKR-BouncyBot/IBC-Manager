package io.github.ibcmanager.install;

@FunctionalInterface
public interface InstallProgress {
    void update(String message, long completedBytes, long totalBytes);

    static InstallProgress none() {
        return (message, completedBytes, totalBytes) -> { };
    }
}
