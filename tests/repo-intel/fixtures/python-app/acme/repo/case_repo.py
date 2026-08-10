from acme.model.case import Case, CaseStatus

MAX_BATCH = 100


class CaseRepository:
    def find_by_id(self, case_id: int):
        return Case(id=case_id, title="found", status=CaseStatus.OPEN)

    def save(self, case: Case) -> Case:
        return case

    def delete(self, case_id: int) -> None:
        pass
