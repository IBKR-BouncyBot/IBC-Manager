package io.github.ibcmanager.security;

public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    public CommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
