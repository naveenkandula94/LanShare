# Version compatibility

| Component | Pinned version | Reason |
|---|---:|---|
| AGP | 8.9.2 | Stable Android Gradle Plugin 8.9 line |
| Gradle | 8.11.1 | Compatible Gradle line for AGP 8.9 |
| JDK | 17 | Required baseline for AGP 8.x |
| Kotlin plugin | 2.0.21 | Kotlin 2.0 compiler plugin |
| Android SDK | 35 | Android 15 API level |
| WebRTC | 144.7559.15 | Maven Central Android WebRTC artifact |

Do not upgrade one item in isolation. Update AGP, Gradle, Kotlin, and the WebRTC artifact as a tested set. Check the official Android Gradle Plugin compatibility table and the WebRTC SDK release notes before upgrading.
