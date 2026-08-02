package io.github.ibcmanager.runtime;

public record IbcCommandResult(boolean success, String response) {
    public IbcCommandResult {
        response = response == null ? "" : response;
    }
}
