package org.jarvis.pccontrol.model;

public enum PcActionExecutionStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    REJECTED,
    FAILED;

    public boolean isSuccessful() {
        return this == SUCCESS || this == PARTIAL_SUCCESS;
    }
}
