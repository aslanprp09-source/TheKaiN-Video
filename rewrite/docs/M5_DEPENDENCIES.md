# M5 dependency versions and licenses

Direct dependencies added to the M4 development build; resolved primary Compose versions were inspected locally. Existing transport/storage dependencies retain the [M4 inventory](M4_DEPENDENCIES.md). M6 must still produce the complete transitive notices/SBOM for signed packaging.

| Component | Version | License |
|---|---|---|
| Kotlin Compose compiler Gradle plugin | 2.0.21 | Apache-2.0 |
| AndroidX Compose BOM | 2025.02.00 | Apache-2.0 |
| Compose UI, foundation, icons-core, tooling, UI tests | 1.7.8 via BOM | Apache-2.0 |
| Material 3 | 1.3.1 via BOM | Apache-2.0 |
| Activity Compose | 1.10.1 | Apache-2.0 |
| Lifecycle ViewModel Compose / runtime Compose | 2.8.7 | Apache-2.0 |
| jsoup (static HTML parsing only) | 1.18.3 | MIT |
| UI Automator (tests only) | 2.3.0 | Apache-2.0 |
| Espresso core (tests only) | 3.6.1 | Apache-2.0 |
| Pillow (CI contact sheet only) | 11.3.0 | HPND/Pillow license |

Sources: [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Kotlin](https://github.com/JetBrains/kotlin), [jsoup license](https://github.com/jhy/jsoup/blob/jsoup-1.18.3/LICENSE), [Pillow license](https://github.com/python-pillow/Pillow/blob/11.3.0/LICENSE). Debug tooling and test-manifest use `debugImplementation`; instrumentation libraries and their mock servers are test configurations. Pillow/FFmpeg only create test evidence/owned fixtures and are not APK dependencies. No yt-dlp, Python, JS runtime, FFmpeg, aria2 or GPL Android wrapper has been added to the app. The existing approved brand asset is reused, not replaced.
