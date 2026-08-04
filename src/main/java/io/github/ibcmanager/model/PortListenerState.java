package io.github.ibcmanager.model;

/** Result of passively inspecting the local operating-system TCP listener table. */
public enum PortListenerState {
    LISTENING,
    NOT_LISTENING,
    UNKNOWN
}
