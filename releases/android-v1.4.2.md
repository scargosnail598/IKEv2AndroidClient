### Home-screen VPN widget

Add **IKEv2 VPN** from your launcher’s widget picker. The simple, resizable widget shows the VPN status and selected profile. Tap it to open the Connection screen and use the existing connect/disconnect controls. No extra setup or background service is required.

### Test the in-app updater

On the **official 1.4.1** build, open **More → Check for updates**, download **1.4.2**, and choose **Install update**. Android may ask you to allow installs from this app. This release uses the same production signing key and increases versionCode from 7 to 8, so profiles and settings are retained during an in-place update. After installation, About should show **1.4.2 (8)** and a new update check should report that it is current.

Debug builds use a different signing key and cannot update to this official APK in place.

Download `ikev2-android-v1.4.2.apk`; the matching `.apk.sha256` verifies its checksum. Android 11 or later; all four Android ABIs are included.

### Validation

The release workflow runs JVM tests, native traffic-counter tests, Android lint and a signed release build, then verifies the APK signature and checksum. Launcher behavior on a physical device and end-to-end update installation remain pending device testing; see RELEASE_TESTING.md.
