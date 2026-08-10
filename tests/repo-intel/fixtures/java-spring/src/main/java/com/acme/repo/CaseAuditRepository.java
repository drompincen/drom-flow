package com.acme.repo;

import com.acme.model.Case;
import org.springframework.stereotype.Repository;

/**
 * Second repository declaring findById — trap for field-type resolution.
 * CaseServiceImpl only injects CaseRepository, so calls resolve only there.
 */
@Repository
public class CaseAuditRepository {

    public Case findById(Long id) {
        return null;
    }

    public void audit(Long id) {
    }
}
