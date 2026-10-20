### Color and font themes

- Open **Connection → More → Theme** to choose a color and font combination.
- **Terminal green** pairs phosphor green with a system monospace font.
- **Amber console** pairs warm amber with a system monospace font.
- **Rosewood** pairs muted rose colors with serif headings and a sans-serif body.
- **Classic blue** keeps the original appearance. Each theme supports light and dark appearance independently, previews immediately, and persists across app restarts.
- The selected theme also applies to the QR scanner. Layouts, text sizes, spacing and shapes are retained; fonts work offline.

### Update

Choose **More → Check for updates**, download **1.4.9**, and select **Install update**. This release uses the official production signing key and increases versionCode from 14 to 15. An in-place update retains profiles and settings. About displays **1.4.9 (15)**.

Download `ikev2-android-v1.4.9.apk` with its matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Validation

Local validation passed: 197 JVM tests (zero failures/errors/skips), native traffic-counter and TUN-update checks, Android lint, and the debug build. The release workflow repeats automated checks and builds the production-signed APK, then verifies its signature and checksum. The new palettes' primary text and accent color pairs were checked at a minimum contrast of 4.5:1 in both light and dark appearance. Physical-device theme interaction, narrow-screen/font-scaling checks and end-to-end update installation remain unverified. The maintainer requested publication after this limitation was disclosed; no device-validation sign-off is claimed. See RELEASE_TESTING.md.
