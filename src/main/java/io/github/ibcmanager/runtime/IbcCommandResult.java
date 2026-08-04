package io.github.ibcmanager.runtime;

import java.util.Objects;

public record IbcCommandResult(CommandDisposition disposition, String response) {
    public IbcCommandResult {
        disposition = Objects.requireNonNullElse(disposition, CommandDisposition.UNKNOWN);
        response = response == null ? "" : response;
    }

    public IbcCommandResult(boolean success, String response) {
        this(success ? CommandDisposition.COMPLETED : CommandDisposition.REJECTED, response);
    }

    public boolean success() {
        return disposition == CommandDisposition.COMPLETED
                || disposition == CommandDisposition.ACCEPTED;
    }

    public boolean completed() { return disposition == CommandDisposition.COMPLETED; }
    public boolean accepted() { return disposition == CommandDisposition.ACCEPTED; }
}
