DEFAULT_PREFIX = "other"


def help_with(x):
    """Same basename as acme.util.helper — deliberate name collision trap."""
    return f"{DEFAULT_PREFIX}:{x}"
