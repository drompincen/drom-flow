DEFAULT_PREFIX = "util"


def help_with(x):
    return f"{DEFAULT_PREFIX}:{x}"


def format_id(case_id: int) -> str:
    return str(case_id)
