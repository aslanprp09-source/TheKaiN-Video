"""Separate console entry point: the windowed Qt executable has no stdio."""
import yt_dlp

if __name__ == '__main__':
    yt_dlp.main()
