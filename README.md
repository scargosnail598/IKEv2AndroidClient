# IKEv2 Android Client

A standalone Android 11+ client that provisions and controls an IKEv2/IPsec
VPN profile through Android's platform VPN APIs. The app is written in Kotlin
with Jetpack Compose and Material 3.

## Status and version

The current application version is **1.1.0** (`versionCode = 2`). The Gradle
configuration in `app/build.gradle.kts` is the version source of truth.

The repository is maintained as an independent Android project. Release-device
acceptance remains a manual gate; see [RELEASE_TESTING.md](RELEASE_TESTING.md).

## Key characteristics

```text
Android app
    ↓
VpnManager / Ikev2VpnProfile
    ↓
Android platform IKEv2/IPsec stack
    ↓
IKEv2 server
```

- Android performs IKEv2/IPsec processing; the app provisions and controls the
  platform profile.
- The app does not implement IKEv2, intercept VPN traffic, use `VpnService`, or
  embed a native VPN engine.
- Authentication uses IKEv2 with EAP-MSCHAPv2 and a private CA.
- Passwords are used transiently for provisioning and are never persisted.
- Diagnostics intentionally contain no VPN password.

## Requirements

- Android 11 or newer (API 30+)
- Device support for `android.software.ipsec_tunnels`
- JDK 25, matching the checked-in Gradle daemon criteria
- Android SDK 36

Gradle compiles application sources for Java 17 bytecode. JDK 25 is the build
runtime selected by `gradle/gradle-daemon-jvm.properties` and is supported by
the pinned Gradle 9.5 wrapper.

## Supported functionality

- Hostname or IPv4 VPN server addresses
- One IPv4 full-tunnel profile
- Native Android VPN provisioning and consent
- Connect, disconnect, reconnect, and platform-confirmed status
- Private CA import from DER or PEM
- Portable `.ikev` schema version 1 import
- Manual profile configuration
- Sanitized diagnostics
- Persistent light/dark theme selection

## Current limitations

The app does not currently support IPv6 literals, multiple profiles, QR
provisioning, SOCKS5 or split-tunnel Proxy Mode, per-app VPN, custom DNS or
routing, a custom `VpnService`, Quick Settings, or auto-connect UI.

API 33+ uses `startProvisionedVpnProfileSession()`, `VpnProfileState`, and
protected VPN-manager events. API 30–32 isolates the deprecated start call and
reports Connected only after Android exposes an app-owned VPN network. Vendor
implementations may report transitions or failure details slowly.

## Portable `.ikev` import

Android v1.1 supports `.ikev` schema version 1 only. A portable profile contains
the server, Remote ID, username, public CA certificate, CA SHA-256 fingerprint,
and informational server/proxy metadata. It never contains a VPN password or
private key. The source JSON and Base64 certificate text are read once and are
not persisted.

1. Export `username.ikev` from the server environment.
2. Copy or share it to the Android device.
3. Open the app and select **Import .ikev Profile**.
4. Select the file and review the server, username, and CA fingerprint.
5. Enter the VPN password and select **Save / Provision VPN**.
6. Approve Android VPN consent if requested, then connect.

Import validates the frozen format and version, EAP-MSCHAPv2 authentication,
Full Tunnel mode, profile/server/username fields, embedded DER CA validity and
CA Basic Constraints, and the CA SHA-256 fingerprint. Import populates the
setup screen but does not provision or replace the Android VPN profile until
the user selects **Save / Provision VPN**.

Android's current `Ikev2VpnProfile.Builder` path cannot represent an independent
server Remote ID, so imported `remote_id` must equal `server`. Profiles that
differ are rejected. Valid SOCKS5 Proxy Mode metadata may be displayed for
review, but it is not configured.

## Manual configuration

The required inputs are:

```text
VPN Server:     <certificate-matching hostname or IPv4 address>
Username:       <configured EAP user>
Password:       <configured EAP password>
CA Certificate: ca-cert.cer
```

Do not copy the CA private key, server private key, or any server-certificate
private key to Android. The public `ca-cert.cer` is sufficient. Portable import
embeds this same public certificate, so a separate CA file is unnecessary for
an imported profile.

The app currently uses the configured server address as the gateway/remote IKE
identity and the username as both the local IKE identity and EAP-MSCHAPv2
username. This matches the tested server configuration but does not represent
every possible IKE identity policy.

The certificate file must contain exactly one currently valid X.509
certificate. The app shows its subject, issuer, CA status, and SHA-256
fingerprint, stores canonical DER in private app storage, and passes it directly
as `serverRootCa`. It does not install a global CA or bypass server identity
checks.

## Build

Configure the local Android SDK through Android Studio or an untracked
`local.properties`, then run from the repository root:

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project structure

Production code is under `app/src/main/java/com/saeed/ikev2vpn/`:

- `certificate/` — bounded document import, parsing, and SHA-256 fingerprints
- `data/` — profile/theme preferences and private canonical CA storage
- `profile/` — bounded `.ikev` reads and strict schema validation
- `vpn/` — native provisioning, SDK compatibility, state, and events
- `ui/` — Compose screens and ViewModel/StateFlow state
- `validation/` — profile and server-address validation
- `app/src/test/` — hermetic JVM tests

## Release build and signing

Release APKs never fall back to the debug key. Create the production key once
and store it securely outside the repository:

```bash
keytool -genkeypair -v \
  -keystore /secure/path/ikev2-android-release.jks \
  -alias ikev2-android \
  -keyalg RSA -keysize 4096 -validity 10000
```

Provide all four signing values through the environment. The prompts keep
passwords out of shell history:

```bash
export ANDROID_KEYSTORE_PATH=/secure/path/ikev2-android-release.jks
export ANDROID_KEY_ALIAS=ikev2-android
read -rsp "Keystore password: " ANDROID_KEYSTORE_PASSWORD; export ANDROID_KEYSTORE_PASSWORD; printf '\n'
read -rsp "Key password: " ANDROID_KEY_PASSWORD; export ANDROID_KEY_PASSWORD; printf '\n'
```

An explicit release task fails when any signing value is missing. Debug builds,
tests, and lint do not read or require signing credentials.

After completing [RELEASE_TESTING.md](RELEASE_TESTING.md), build v1.1.0 with:

```bash
./gradlew --no-daemon --no-configuration-cache clean testDebugUnitTest lintDebug assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/ikev2-android-v1.1.0.apk
apksigner verify --verbose --print-certs dist/ikev2-android-v1.1.0.apk
(cd dist && sha256sum ikev2-android-v1.1.0.apk > ikev2-android-v1.1.0.apk.sha256)
(cd dist && sha256sum -c ikev2-android-v1.1.0.apk.sha256)
unset ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
```

Keep `--no-daemon --no-configuration-cache` on signed-release commands so the
signing process exits after the build and Gradle does not persist values passed
to the Android plugin during configuration. Retain the signing-certificate
SHA-256 digest with the release record and keep the same production signing key
for every update.

`dist/` is ignored. A human release owner may create the tag
`android-v1.1.0` only after validation; no build or CI command creates tags,
publishes releases, or uploads production artifacts.

## Testing

The normal unsigned validation suite is:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions runs the same checks for pushes and pull requests. Real-device
testing on both API 30–32 and API 33+ remains mandatory because JVM tests cannot
establish an IKEv2 tunnel. Use [RELEASE_TESTING.md](RELEASE_TESTING.md) as a
checklist, not as evidence that testing has already passed.

## Security notes

- The VPN password remains only in a non-saveable UI field for the current
  provisioning operation and is not copied into StateFlow, DataStore, files,
  backups, logs, or diagnostics.
- Only public CA certificates belong on the device or in portable profiles.
- Android backup and cleartext application traffic are disabled in the
  manifest.
- Release signing secrets are read only for explicit release packaging tasks.
- Keystores, local signing properties, local SDK paths, build outputs, and
  release staging are ignored by Git.
- CI performs unsigned debug validation and has no production signing secrets.

## Roadmap

Potential future work includes a separate local IKE identity, multiple
profiles, additional routing modes, and device-validated release automation.
These are not part of the current v1.1 scope.

## License

This project is available under the [MIT License](LICENSE).
