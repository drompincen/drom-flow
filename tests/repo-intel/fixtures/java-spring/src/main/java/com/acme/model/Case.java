package com.acme.model;

public class Case extends BaseEntity {
    private String title;
    private CaseStatus status;

    public Case() {
        this.status = CaseStatus.OPEN;
    }

    public Case(String title) {
        this.title = title;
        this.status = CaseStatus.OPEN;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }
}
