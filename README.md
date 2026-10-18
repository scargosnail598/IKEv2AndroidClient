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
- Multiple IPv4 VPN profiles with separate credentials and routing settings
- strongSwan VPN service provisioning and Android VPN consent
- Connect, disconnect, reconnect, and strongSwan-confirmed status
- Private CA import from DER or PEM
- Portable `.ikev` schema version 1 import with private or public CA trust
- Manual profile configuration
- Sanitized diagnostics
- Persistent light/dark theme selection
- Per-profile app routing: all apps, only selected apps, or selected apps bypass VPN

## Current limitations

The app does not currently support IPv6 literals, QR
provisioning, SOCKS5 Proxy Mode, custom DNS or
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

## Import a configuration link or QR

In the server web panel, open **Users → Link & QR**, optionally include the VPN
password, then generate the link/QR. Android supports the server's offline
`ikev://import/v1/<payload>` transport. No panel URL, login, or network request
is needed to read it.

On the Android setup screen choose:

- **Import configuration link** to enter a link or explicitly paste from the clipboard.
- **Scan QR** to use the camera; permission is requested only when scanning.
- **QR image** to select a downloaded PNG/JPEG through Android's document picker.
- **Import .ikev Profile** to continue using the existing portable file format.

Opening an `ikev://import/v1/…` link in an app that supports custom URI schemes
also opens the review screen. Some browsers, messengers, and stock camera apps
do not make custom-scheme text clickable; use paste or the in-app scanner then.
Scanning uses CameraX and bundled ZXing Core, without Google Play Services or an
external QR service. Camera access is optional; image and text import still work
when permission is denied or no camera is available.

Review the server, Remote ID, username, and certificate trust before saving.
For a private CA, compare the displayed SHA-256 fingerprint with a trusted
source and confirm trust. The matching embedded fingerprint checks consistency;
it does not authenticate the sender. Importing alone does not save, connect,
or disconnect the VPN. Disconnect an active VPN before importing another profile.
Imports create a **new profile** by default. If a saved profile has the same
server and username, **Replace a matching saved profile…** explicitly selects
that target; final confirmation is still required. New imports start with
**All apps** routing, which you can change before saving.

When the link includes a password, leave the password field empty to use it or
enter a replacement. When omitted, enter the VPN password yourself. Included
passwords stay outside UI/saved state and are discarded on backgrounding,
rotation, cancellation, identity edits, or a new import. After that, re-enter
the password or import again. Only confirmed provisioning uses the existing
Android Keystore encrypted credential store. Links and passwords are excluded
from diagnostics; the clipboard is read only on an explicit paste action.

The decoder accepts only the exact v1 URI, up to 2953 ASCII bytes, with unpadded
Base64URL and zlib-wrapped DEFLATE. It bounds decompressed JSON to 64 KiB, rejects
trailing/incomplete streams, malformed or deeply nested JSON, duplicate keys,
and unsupported share/profile versions, then reuses the `.ikev` profile and CA
validator. Selected QR images are bounded to 16 MiB encoded and 2048 pixels on
the longest decoded edge. Links are compressed, not encrypted or signed, and
have no built-in expiry; distribute password-bearing links/images privately.

The interoperability contract and frozen test fixtures follow server commit
`39885b00d5a2bfdba10c2c609ccb91a12badc4b5`:
[configuration sharing](https://github.com/scargosnail598/myIkev2-connection/blob/39885b00d5a2bfdba10c2c609ccb91a12badc4b5/docs/config-sharing.md).

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

## Per-app VPN

Disconnect, open **Edit Profile**, and use **Per-app VPN**:

- **All apps** keeps the normal full-tunnel behavior.
- **Only selected apps use VPN** sends the selected apps through the tunnel;
  other apps use the regular network. At least one app must be selected.
- **Selected apps bypass VPN** sends all other apps through the tunnel.

Use **Select apps** to search by app label or package name. **Show system apps**
includes system packages with internet access. Selected system apps stay visible
when the toggle is off. Unavailable saved apps remain listed so they can be
removed. Enter the password and choose **Save / Provision VPN**, then reconnect.
Canceling profile edits discards routing changes along with other unsaved fields.
Routing preferences survive relaunch and are retained separately for each saved
profile. New profiles and profiles from older builds default to **All apps**.

The app's own package is excluded by strongSwan. App routing uses Android's
VPN app filtering; it does not create an HTTP or SOCKS proxy. Existing `.ikev`
proxy metadata remains informational and app choices are stored locally.
If every app in an include list has been uninstalled, tunnel creation fails
instead of accidentally applying the VPN to all apps, including on reconnect.
Apps sharing a UID may share routing behavior. Android's always-on VPN option
**Block connections without VPN** can block bypassed apps; verify the intended
behavior on the target device.

The picker uses `QUERY_ALL_PACKAGES` because per-app routing includes arbitrary
packages without launcher activities. App inventory is read locally and is not
transmitted. Distribution through Google Play requires review of its
[package visibility permission policy](https://support.google.com/googleplay/android-developer/answer/10158779)
and the applicable permission declaration.
