import tempfile
import unittest
from pathlib import Path
from engine import Options, command_for, FORMAT_CHOICES


class EngineTest(unittest.TestCase):
    def test_rejects_unsafe_url(self):
        for url in ("file:///etc/passwd", "javascript:alert(1)", "nonsense", "https://u:p@example.com"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                command_for(Options(url, Path(tempfile.gettempdir())))

    def test_all_formats(self):
        for quality in FORMAT_CHOICES:
            cmd = command_for(Options("https://example.org/video", Path(tempfile.gettempdir()), quality))
            self.assertIn("--continue", cmd)
            self.assertEqual(cmd[-1], "https://example.org/video")

    def test_mp3(self):
        cmd = command_for(Options("https://example.org/test.mp4", Path.cwd(), "MP3"))
        self.assertIn("--audio-format", cmd)
        self.assertIn("mp3", cmd)


if __name__ == "__main__":
    unittest.main()
