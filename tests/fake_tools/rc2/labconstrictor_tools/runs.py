def build_record(*args, **kwargs):
    raise NotImplementedError


def record_json(content):
    raise NotImplementedError


def done_status(task_status, cancel_requested=False):
    raise NotImplementedError
