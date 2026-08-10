package com.acme.service;

import com.acme.model.*;
import com.acme.repo.CaseRepository;
// import com.acme.other.Helper;
import com.acme.util.Helper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CaseServiceImpl implements CaseService {

    private final CaseRepository caseRepository;

    public CaseServiceImpl(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Override
    public Case getCase(Long id) {
        // Resolves to CaseRepository.findById via declared field type — not CaseAuditRepository
        Case found = caseRepository.findById(id);
        String hint = Helper.help();
        // Helper.help() from other package — commented out, must not appear in graph
        // com.acme.other.Helper.help();
        List<Case> buffer = new ArrayList<>();
        if (found != null) {
            buffer.add(found);
            // Local List.get — must NOT resolve to any repository method named get
            Case first = buffer.get(0);
            first.setTitle(Helper.format(hint));
        }
        String decoy = "import com.acme.other.Helper; Helper.help();";
        return found;
    }

    @Override
    public Case createCase(String title) {
        Case entity = new Case(title);
        entity.setStatus(CaseStatus.OPEN);
        return caseRepository.save(entity);
    }

    @Override
    public Case updateCase(Long id, String title) {
        Case entity = caseRepository.findById(id);
        if (entity != null) {
            entity.setTitle(title);
            entity.setStatus(CaseStatus.IN_PROGRESS);
            return caseRepository.save(entity);
        }
        return null;
    }

    private void touch(Case entity) {
        if (entity != null && entity.getStatus() == CaseStatus.CLOSED) {
            caseRepository.delete(entity.getId());
        }
    }
}
