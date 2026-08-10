package com.acme.repo;

import com.acme.model.Case;
import org.springframework.stereotype.Repository;

@Repository
public class CaseRepository {

    public Case findById(Long id) {
        return null;
    }

    /** Same simple name as java.util.List.get — field-type must disambiguate. */
    public Case get(Long id) {
        return findById(id);
    }

    public Case save(Case entity) {
        return entity;
    }

    public void delete(Long id) {
    }
}
