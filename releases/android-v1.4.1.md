First official signed release of IKEv2 Android Client. Requires Android 11 or later.

### Changes

- Import VPN profiles from configuration links, QR scanning, QR images, and profile files.
- Redesigned Connection and Profiles screens with profile switching, editing, renaming, and deletion.
- Show connection duration and VPN download/upload totals on the home screen.
- Add About and in-app updates from official GitHub releases, with download progress, cancellation, checksum and signing-key verification, and Android installation confirmation.

### Installation and future updates

This release uses a new production signing key. Existing local debug APKs, including the 1.4.0 test build, cannot upgrade to it in place. Keep your original profile files or import links, uninstall the debug build, install this official APK, and reimport your profiles. Uninstalling clears the debug app's local data. Future official releases will use this production key and support in-place updates.

Download `ikev2-android-v1.4.1.apk`; the matching `.apk.sha256` file verifies its SHA-256 checksum. The four Android ABIs are included.

Production signer SHA-256: `9DD62E924AE88A07C49E180173469B70D90EFAD5235172FE0C4F33D27CD453EB`.

### Validation

The publication workflow runs JVM unit tests, native traffic-counter tests, Android lint, and a signed release build, then verifies the APK signature and checksum. Physical-device validation and an end-to-end same-key update installation remain unverified; see RELEASE_TESTING.md.
