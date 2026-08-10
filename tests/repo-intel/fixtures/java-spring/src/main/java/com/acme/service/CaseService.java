package com.acme.service;

import com.acme.model.Case;

public interface CaseService {
    Case getCase(Long id);

    Case createCase(String title);

    Case updateCase(Long id, String title);
}
