"""Controller regressions without constructing a GUI or requiring a display."""
import queue
import importlib.util
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

if importlib.util.find_spec('tkinter') is not None:
    from app import App
else:
    App = None
from domain import AnalyzeResult, DownloadError, ErrorCode
from extractor import parse_metadata
from dataclasses import replace
from pathlib import Path
from jobs import DownloadJob, JobStatus as S, ResumeCapability as R
from domain import FormatVariant


@unittest.skipIf(App is None, "Tkinter not installed; core/extractor tests still run")
class AnalysisControllerTest(unittest.TestCase):
    def controller(self):
        return SimpleNamespace(
            analysis_generation=2, analyzing=True, media=None, busy=False,
            analysis_cancel=threading.Event(), quality=Mock(), formats=Mock(),
            start_btn=Mock(), analyze_btn=Mock(), status=Mock(), events=queue.Queue())

    def test_stale_results_never_enable_download(self):
        c = self.controller()
        result = parse_metadata({'formats': [{'format_id': 'a', 'ext': 'm4a', 'acodec': 'aac'}]}, 'https://example.invalid/v')
        App.apply_analysis(c, 1, result)
        self.assertIsNone(c.media)
        c.start_btn.config.assert_not_called()
        App.apply_analysis(c, 2, result)
        self.assertEqual(c.media, result.media)
        c.start_btn.config.assert_called_with(state='normal')

    def test_url_edit_invalidates_selection_and_cancels(self):
        c = self.controller()
        App.url_changed(c)
        self.assertTrue(c.analysis_cancel.is_set())
        self.assertEqual(c.analysis_generation, 3)
        self.assertIsNone(c.media)
        c.formats.config.assert_called_with(values=())
        c.start_btn.config.assert_called_with(state='disabled')

    def test_failure_never_enables_download(self):
        c = self.controller()
        App.apply_analysis(c, 2, AnalyzeResult(error=DownloadError(ErrorCode.REMOTE_FORBIDDEN)))
        self.assertFalse(c.analyzing)
        self.assertIsNone(c.media)
        c.start_btn.config.assert_not_called()
        self.assertIn('403', c.status.set.call_args.args[0])

    def test_unexpected_worker_error_is_reported_without_secrets(self):
        c = self.controller()
        c.analyzer = Mock()
        c.analyzer.analyze.side_effect = RuntimeError('https://example.invalid/?token=secret')
        App.analyze_worker(c, 2, 'https://example.invalid/v', c.analysis_cancel)
        event, (generation, result) = c.events.get_nowait()
        self.assertEqual((event, generation), ('analysis', 2))
        self.assertEqual(result.error.code, ErrorCode.UNKNOWN)
        self.assertNotIn('secret', str(result))

    def test_queue_rendering_never_offers_fake_pause_or_early_100(self):
        c = SimpleNamespace(progressbar=Mock(), progress=Mock(), task_status=Mock(),
                            pause_btn=Mock(), resume_btn=Mock(), retry_btn=Mock(), cancel_btn=Mock())
        job = DownloadJob('fixture', 'https://example.invalid/v', 'fixture',
                          FormatVariant('f', 'mp4', 'mp4'), Path('/out'), Path('/stage'),
                          state=S.DOWNLOADING, progress_bytes=100, total_bytes=100, resume=R.NO)
        App.render_job(c, job)
        c.progress.set.assert_called_with(99)
        c.pause_btn.config.assert_called_with(state='disabled')
        App.render_job(c, replace(job, state=S.VERIFYING))
        c.progressbar.config.assert_called_with(mode='indeterminate')
        App.render_job(c, replace(job, state=S.COMPLETED))
        c.progress.set.assert_called_with(100)
