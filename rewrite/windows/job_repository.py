"""SQLite v1 migration and transactional state changes; one connection per operation."""
import json
import sqlite3
import uuid
import time
from contextlib import contextmanager
from dataclasses import asdict, replace
from pathlib import Path
from domain import DownloadError, ErrorCode, FormatVariant
from jobs import DownloadJob, JobStatus, ResumeCapability, TRANSITIONS


def encode(job):
    data = asdict(job)
    for key in ('destination', 'stage', 'artifact', 'output'):
        data[key] = str(data[key]) if data[key] is not None else None
    return json.dumps(data, ensure_ascii=False)


def decode(payload):
    data = json.loads(payload)
    for key in ('destination', 'stage', 'artifact', 'output'):
        data[key] = Path(data[key]) if data[key] is not None else None
    data['state'] = JobStatus(data['state'])
    data['resume'] = ResumeCapability(data['resume'])
    data['variant']['subtitle_languages'] = tuple(data['variant']['subtitle_languages'])
    data['variant'] = FormatVariant(**data['variant'])
    if data['error']:
        data['error'] = DownloadError(ErrorCode(data['error']['code']), data['error']['retryable'])
    return DownloadJob(**data)


class JobRepository:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = self.root / 'jobs.sqlite3'
        with self.connect() as con:
            version = con.execute('PRAGMA user_version').fetchone()[0]
            if version > 1:
                raise RuntimeError('Unsupported queue schema; database left unchanged')
            if version == 0:
                con.execute('CREATE TABLE jobs (id TEXT PRIMARY KEY, payload TEXT NOT NULL)')
                con.execute('PRAGMA user_version=1')
        self.db.chmod(0o600)

    @contextmanager
    def connect(self):
        con = sqlite3.connect(self.db, timeout=10)
        try:
            with con:
                yield con
        finally:
            con.close()

    def add(self, url, title, variant, destination, backend='ytdlp'):
        job_id = str(uuid.uuid4())
        job = DownloadJob(job_id, url, title, variant, Path(destination).expanduser().resolve(),
                          self.root / 'staging' / job_id / '1', backend=backend)
        with self.connect() as con:
            con.execute('INSERT INTO jobs VALUES (?,?)', (job.id, encode(job)))
        return job

    def get(self, job_id):
        with self.connect() as con:
            row = con.execute('SELECT payload FROM jobs WHERE id=?', (job_id,)).fetchone()
        if row is None:
            raise KeyError(job_id)
        return decode(row[0])

    def list(self):
        with self.connect() as con:
            rows = con.execute('SELECT payload FROM jobs ORDER BY rowid').fetchall()
        return [decode(row[0]) for row in rows]

    def update(self, job_id, state=None, **fields):
        with self.connect() as con:
            con.execute('BEGIN IMMEDIATE')
            row = con.execute('SELECT payload FROM jobs WHERE id=?', (job_id,)).fetchone()
            if row is None:
                raise KeyError(job_id)
            old = decode(row[0])
            if old.state in (JobStatus.COMPLETED, JobStatus.CANCELED):
                raise ValueError('Terminal job is immutable')
            if state is not None and state != old.state and state not in TRANSITIONS[old.state]:
                raise ValueError(f'Invalid transition {old.state.value} -> {state.value}')
            job = replace(old, state=state or old.state, updated_at=time.time(), **fields)
            if job.state == JobStatus.COMPLETED and not (job.output and job.verified_bytes and job.digest):
                raise ValueError('Completion requires verification and publication evidence')
            con.execute('UPDATE jobs SET payload=? WHERE id=?', (encode(job), job_id))
        return job
