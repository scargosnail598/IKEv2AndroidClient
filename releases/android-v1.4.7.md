### A more compact Connection screen

- Removed the separate **Current session / Profile details** card from the home screen.
- Account, app routing and the confirmed connection's start time now appear in a compact summary below **Selected profile**.
- The summary uses at most two lines. Elapsed connection time stays below the connection status, and technical details remain available through **More → Diagnostics**.
- Connection controls, traffic statistics, server ping and configuration sharing remain available.

### Update

From an official earlier build, choose **More → Check for updates**, download **1.4.7**, and select **Install update**. This release retains the official production signing key and increases versionCode from 12 to 13. Profiles and settings are retained by an in-place update. About displays **1.4.7 (13)** after installation.

Download `ikev2-android-v1.4.7.apk`; verify it with the matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Validation

Local validation passed: 167 JVM tests, native traffic-counter checks, Android lint and a production-signed release build. APK signature compatibility with official v1.4.6 and the checksum were verified. The publication workflow repeats tests, lint and the signed build, then verifies the APK signature and checksum. A compact-screen preview is available for 320dp width and enlarged fonts. Physical-device UI interaction and end-to-end installation remain pending device testing; the maintainer requested publication with this documented exception. See RELEASE_TESTING.md.
