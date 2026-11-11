import tempfile
import unittest
from pathlib import Path
from engine import Options, command_for
from extractor import parse_metadata


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.media = parse_metadata({'formats': [
            {'format_id': 'v720', 'ext': 'mp4', 'height': 720, 'vcodec': 'avc1', 'acodec': 'none'},
            {'format_id': 'audio', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a'},
            {'format_id': 'mixed', 'ext': 'webm', 'vcodec': 'vp9', 'acodec': 'opus'},
        ]}, 'https://example.org/video').media

    def test_rejects_unsafe_url(self):
        for url in ('file:///etc/passwd', 'javascript:alert(1)', 'nonsense', 'https://u:p@example.com'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                command_for(Options(url, Path(tempfile.gettempdir()), self.media.formats[0]))

    def test_all_actual_formats(self):
        for variant in self.media.formats:
            cmd = command_for(Options('https://example.org/video', Path.cwd(), variant))
            self.assertIn('--continue', cmd)
            self.assertIn('--ignore-config', cmd)
            self.assertEqual(cmd[-2:], ['--', 'https://example.org/video'])
            self.assertEqual(cmd[cmd.index('-f') + 1], variant.selector)
            self.assertEqual('--merge-output-format' in cmd, variant.requires_merge)

    def test_audio_keeps_actual_container(self):
        audio = next(v for v in self.media.formats if not v.video_codec)
        cmd = command_for(Options('https://example.org/test', Path.cwd(), audio))
        self.assertNotIn('--audio-format', cmd)
        self.assertNotIn('--merge-output-format', cmd)
        self.assertEqual(audio.final_extension, 'm4a')

    def test_no_speculative_quality_fallback(self):
        with self.assertRaises(ValueError):
            command_for(Options('https://example.org/video', Path.cwd(), '1080p'))


if __name__ == '__main__':
    unittest.main()
