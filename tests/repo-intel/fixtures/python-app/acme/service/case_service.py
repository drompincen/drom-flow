import logging
from acme.repo.case_repo import CaseRepository
from acme.model.case import Case, CaseStatus as Status
from ..util import helper
from .base import BaseService

LOG_LEVEL = "INFO"


def untyped_factory():
    """Returns an object with a .save method; type is opaque to a syntactic analyser."""
    return CaseRepository()


def format_id(value):
    """Module-level function deliberately shadowed in process_label."""
    return str(value)


def process_label(value):
    # Local shadows module-level format_id — call must not resolve to the function above.
    format_id = str
    return format_id(value)


class CaseService(BaseService):
    def __init__(self):
        super().__init__()
        self.repo = CaseRepository()

    def get_case(self, case_id: int):
        """Look up a case by id.

        Docstring trap — must NOT produce a CALLS edge:
        self.repo.delete(999)
        """
        # Comment trap — must NOT produce a CALLS edge: self.repo.delete(0)
        labeled = helper.help_with(case_id)
        _ = logging.getLogger(__name__)
        case = self.repo.find_by_id(case_id)

        # Unknown-type call: method name matches CaseRepository.save but type is unknown.
        mystery = untyped_factory()
        mystery.save(case)

        # Dynamic dispatch — must not produce a CALLS edge to CaseRepository.delete.
        getattr(self.repo, "delete")(case_id)

        return case

    def create_case(self, title: str) -> Case:
        case = Case.create_new(title)
        case.status = Status.OPEN
        return self.repo.save(case)
