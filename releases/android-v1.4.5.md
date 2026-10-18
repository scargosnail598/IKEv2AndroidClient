### Ping once, then tap to refresh

- Each confirmed VPN connection runs one initial server ping. The result remains visible without periodic probes.
- Tap **Server ping** in the Connection stats card to run one new test. Repeated taps while checking do not start overlapping tests.
- Switching screens, backgrounding/resuming, and rotating the device retain the result for the same session. A pending test can finish within its existing timeout.
- Disconnecting or changing the session, server, profile, or routing cancels the old probe and clears its reading. Late replies cannot replace a new session's result.
- A failed test shows **Unavailable** until you tap to retry or start a new connection. Ping availability does not determine VPN connection status.

### Update

From an official earlier build, choose **More → Check for updates**, download **1.4.5**, and select **Install update**. This release retains the official production signing key and increases versionCode from 10 to 11. Profiles and settings are retained by an in-place update. About displays **1.4.5 (11)** after installation.

Download `ikev2-android-v1.4.5.apk`; verify it with the matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Validation

Local validation passed: 157 JVM tests, native traffic-counter tests, Android lint, and a production-signed release build. Regression tests cover single-echo sampling, retained results, explicit refresh, overlapping-tap prevention, session changes, cancellation and stale replies. The publication workflow repeats tests, lint and the signed build, then verifies the APK signature and checksum. Physical-device ping execution, UI interaction and end-to-end installation remain pending device testing; see RELEASE_TESTING.md.
