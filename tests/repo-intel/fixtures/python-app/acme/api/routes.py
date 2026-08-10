from fastapi import APIRouter
from acme.service.case_service import CaseService

router = APIRouter()
service = CaseService()


@router.get("/cases/{id}")
def get_case(id: int):
    return service.get_case(id)


@router.post("/cases")
def create_case(title: str):
    return service.create_case(title)
