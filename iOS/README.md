# TheKaiN Video — iOS beta

Native SwiftUI companion app. Compatible with iOS 26.6.2 (deployment target iOS 18.0).

Features: direct HTTP(S) video downloads, single-tap link clearing, pause/resume when supported by the server, text/m3u playlist queues, Files app export, playback of supported local videos.

Important limitations: this build **does not download YouTube/VK webpages or proprietary playlists**, nor does it circumvent DRM. Unlike Android, it does not embed yt-dlp or FFmpeg. It requires direct links to video files. HLS is not yet available.

## Build
Use GitHub Actions "Build iOS unsigned IPA". It produces `TheKaiNVideo-unsigned.ipa`. The IPA is **not installable on a normal iPhone without Apple signing/provisioning**. For an installable .ipa, supply Apple development/ad hoc provisioning assets securely to your own build environment or use TestFlight. Do not commit signing keys.

Apple docs: https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices
