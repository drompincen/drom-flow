package com.acme.api;

import com.acme.model.Case;
import com.acme.service.CaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping("/{id}")
    public Case getById(Long id) {
        return caseService.getCase(id);
    }

    @PostMapping("/")
    public Case create(String title) {
        return caseService.createCase(title);
    }

    @PutMapping("/{id}")
    public Case update(Long id, String title) {
        return caseService.updateCase(id, title);
    }
}
