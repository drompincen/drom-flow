package com.acme.model;

public enum CaseStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
