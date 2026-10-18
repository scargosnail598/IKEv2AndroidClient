### VPN controls in the notification drawer

- When disconnected, the quiet VPN notification provides **Connect** for the selected saved profile.
- The active VPN notification provides **Disconnect**, including cancellation while connecting. Error notifications provide **Retry**.
- Notification action buttons operate directly without opening the app or a confirmation Activity. No Quick Settings tile is required.
- On Android 13+, notification permission is requested once after profile setup. Use **More → Notifications** to adjust permission or the VPN notification channel.
- Clear the foreground notification correctly after stopping a failed VPN initialization.

The initial profile/password setup and Android VPN consent still happen in the app. If setup or consent is missing, the notification action reports the problem instead of launching an Activity. Android controls notification visibility when permission is denied, the channel is blocked, or the app is force-stopped.

### Update

From an official earlier build, choose **More → Check for updates**, download **1.4.3**, and select **Install update**. This release retains the official production signing key and increases versionCode from 8 to 9. Profiles and settings are retained by an in-place update. After installation, About displays **1.4.3 (9)**. Allow notifications when prompted to enable the notification controls.

Download `ikev2-android-v1.4.3.apk`; verify it with the matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Validation

Local validation passed: 140 JVM tests, native traffic-counter tests, Android lint, and a production-signed release build. The publication workflow repeats tests, lint, and the signed build, then verifies the APK signature and checksum. Physical notification interaction and end-to-end installation remain pending device testing; see RELEASE_TESTING.md.
