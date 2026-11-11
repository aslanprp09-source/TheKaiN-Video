# M4 Android dependencies

Pinned direct build/runtime/test dependencies for the development APK. No packaged distribution or license-compliance sign-off is claimed. Before M6 packaging, generate the resolved transitive dependency/license inventory and include required notices in the distributable.

| Dependency | Version | License / upstream |
|---|---|---|
| Android Gradle Plugin | 8.9.2 | Apache-2.0, Android Open Source Project |
| Kotlin Android + kapt | 2.0.21 | Apache-2.0, JetBrains Kotlin |
| AndroidX Room runtime/ktx/compiler | 2.7.2 | Apache-2.0, AndroidX |
| AndroidX Work runtime-ktx | 2.10.1 | Apache-2.0, AndroidX |
| AndroidX Core ktx | 1.15.0 | Apache-2.0, AndroidX |
| kotlinx-coroutines Android/test | 1.9.0 | Apache-2.0, JetBrains |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| AndroidX test runner/core-ktx/ext JUnit | 1.6.2 / 1.6.1 / 1.2.1 | Apache-2.0 |
| OkHttp MockWebServer / TLS (tests only) | 4.12.0 | Apache-2.0, Square |
| Gradle (build tool) | 8.11.1 | Apache-2.0 |
| JDK (build tool, CI Temurin) | 17 | GPL-2.0 with Classpath Exception |

Android SDK platform 35, build tools 35 and emulator/system images are official Google development tools under their respective SDK licenses; they are not bundled in the app. Native media extraction/frame decoding uses Android APIs. FFmpeg generated the owned test fixture but is not bundled in the Android APK. MockWebServer, its TLS certificate helper, AndroidX test and JUnit are confined to test configurations. Production transport uses the platform HTTPS implementation.
