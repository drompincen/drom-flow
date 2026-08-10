from dataclasses import dataclass
from enum import Enum

CASE_DEFAULT_PRIORITY = 1


class CaseStatus(Enum):
    OPEN = "open"
    CLOSED = "closed"
    PENDING = "pending"


@dataclass
class Case:
    id: int
    title: str
    status: CaseStatus = CaseStatus.OPEN

    def is_open(self) -> bool:
        return self.status == CaseStatus.OPEN

    @staticmethod
    def from_dict(data: dict) -> "Case":
        return Case(id=data["id"], title=data["title"])

    @classmethod
    def create_new(cls, title: str) -> "Case":
        return cls(id=0, title=title)
