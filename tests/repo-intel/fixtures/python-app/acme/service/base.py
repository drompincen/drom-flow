SERVICE_VERSION = "1.0"


class BaseService:
    def __init__(self):
        self.ready = True

    def ping(self) -> str:
        return "ok"
