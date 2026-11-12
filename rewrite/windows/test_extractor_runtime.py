import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from extractor import SourceAnalyzer, parse_metadata
from extractor_runtime import extractor_command, extractor_available
from engine import Options, command_for


class FrozenExtractorTest(unittest.TestCase):
    def test_source_uses_python_module(self):
        self.assertEqual(extractor_command(), [sys.executable, '-m', 'yt_dlp', '--encoding', 'utf-8'])

    def test_frozen_analysis_and_transfer_use_sibling_worker_even_with_spaces(self):
        with tempfile.TemporaryDirectory(prefix='TheKaiN QA ') as directory:
            gui = Path(directory) / 'TheKaiNVideo.exe'
            worker = gui.with_name('TheKaiNExtractor.exe')
            with patch.object(sys, 'frozen', True, create=True), patch.object(sys, 'executable', str(gui)):
                self.assertFalse(extractor_available())
                worker.touch()
                self.assertTrue(extractor_available())
                self.assertEqual(SourceAnalyzer().command, [str(worker), '--encoding', 'utf-8'])
                media = parse_metadata({'formats': [{'format_id': 'audio', 'ext': 'm4a', 'acodec': 'aac'}]},
                                       'https://example.invalid/owned').media
                command = command_for(Options('https://example.invalid/owned?token=test', Path(directory), media.formats[0]))
                self.assertEqual(command[:4], [str(worker), '--encoding', 'utf-8', '--ignore-config'])
                self.assertEqual(command[-2:], ['--', 'https://example.invalid/owned?token=test'])
                self.assertNotIn(str(gui), command)
