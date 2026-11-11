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
