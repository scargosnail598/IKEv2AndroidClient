# IKEv2 Android Client

A standalone Android 11+ IKEv2/IPsec client with a Jetpack Compose interface
and the strongSwan userland VPN engine.

## Status and version

The current application version is **1.3.1** (`versionCode = 5`). The Gradle
configuration in `app/build.gradle.kts` is the version source of truth.

The repository is maintained as an independent Android project. Release-device
acceptance remains a manual gate; see [RELEASE_TESTING.md](RELEASE_TESTING.md).

## Key characteristics

```text
Android app
    ↓
StrongSwanVpnController
    ↓
strongSwan Charon + libipsec via JNI/VpnService
    ↓
IKEv2 server
```

- IKEv2, certificate validation, and IPsec run in the bundled strongSwan engine,
  independently of Android's native IKE implementation.
- Android `VpnService` provides the tunnel interface; strongSwan handles
  encrypted network traffic in user space.
- Authentication uses IKEv2 with EAP-MSCHAPv2 and a private CA.
- The EAP password is encrypted with an app-specific, non-exportable Android
  Keystore key when the profile is saved. It is decrypted only when starting
  the strongSwan service and is not stored in the strongSwan profile database.
- Diagnostics intentionally contain no VPN password.

## Requirements

- Android 11 or newer (API 30+)
- JDK 25, matching the checked-in Gradle daemon criteria
- Android SDK 36
- Android NDK 27.3.13750724
- Autoconf, Automake, Libtool, pkg-config, OpenSSL development headers, make,
  Perl, Python 3, jq, and a C/C++ compiler for the native engine build

Gradle compiles application sources for Java 17 bytecode. JDK 25 is the build
runtime selected by `gradle/gradle-daemon-jvm.properties` and is supported by
the pinned Gradle 9.5 wrapper.

## Supported functionality

- Hostname or IPv4 VPN server addresses
- One IPv4 full-tunnel profile
- strongSwan VPN service provisioning and Android VPN consent
- Connect, disconnect, reconnect, and strongSwan-confirmed status
- Private CA import from DER or PEM
- Portable `.ikev` schema version 1 import with private or public CA trust
- Manual profile configuration
- Sanitized diagnostics
- Persistent light/dark theme selection

## Current limitations

The app does not currently support IPv6 literals, multiple profiles, QR
provisioning, SOCKS5 or split-tunnel Proxy Mode, per-app VPN, custom DNS or
routing, Quick Settings, or auto-connect UI.

Connection state and protocol logs come from the strongSwan service and its
`charon.log`; Android's built-in IKEv2 stack is not used.

## Portable `.ikev` import

The app supports `.ikev` schema version 1 only. A portable profile contains
the server, Remote ID, username, and either the private CA certificate with its
SHA-256 fingerprint or `certificate_trust: "public"` for a server
certificate trusted by strongSwan. It may also include informational server/proxy
metadata. It never contains a VPN password or private key. The source JSON and
Base64 certificate text are read once and are not persisted.

1. Export `username.ikev` from the server environment.
2. Copy or share it to the Android device.
3. Open the app and select **Import .ikev Profile**.
4. Select the file and review the server, username, and CA fingerprint.
5. Enter the VPN password and select **Save / Provision VPN**.
6. Approve Android VPN consent if requested, then connect.

Import validates the frozen format and version, EAP-MSCHAPv2 authentication,
Full Tunnel mode, profile/server/username fields, embedded DER CA validity and
CA Basic Constraints and SHA-256 fingerprint for private-CA profiles. Public
trust profiles omit CA data and use strongSwan certificate validation.
Import populates the setup screen but does not provision or replace the Android VPN profile until
the user selects **Save / Provision VPN**.

The strongSwan engine accepts a separate server Remote ID. Imported profile
metadata the UI does not configure, including SOCKS5 Proxy Mode, remains
informational only.

## Manual configuration

The required inputs are:

```text
VPN Server:     <certificate-matching hostname or IPv4 address>
Username:       <configured EAP user>
Password:       <configured EAP password>
CA Certificate: ca-cert.cer (optional for a publicly trusted server certificate)
```

Do not copy the CA private key, server private key, or any server-certificate
private key to Android. The public `ca-cert.cer` is sufficient. Portable import
embeds this same public certificate, so a separate CA file is unnecessary for
an imported profile.

The configured server address is used as the gateway and remote IKE identity;
the username is used for EAP-MSCHAPv2. A private CA is imported into
strongSwan's app-private trust store. No custom CA is required when the server
certificate chains to a trusted system CA.

The certificate file must contain exactly one currently valid X.509
certificate. The app shows its subject, issuer, CA status, and SHA-256
fingerprint, stores canonical DER in private app storage, and installs the trust
anchor in strongSwan's app-private certificate store. It does not install a
global CA or bypass server identity checks. Public CA profiles use strongSwan's
loaded trusted certificate store.

## Build

Configure the local Android SDK through Android Studio or an untracked
`local.properties`, then run from the repository root:

```bash
./gradlew --no-daemon assembleDebug
```

The first build runs `scripts/prepare-strongswan-android.sh`: it generates the
strongSwan Android make configuration and builds OpenSSL's static crypto
library for supported Android ABIs. The pinned OpenSSL source archive is kept
under `third_party/`; generated sources and libraries are ignored.

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
- `vpn/` — strongSwan profile provisioning, service state, and diagnostics
- `ui/` — Compose screens and ViewModel/StateFlow state
- `validation/` — profile and server-address validation
- `app/src/test/` — hermetic JVM tests

## License

This project and the integrated strongSwan Android engine are distributed under
GPL-2.0-or-later. The upstream strongSwan license and its OpenSSL linking
exception are included in `third_party/strongswan/LICENSE`. OpenSSL carries its
own license in the pinned source archive.

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

After completing [RELEASE_TESTING.md](RELEASE_TESTING.md), build v1.3.1 with:

```bash
./gradlew --no-daemon --no-configuration-cache clean assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/ikev2-android-v1.3.1.apk
apksigner verify --verbose --print-certs dist/ikev2-android-v1.3.1.apk
(cd dist && sha256sum ikev2-android-v1.3.1.apk > ikev2-android-v1.3.1.apk.sha256)
(cd dist && sha256sum -c ikev2-android-v1.3.1.apk.sha256)
unset ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
```

Keep `--no-daemon --no-configuration-cache` on signed-release commands so the
signing process exits after the build and Gradle does not persist values passed
to the Android plugin during configuration. Retain the signing-certificate
SHA-256 digest with the release record and keep the same production signing key
for every update.

`dist/` is ignored. After the signed release candidate passes the manual device
checklist, run **Tag Android Release** from the Actions tab on `main`, enter
`1.3.1`, and confirm device validation. The workflow checks that the requested
version matches Gradle, runs unsigned CI validation, creates the annotated tag,
and starts **Android Release** to build and publish the signed APK and checksum.
The Android Release workflow can also be started manually for an existing tag.

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

- The VPN password is absent from StateFlow, DataStore, backups, logs, and
  diagnostics. Only AES-GCM ciphertext and its IV are stored in app-private
  preferences; the encryption key remains non-exportable in Android Keystore.
- Only public CA certificates belong on the device or in portable profiles.
- Android backup and cleartext application traffic are disabled in the
  manifest.
- Release signing secrets are read only for explicit release packaging tasks.
- Keystores, local signing properties, local SDK paths, build outputs, and
  release staging are ignored by Git.
- CI performs unsigned debug validation and has no production signing secrets.

## Roadmap

Potential future work includes a separate local IKE identity, additional routing modes, and device-validated release automation.
These are not part of the current v1.3 scope.

## Multiple profiles

Use **Add Profile** to create or import another configuration. The profile menu
selects a saved configuration after disconnecting and returns to the connection
screen. Each configuration retains its own CA certificate and encrypted password.
Editing is available separately through **Edit Profile**. Profiles from older builds
whose passwords were discarded ask for a password once in a separate dialog.
