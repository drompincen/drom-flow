from acme.service.case_service import CaseService


def test_get_case():
    svc = CaseService()
    result = svc.get_case(1)
    assert result is not None


def test_create_case():
    svc = CaseService()
    case = svc.create_case("demo")
    assert case.title == "demo"
