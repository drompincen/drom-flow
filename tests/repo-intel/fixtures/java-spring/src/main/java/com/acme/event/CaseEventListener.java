package com.acme.event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CaseEventListener {

    @KafkaListener(topics = "case.updated")
    public void onCaseUpdated(CaseUpdatedEvent event) {
        handle(event);
    }

    private void handle(CaseUpdatedEvent event) {
        if (event != null) {
            Long id = event.caseId();
        }
    }
}
