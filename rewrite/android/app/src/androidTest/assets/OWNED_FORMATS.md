# Owned synthetic format fixtures

Issue #62 adds `owned-sample-320.mp4`: one second of a solid color and silent
audio, generated locally without third-party media or credentials. The existing
`owned-sample.mp4` remains byte-for-byte unchanged (see `docs/M4_ANDROID.md`).
These are authorized repository-owned test materials, not downloaded site media.

| File | Bytes | SHA-256 | Real video | Real audio |
| --- | ---: | --- | --- | --- |
| owned-sample.mp4 | 2428 | dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2 | H.264, 160x90, 10 fps | AAC, mono, 8000 Hz |
| owned-sample-320.mp4 | 2485 | eec943f4eb96f892f1b5f2cce512d4802f810b38739d2ccfad49d1eb437e0856 | H.264 baseline, 320x180, 10 fps | AAC, mono, 8000 Hz |

Reproduction: `bash tools/generate_owned_format.sh /tmp/owned-new.mp4` from
`rewrite/android`. The developer machine used Debian's installed FFmpeg
`7.1.5-0+deb13u1` (libx264); the command and official `ffprobe` output are retained.
Byte identity can depend on tool versions. Never regenerate/replace the committed
fixture silently: verify its table hash. No FFmpeg or FFprobe executable/library
is added to the Android package by this fixture-generation command.

The HTML page declares the actual dimensions and AVC/AAC codecs. It does not
declare a filesize or FPS. Those fields must remain unknown in extractor output
and the selector even though the independent fixture oracle knows them. Android
acceptance independently checks downloaded bytes/hash, dimensions, samples in
both audio/video tracks and playback; source declarations alone are not proof.
