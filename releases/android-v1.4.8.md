### Global traffic bypass

- Open **More → Traffic bypass** to configure two independent options for all profiles.
- Bypass domain suffixes such as `.ir` using addresses learned from matching standard UDP DNS replies. Learned routes expire with DNS TTLs.
- Bypass country IP allocations with explicitly downloaded IPv4/IPv6 country lists; saved lists work offline and can be updated daily.
- Save and reconnect to apply settings. Turning off both options restores the usual VPN routing.

### Update

Choose **More → Check for updates**, download **1.4.8**, and select **Install update**. This release retains the official signing key and increases versionCode from 13 to 14, including for the signed bypass test build. An in-place update retains profiles and settings. About displays **1.4.8 (14)**.

Download `ikev2-android-v1.4.8.apk` with its matching `.apk.sha256`. Android 11+; all four Android ABIs are included.

### Routing limits

Encrypted DNS, TCP DNS and previously cached answers cannot create domain bypass rules. Domains sharing an IP may share its bypass route. Country lists describe IP allocations rather than guaranteed physical server location. Android's **Block connections without VPN** blocks direct bypass traffic. Large rule sets may exceed Android 11/12 route limits; the app reports this condition. Android 13+ uses native route exclusions.

### Validation

Final local validation of 1.4.8 (14) passed: 197 JVM tests, native traffic-counter and TUN-update checks, Android lint and the production-signed release build. Package/version, official signing-key compatibility and the APK checksum were verified. The maintainer tested the signed bypass APK, confirmed it works, and requested publication. The full device/API matrix and individual routing/download scenarios remain unrecorded. The release workflow repeats automated checks and verifies the signed APK and checksum. See RELEASE_TESTING.md.
