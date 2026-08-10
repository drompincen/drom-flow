package com.acme.service;

import com.acme.model.Case;
import com.acme.repo.CaseRepository;
import org.junit.jupiter.api.Test;

public class CaseServiceImplTest {

    @Test
    public void testGetCase() {
        CaseRepository repo = new CaseRepository();
        CaseServiceImpl service = new CaseServiceImpl(repo);
        Case result = service.getCase(1L);
    }

    @Test
    public void testCreateCase() {
        CaseRepository repo = new CaseRepository();
        CaseServiceImpl service = new CaseServiceImpl(repo);
        Case created = service.createCase("demo");
    }
}
