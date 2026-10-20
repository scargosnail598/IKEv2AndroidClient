### Share configurations as links and QR codes

- Open **Profiles → ⋯ → Share link / QR** on any saved connection.
- Scan the displayed QR, copy or share the configuration link, or send a PNG with **Share QR image**.
- **Include saved password** is off by default. Enable it only for a trusted recipient: links and QR images are not encrypted.
- Sharing works for selected and inactive profiles, including while connected, without switching profiles or interrupting the VPN.
- Exports use the existing offline `ikev://import/v1/` format and preserve the server, username, profile name and private-CA certificate/fingerprint or public certificate trust.
- App routing stays on the source device; imported connections start with **All apps**. Oversized configurations and usernames/passwords unsupported by v1 show an actionable error.
- The sharing dialog closes on backgrounding or rotation. Images sent through the share sheet use temporary read access to a dedicated private cache directory.

### Update

From an official earlier build, choose **More → Check for updates**, download **1.4.6**, and select **Install update**. This release retains the official production signing key and increases versionCode from 11 to 12. Profiles and settings are retained by an in-place update. About displays **1.4.6 (12)** after installation.

Download `ikev2-android-v1.4.6.apk`; verify it with the matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Validation

Local release validation passed: 167 JVM tests, native traffic-counter checks, Android lint and a production-signed release build. The APK signature matches official v1.4.5, and its checksum was verified. Coverage includes link round trips for both trust modes, optional and profile-specific credentials, QR decoding at the transport size limit, rejected oversized exports and sharing without changing the active VPN. The publication workflow repeats tests, lint and the production-signed build, then verifies the APK signature and checksum. Physical-device scanning/sharing, UI interaction and end-to-end installation remain pending device testing; the maintainer requested publication with this documented exception. See RELEASE_TESTING.md.
