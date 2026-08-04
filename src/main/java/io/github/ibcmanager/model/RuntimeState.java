package io.github.ibcmanager.model;

public enum RuntimeState {
    STOPPED,
    VALIDATING,
    STARTING,
    RESTARTING,
    WAITING_FOR_LOGIN,
    WAITING_FOR_SECOND_FACTOR,
    RUNNING,
    API_LISTENER_DETECTED,
    PAUSING,
    PAUSED,
    STOPPING,
    ERROR,
    UNKNOWN
}
