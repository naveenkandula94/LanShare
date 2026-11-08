# Build guide

## Toolchain

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.9.2 |
| Gradle | 8.11.1 |
| JDK | 17 |
| Kotlin Gradle plugin | 2.0.21 |
| Compile SDK | 35 |
| Target SDK | 35 |
| Minimum SDK | 26 |
| WebRTC Android SDK | 144.7559.15 |

The AGP/Gradle/JDK combination is pinned to a known compatible AGP 8.9 line. The WebRTC Android SDK is pulled from Maven Central.

## Build

```bash
gradle --no-daemon :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The project intentionally uses a Gradle build file and does not include a Gradle wrapper JAR. GitHub Actions installs Gradle 8.11.1 directly. AIDE compatibility depends on the AIDE version and its support for AGP 8.9/Kotlin 2.0; GitHub Actions is the recommended build path.
