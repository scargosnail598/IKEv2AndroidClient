### Server ping on the home screen

- The Connection stats card now shows **Server ping** beneath download and upload totals.
- Ping measures ICMP round-trip time to the selected profile’s server and shows the result in milliseconds. Values below one millisecond display as **<1 ms**.
- Measurement runs only while the VPN is confirmed connected and the Connection screen is visible, with five seconds between probes. Leaving the screen, backgrounding the app, or disconnecting cancels the probe.
- A new session starts with **Checking…**; a failed or timed-out probe clears the previous result and displays **Unavailable**. This result alone does not mean the VPN is disconnected.
- The target follows the app’s per-app routing. **Outside VPN** is shown when this VPN app is excluded. No third-party ping target or extra permission is used.

The server/firewall must allow ICMP replies for a result to be available. This measures a round-trip to the server, rather than Internet throughput or overall tunnel health.

### Update

From an official earlier build, choose **More → Check for updates**, download **1.4.4**, and select **Install update**. This release retains the official production signing key and increases versionCode from 9 to 10. Profiles and settings are retained by an in-place update. About displays **1.4.4 (10)** after installation.

Download `ikev2-android-v1.4.4.apk`; verify it with the matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Validation

Local validation passed: 149 JVM tests, native traffic-counter tests, Android lint, and a production-signed release build. Tests cover ping parsing, timeouts, cancellation, process cleanup, sampling reset and display formatting. The publication workflow repeats tests, lint and the signed build, then verifies the APK signature and checksum. Physical-device ICMP execution and end-to-end installation remain pending device testing; see RELEASE_TESTING.md.
