# LAN Share (Android Kotlin starter)

A single-APK Android app prototype for sharing one phone's screen and microphone audio to another phone on the same Wi-Fi network. Both devices install the same app. One taps **Share My Screen + Voice**; the other enters the sender's local Wi-Fi IP and taps **Watch Other Phone + Voice**.

## Important status

This is a source project, not a prebuilt APK. The project files and workflow are packaged, but an Android build has **not** been run in this environment. Run the included GitHub Actions workflow before treating the APK as tested.

This is an MVP prototype. The receiver now explicitly negotiates a receive-only video stream, fixing a core WebRTC negotiation issue where the original receiver offer did not contain a video media section. Signaling uses a simple TCP connection on port `47831`; it is not encrypted or authenticated. Use only on a trusted Wi-Fi network. It does not include mDNS auto-discovery, remote access over the internet, screen-control/touch input, or a polished reconnect flow.

## Requirements

- Android 8.0 (API 26) or newer.
- Both phones on the same Wi-Fi LAN; avoid guest Wi-Fi/client isolation.
- Microphone permission on both phones.
- Sender must approve Android's screen-capture dialog every session.
- Receiver needs the sender's local IPv4 address.

## Build with GitHub Actions (phone-friendly)

1. Create a GitHub repository and upload the contents of this folder (not the ZIP itself).
2. Ensure the default branch is named `main`.
3. Open the repository's **Actions** tab.
4. Select **Build LAN Share APK** and tap **Run workflow**. A push to `main` also triggers it.
5. Wait for the green successful run. Open the run, scroll to **Artifacts**, and download `lan-share-debug-apk`.
6. Extract the downloaded artifact ZIP; install `app-debug.apk` on both phones. Allow installation from the browser/files app if Android asks.

The workflow runs `gradle :app:assembleDebug`, checks that the APK exists, and runs `unzip -t` before uploading it.

## Local build

With JDK 17 and Gradle 8.11.1 installed:

```bash
gradle --no-daemon :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Pairing

1. Connect both phones to the same Wi-Fi network.
2. On the sender, tap **Share My Screen + Voice** and approve screen capture.
3. The sender app displays its Wi-Fi IP. Keep the app open.
4. On the receiver, type that IP and tap **Watch Other Phone + Voice**.
5. Both users should grant microphone permission. Tap **Stop / Disconnect** to end the session.

If it fails, check that both phones are on the same non-isolated Wi-Fi, the sender is still waiting, and port `47831` is not blocked by the router.

## Privacy and Android behavior

- Screen capture is visible to the sender and requires Android's system consent dialog.
- Android shows an ongoing foreground-service notification while sharing.
- Microphone audio is sent through WebRTC audio tracks. Mute/volume controls are not yet included.
- The signaling socket carries SDP over local TCP without TLS. Do not use on public/untrusted networks.

## Project identifiers

- Application ID: `com.example.lanshare`
- Version: `1.0` (`versionCode` 1)
- Minimum SDK: 26
- Compile/target SDK: 35


## Included capabilities

- Live Android screen mirroring from one phone to another over local Wi-Fi.
- Two-way microphone audio using WebRTC audio tracks (both users must grant microphone permission).
- Android screen-capture consent prompt and an ongoing foreground-service notification.
- Manual pairing using the sender's local IPv4 address.
- IPv4 input validation and a network-interface fallback for IP detection.

## How the app works

1. Connect both phones to the same trusted Wi-Fi network.
2. On the sender, tap **Share My Screen + Voice** and approve Android's screen-capture prompt.
3. The sender starts a TCP signaling listener on port `47831` and displays its LAN IP address.
4. On the receiver, enter that IP and tap **Watch Other Phone + Voice**.
5. The receiver creates a WebRTC SDP offer; the sender returns an SDP answer. ICE gathering completes before the SDP is exchanged.
6. WebRTC carries screen video and microphone audio directly between the devices. The receiver displays the incoming video.
7. Tap **Stop / Disconnect** to end the session.

## Current limitations / recommended next features

- Manual pairing only; QR pairing and automatic LAN discovery are not included.
- Signaling uses plain TCP without authentication or encryption. Use only a trusted private network.
- No TURN relay is configured; client-isolated Wi-Fi or restrictive networks may prevent connection.
- No remote touch control, file transfer, recording, mute button, or reconnect UI yet.
- This source has not been built or tested on physical devices in this environment. Run CI and test on two Android phones before release.


## Version 1.2 feature status

Implemented in this source update:
- Microphone mute/unmute for the local microphone track.
- Sender-side capture quality presets: 640×360/15 fps, 1280×720/15 fps, and 1920×1080/30 fps.
- Optional password check during LAN signaling before WebRTC negotiation.
- QR code generation on the sender and QR scanning on the receiver.
- A manual reconnect button that reuses the last receiver IP and password.

Not implemented yet (do not treat the buttons/features as available):
- Sending photos/documents between phones.
- Recording the shared session to a video file.
- Automatic reconnect/retry after a dropped connection.

Security note: the optional password is sent over the current plain TCP signaling socket and is not cryptographically protected. It is a basic pairing gate, not production-grade authentication. WebRTC media encryption depends on the WebRTC transport, but signaling still needs TLS or a secure authenticated channel for production.

## Build and verification

The included GitHub Actions workflow uses JDK 17 and Gradle 8.11.1 to build `:app:assembleDebug`, checks that the APK is a valid ZIP, and uploads `app-debug.apk` as the `lan-share-debug-apk` artifact. Run the workflow on GitHub to get a real build result. This editing environment does not have the Android SDK or Gradle installed, so this updated source has not been compiled here and cannot honestly be described as a verified APK.
