package io.github.ibcmanager.runtime;

/** Meaning of the command server's complete response, not merely its first line. */
public enum CommandDisposition {
    COMPLETED,
    ACCEPTED,
    REJECTED,
    UNKNOWN
}
