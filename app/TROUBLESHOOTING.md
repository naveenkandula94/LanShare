# Troubleshooting

- **Gradle/JDK error:** use JDK 17 and Gradle 8.11.1.
- **Dependency download failure:** confirm Maven Central access and rerun the workflow.
- **No sender connection:** verify both phones are on the same Wi-Fi and not on a guest/client-isolated network.
- **Wrong IP:** use the IPv4 address shown in the sender app; both phones may need to be on the same subnet.
- **Connection refused:** sender must tap Share first and keep the app open; check router isolation/firewall.
- **No voice:** grant microphone permission on both devices and check media volume.
- **No screen:** sender must approve the Android screen capture prompt; some protected apps prevent capture.
- **Battery restrictions:** disable battery optimization for the app during testing if the phone aggressively stops background work.
- **AIDE cannot import:** AIDE versions differ in AGP/Kotlin support. Use the included GitHub Actions workflow as the reliable build route.

The current prototype has no automatic peer discovery and no secure signaling/authentication. It is intended for a trusted LAN test only.


## QR pairing and password
- The sender displays a QR code after screen sharing starts; the receiver can tap **Scan Sender QR Code**.
- If the sender configured a password, enter the same password on the receiver before connecting.
- A wrong password is rejected before WebRTC negotiation.
- Password signaling is currently plain TCP; use only trusted private Wi-Fi.
