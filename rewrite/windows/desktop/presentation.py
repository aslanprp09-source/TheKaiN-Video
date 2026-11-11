"""Pure presentation helpers. No fake sizes, rates, resume or completion states."""
from collections import deque
from jobs import JobStatus as S, ResumeCapability as R
from desktop.strings import tr


ACTIVE = {S.QUEUED, S.DOWNLOADING, S.PAUSING, S.PAUSED, S.POSTPROCESSING, S.VERIFYING, S.PUBLISHING, S.RETRY_WAIT}


def size_text(amount):
    if amount is None:
        return '—'
    for divisor, unit in ((1024 ** 3, tr('unit.gib')), (1024 ** 2, tr('unit.mib')), (1024, tr('unit.kib'))):
        if amount >= divisor:
            return f'{amount / divisor:.1f}'.replace('.', ',') + ' ' + unit
    return f'{amount} ' + tr('unit.byte')


def format_text(variant):
    kind = f'{variant.video_height}p' if variant.video_height else tr('format.video' if variant.video_codec else 'format.audio')
    fps = ' · ' + tr('format.fps', fps=variant.fps) if variant.fps else ''
    amount = variant.byte_size_known if variant.byte_size_known is not None else variant.byte_size_estimated
    estimate = '≈ ' if variant.byte_size_known is None and amount is not None else ''
    sound = ' · ' + tr('format.silent') if variant.video_codec and not variant.has_audio else ''
    return f'{kind}{fps} · {variant.final_extension.upper()} · {estimate}{size_text(amount)}{sound}'


def can_pause(job):
    return job.state == S.DOWNLOADING and job.resume == R.YES


def can_resume(job):
    return job.state == S.PAUSED and job.backend == 'http'


def filtered_jobs(jobs, group='all', search='', sort='new', hidden=()):
    items = [j for j in jobs if j.id not in hidden and search.casefold() in j.title.casefold()]
    if group == 'active':
        items = [j for j in items if j.state in ACTIVE]
    elif group == 'completed':
        items = [j for j in items if j.state == S.COMPLETED]
    elif group == 'failed':
        items = [j for j in items if j.state == S.FAILED]
    return sorted(items, key=(lambda j: j.title.casefold()) if sort == 'title' else (lambda j: j.created_at), reverse=sort == 'new')


class SpeedMeter:
    def __init__(self):
        self.samples = {}

    def update(self, job, now):
        key = (job.id, job.attempt)
        if job.state != S.DOWNLOADING:
            self.samples.pop(key, None)
            return None, None
        points = self.samples.setdefault(key, deque())
        if points and job.progress_bytes < points[-1][1]:
            points.clear()
        if not points or now - points[-1][0] >= .2:
            points.append((now, job.progress_bytes))
        while len(points) > 2 and now - points[0][0] > 5:
            points.popleft()
        elapsed = now - points[0][0]
        if len(points) < 2 or elapsed < .5:
            return None, None
        speed = max(0, job.progress_bytes - points[0][1]) / elapsed
        eta = max(0, job.total_bytes - job.progress_bytes) / speed if job.total_bytes and speed > 0 else None
        return speed, eta
