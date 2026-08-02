package io.github.ibcmanager.model;

public enum RuntimeState {
    STOPPED,
    VALIDATING,
    STARTING,
    WAITING_FOR_LOGIN,
    WAITING_FOR_SECOND_FACTOR,
    RUNNING,
    API_SOCKET_OPEN,
    PAUSED,
    STOPPING,
    ERROR,
    UNKNOWN
}
