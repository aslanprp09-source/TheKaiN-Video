import json
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from domain import AnalyzeResult, ErrorCode
from extractor import SourceAnalyzer, classify_error, parse_metadata, validate_url

FIXTURES = Path(__file__).with_name('fixtures')
URL = 'https://example.invalid/authorized-fixture?token=test-only'


def fixture(name):
    return json.loads((FIXTURES / (name + '.json')).read_text(encoding='utf-8'))


class MetadataTest(unittest.TestCase):
    def test_one_format_and_missing_thumbnail(self):
        media = parse_metadata(fixture('one'), URL).media
        self.assertEqual(media.source_id, 'fixture-one')
        self.assertEqual(media.source_host, 'example.invalid')
        self.assertIsNone(media.thumbnail_uri)
        self.assertEqual(media.duration_seconds, 12.5)
        self.assertEqual(len(media.formats), 1)
        v = media.formats[0]
        self.assertEqual((v.video_height, v.final_extension, v.has_audio, v.requires_merge), (360, 'mp4', True, False))
        self.assertEqual(v.byte_size_known, 1048576)
        self.assertIsNone(v.byte_size_estimated)

    def test_three_real_formats_and_estimate(self):
        media = parse_metadata(fixture('three'), URL).media
        self.assertEqual([v.extractor_format_id for v in media.formats], ['video-720', 'mixed-360', 'audio-128'])
        video = media.formats[0]
        self.assertEqual(video.selector, 'video-720+audio-128')
        self.assertTrue(video.requires_merge)
        self.assertEqual(video.final_extension, 'mp4')
        self.assertIsNone(video.byte_size_known)
        self.assertEqual(video.byte_size_estimated, 3000)
        self.assertIn('≈', video.label)
        self.assertEqual(video.subtitle_languages, ('ru',))
        self.assertNotIn('1080', ' '.join(v.label for v in media.formats))

    def test_unknown_size_is_neither_zero_nor_estimate(self):
        v = parse_metadata(fixture('three'), URL).media.formats[1]
        self.assertIsNone(v.byte_size_known)
        self.assertIsNone(v.byte_size_estimated)
        self.assertIn('—', v.label)

    def test_audio_only_not_fabricated_mp3(self):
        media = parse_metadata(fixture('audio'), URL).media
        self.assertEqual(len(media.formats), 1)
        v = media.formats[0]
        self.assertIsNone(v.video_codec)
        self.assertEqual(v.final_extension, 'webm')
        self.assertTrue(v.has_audio)
        self.assertFalse(v.requires_merge)

    def test_no_formats(self):
        result = parse_metadata(fixture('none'), URL)
        self.assertIsNone(result.media)
        self.assertEqual(result.error.code, ErrorCode.NO_FORMATS)

    def test_video_only_without_audio_partner_is_honest(self):
        data = fixture('three')
        data['formats'] = [data['formats'][1]]
        v = parse_metadata(data, URL).media.formats[0]
        self.assertFalse(v.has_audio)
        self.assertFalse(v.requires_merge)
        self.assertIn('без звука', v.label)

    def test_incompatible_merge_uses_mkv(self):
        data = fixture('three')
        data['formats'][0]['ext'] = 'ogg'
        self.assertEqual(parse_metadata(data, URL).media.formats[0].final_extension, 'mkv')

    def test_merge_size_requires_both_tracks(self):
        data = fixture('three')
        data['formats'][0].pop('filesize')
        v = parse_metadata(data, URL).media.formats[0]
        self.assertIsNone(v.byte_size_known)
        self.assertIsNone(v.byte_size_estimated)
        data['formats'][0]['filesize'] = 1000
        data['formats'][1]['filesize'] = 2000
        self.assertEqual(parse_metadata(data, URL).media.formats[0].byte_size_known, 3000)

    def test_drm_not_offered(self):
        for top_level in (True, False):
            data = fixture('one')
            if top_level:
                data['has_drm'] = True
            else:
                data['formats'][0]['has_drm'] = True
            self.assertEqual(parse_metadata(data, URL).error.code, ErrorCode.DRM_PROTECTED)

    def test_storyboards_invalid_ids_duplicates_filtered(self):
        data = fixture('one')
        data['formats'] += [data['formats'][0].copy(), {'format_id': 'story', 'ext': 'mhtml', 'vcodec': 'none', 'acodec': 'none'},
                            {'format_id': 'best/best', 'ext': 'mp4', 'vcodec': 'avc1'}]
        self.assertEqual(len(parse_metadata(data, URL).media.formats), 1)

    def test_playlist_not_silently_selected(self):
        self.assertEqual(parse_metadata({'_type': 'playlist', 'entries': []}, URL).error.code, ErrorCode.UNSUPPORTED_SOURCE)

    def test_malformed_metadata(self):
        for data in ([], None, {'formats': 'not a list'}):
            self.assertEqual(parse_metadata(data, URL).error.code, ErrorCode.UNKNOWN)

    def test_invalid_sizes(self):
        for bad in (-1, float('nan'), True, '100'):
            data = fixture('one')
            data['formats'][0]['filesize'] = bad
            self.assertIsNone(parse_metadata(data, URL).media.formats[0].byte_size_known)


class ErrorTest(unittest.TestCase):
    def test_invalid_urls(self):
        for url in ('file:///tmp/video', 'javascript:alert(1)', 'data:video/mp4,a', 'ftp://example.org/v',
                    'https://u:p@example.org/v', 'https://@example.org', 'https://example.org:bad/v',
                    'https://[broken', 'https://example.org/a\nb', 'https://example.org/\x00',
                    'https://example.org/a b', 'https://example.org\\@evil.org'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_url(url)

    def test_query_shell_characters_are_data(self):
        url = "https://example.org/v?q=$(test);&a='x'"
        self.assertEqual(validate_url(url), url)

    def test_classifications_and_private_diagnostics(self):
        for text, code in (('HTTP Error 403 Forbidden', ErrorCode.REMOTE_FORBIDDEN), ('HTTP Error 404', ErrorCode.REMOTE_MISSING),
                           ('HTTP Error 429', ErrorCode.RATE_LIMITED), ('private video', ErrorCode.AUTH_REQUIRED),
                           ('DRM protected', ErrorCode.DRM_PROTECTED), ('not available in your country', ErrorCode.GEO_RESTRICTED),
                           ('Connection reset', ErrorCode.NETWORK_LOST), ('timed out', ErrorCode.TIMEOUT),
                           ('Unsupported URL', ErrorCode.UNSUPPORTED_SOURCE), ('No module named yt_dlp', ErrorCode.TOOL_MISSING),
                           ('no video formats', ErrorCode.NO_FORMATS), ('unexpected problem', ErrorCode.UNKNOWN)):
            with self.subTest(code=code):
                error = classify_error(text + ' https://example.invalid/?token=secret-test')
                self.assertEqual(error.code, code)
                self.assertNotIn('secret-test', str(error))
                self.assertNotIn('https://', error.message)
                self.assertTrue(error.message_key.startswith('error.'))

    def test_result_exactly_one_outcome(self):
        with self.assertRaises(ValueError):
            AnalyzeResult()


class ProcessTest(unittest.TestCase):
    def analyzer(self, mode):
        return SourceAnalyzer([sys.executable, str(FIXTURES / 'extractor_process.py'), mode])

    def test_real_subprocess_metadata(self):
        self.assertEqual(self.analyzer('one').analyze(URL).media.source_id, 'fixture-one')

    def test_command_safe_no_download(self):
        with patch('extractor.subprocess.Popen', wraps=subprocess.Popen) as launch:
            self.analyzer('one').analyze(URL)
        args, kwargs = launch.call_args
        self.assertIsInstance(args[0], list)
        self.assertEqual(args[0][-2:], ['--', URL])
        self.assertIn('--skip-download', args[0])
        self.assertIn('--ignore-config', args[0])
        self.assertFalse(kwargs['shell'])

    def test_extractor_failure_and_bad_json(self):
        self.assertEqual(self.analyzer('error').analyze(URL).error.code, ErrorCode.REMOTE_FORBIDDEN)
        self.assertEqual(self.analyzer('bad-json').analyze(URL).error.code, ErrorCode.UNKNOWN)

    def test_missing_tool(self):
        self.assertEqual(SourceAnalyzer([str(FIXTURES / 'absent')]).analyze(URL).error.code, ErrorCode.TOOL_MISSING)

    def test_invalid_url_never_launches(self):
        with patch('extractor.subprocess.Popen') as launch:
            self.assertEqual(self.analyzer('one').analyze('file:///etc/passwd').error.code, ErrorCode.INVALID_URL)
        launch.assert_not_called()

    def test_timeout_reaps_process(self):
        processes = []
        start = time.monotonic()
        real_popen = subprocess.Popen
        def record(*args, **kwargs):
            p = real_popen(*args, **kwargs)
            processes.append(p)
            return p
        with patch('extractor.subprocess.Popen', side_effect=record):
            result = self.analyzer('wait').analyze(URL, timeout=0.15)
        self.assertEqual(result.error.code, ErrorCode.TIMEOUT)
        self.assertLess(time.monotonic() - start, 3)
        self.assertIsNotNone(processes[0].poll())

    def test_cancel_before_launch(self):
        cancel = threading.Event()
        cancel.set()
        with patch('extractor.subprocess.Popen') as launch:
            self.assertEqual(self.analyzer('wait').analyze(URL, cancel).error.code, ErrorCode.CANCELED)
        launch.assert_not_called()

    def test_cancel_running_process(self):
        cancel = threading.Event()
        timer = threading.Timer(0.15, cancel.set)
        timer.start()
        try:
            start = time.monotonic()
            result = self.analyzer('wait').analyze(URL, cancel)
            self.assertEqual(result.error.code, ErrorCode.CANCELED)
            self.assertLess(time.monotonic() - start, 3)
        finally:
            timer.cancel()
