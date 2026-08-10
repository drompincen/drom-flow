package com.acme.event;

public record CaseUpdatedEvent(Long caseId, String title) {
}
