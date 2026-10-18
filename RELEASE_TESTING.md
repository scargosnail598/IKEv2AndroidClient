# Android v1.3.1 Release Testing

Complete this checklist against the release candidate before creating
`android-v1.3.1`. Record only observed results as `PASS`, `FAIL`, or `BLOCKED`;
this document is not evidence that any test has already passed.

## Device record matrix

| Device | Android version | API level | Manufacturer | Build type | Result | Notes |
|---|---|---:|---|---|---|---|
| Device A |  | 30–32 |  | Signed release |  |  |
| Device B |  | 33+ |  | Signed release |  |  |

Prefer different OEM families when practical. Record the APK SHA-256 and test
date in Notes. A stable release requires both API rows to pass; document and
review any exception.

## Test setup

- Use a disposable EAP account and the server's public `ca-cert.cer`.
- Prepare a valid v1 `.ikev` profile for that account.
- For public-CA server mode, prepare a public-trust `.ikev` profile and verify
  that its certificate is trusted by strongSwan's system trust store.
- Record the device's original public IPv4, DNS behavior, and active network.
- Verify the signed APK checksum before installation.
- Capture only sanitized logs and screenshots.
- Compare the imported CA fingerprint with a trusted value:

  ```bash
  openssl x509 -inform DER -in ca-cert.cer -noout -fingerprint -sha256
  ```

## Installation and provisioning

- [ ] Install the signed release APK without relying on a debug installation.
- [ ] Launch successfully and confirm the app reports the profile as not
      configured on a clean install.
- [ ] Import a valid `.ikev` file; verify profile name, server, username, CA
      summary, and fingerprint before provisioning.
- [ ] Import a public-trust `.ikev` profile; confirm the UI identifies Android
      system CA validation and the profile provisions without an embedded CA.
- [ ] Enter the password, select **Save / Provision VPN**, approve Android VPN
      consent, and confirm provisioning succeeds.
- [ ] Disconnect and reconnect without another password prompt; confirm the
      stored encrypted credential authenticates successfully.
- [ ] Reprovision through **Edit Profile** with valid manual configuration and
      the correct public CA.
- [ ] Deny VPN consent independently; confirm no provisioned state is retained
      and a later valid attempt succeeds.

## Connection and network behavior

- [ ] Select **CONNECT**; confirm Connected appears only with strongSwan service
      evidence and the server reports an established IKEv2 session.
- [ ] Confirm a virtual IP is assigned and DNS resolves a fresh hostname.
- [ ] Confirm all IPv4 Internet traffic works and public IPv4 matches the VPN
      server.
- [ ] Select **DISCONNECT**; confirm the tunnel closes and the original network,
      DNS behavior, Internet access, and public IPv4 return.
- [ ] Connect again after disconnect and repeat the network checks.
- [ ] Relaunch with a provisioned but disconnected profile; confirm
      connect/disconnect still works and no false state appears.

## Per-app VPN routing

- [ ] Upgrade from an older build and verify the existing profile defaults to
      **All apps**, with the same network behavior as before.
- [ ] Choose **Only selected apps use VPN**, select one browser, save, and
      reconnect. Verify its public IP/DNS behavior uses the VPN while another
      browser uses the regular network. Repeat after Wi-Fi/mobile changes.
- [ ] Choose **Selected apps bypass VPN**, select one browser, save, and
      reconnect. Verify the selected browser bypasses and another uses the VPN.
- [ ] Save **All apps** after testing both modes; confirm prior restrictions
      are cleared from the reused engine profile.
- [ ] Verify empty include selection prevents saving; uninstall every selected
      app and reconnect, confirming no all-app tunnel is established.
- [ ] Uninstall just one of multiple included apps; the remaining selected apps
      should still use the VPN. Remove the unavailable app in the picker.
- [ ] Search by name/package; verify system-app filtering, selection retention,
      and selection of an internet-capable app without a launcher activity.
- [ ] Cancel edits, rotate, relaunch, and switch between profiles with different
      app selections. Confirm each saved configuration retains its own mode and list.
- [ ] Check bypass behavior with Android always-on VPN and **Block connections
      without VPN**, and record shared-UID or device-specific restrictions.

## `.ikev` import rejection checks

Perform each case independently and restore the valid profile afterward:

- [ ] Empty file and malformed JSON are rejected.
- [ ] Unsupported schema version is rejected.
- [ ] Missing required profile, server, username, authentication, tunnel-mode,
      or certificate fields are rejected.
- [ ] Malformed Base64 certificate data is rejected.
- [ ] A non-CA, expired, or not-yet-valid certificate is rejected.
- [ ] A CA fingerprint mismatch is rejected.
- [ ] A public-trust profile with embedded CA data or an unknown trust mode is
      rejected.
- [ ] Unsupported authentication or tunnel mode is rejected.
- [ ] A Remote ID that differs from the server is rejected.
- [ ] Rejection does not overwrite the previously committed profile or CA.

## Configuration link and QR import

- [ ] Export public-trust and private-CA links from **Users → Link & QR**, each
      with and without a password. Paste each into Android and compare the
      server, Remote ID, username, CA fingerprint, and proxy summary.
- [ ] Scan the panel's QR, including a dense 4096-bit CA export, in portrait and
      landscape. Verify one result, preview release, and back/cancel behavior.
- [ ] Import a downloaded QR PNG and a rotated QR image. Reject a non-QR image,
      malformed image, image over 16 MiB, and unrelated QR text without changing
      the committed profile. Check image import after camera permission denial.
- [ ] Deny/revoke camera permission, try again from app settings, and verify
      text/file/image import works on a device without a camera.
- [ ] Open an `ikev://import/v1/…` link while the app is closed and while it is
      already open. Rotate/recreate the Activity and verify no duplicate import.
- [ ] Verify importing creates a new profile by default, retains earlier
      profiles, and resets per-app routing to All apps. Explicitly select a
      matching saved profile for replacement; cancel and confirm separately.
- [ ] Confirm a password-bearing share requires no retyping, permits an
      explicitly typed replacement, and stores credentials only after saving.
      A password-free share must prompt for a password.
- [ ] Background, rotate, cancel, change identity, and start another import;
      verify the previous included password is discarded. Repeat backgrounding
      while image/link decoding is still in progress.
- [ ] Confirm private CA trust and the complete fingerprint before saving.
      Import must never auto-connect, bypass VPN consent, or change an active
      tunnel. Reject imports during connection/provisioning/consent operations.
- [ ] Reject altered URI structure, future versions, invalid Base64/zlib,
      truncated/trailing streams, oversized output, duplicate/deep JSON,
      invalid password fields, and a CA fingerprint mismatch. Keep the prior
      committed profile intact and exclude full links/passwords from diagnostics.

## Authentication, identity, and failure checks

- [ ] Incorrect password fails without a false Connected state.
- [ ] Incorrect or unrelated CA fails server authentication safely.
- [ ] Wrong server identity, such as an alias absent from the certificate SAN,
      fails safely.
- [ ] Unreachable server produces a conservative timeout/error and can be
      stopped or reset.
- [ ] Invalid hostname is rejected before provisioning.
- [ ] Empty or malformed standalone certificate import is rejected without
      replacing the committed profile or CA.
- [ ] Every failure can recover using valid inputs and ordinary networking
      remains usable.

## Lifecycle, persistence, and diagnostics

- [ ] Rotate during setup, consent, connection, and disconnection; confirm no
      crash, duplicate operation, or incoherent profile state.
- [ ] Background and resume the app during connection transitions; confirm the
      state refreshes conservatively.
- [ ] Relaunch after provisioning; confirm non-secret profile data remains and
      the password field is blank.
- [ ] Switch between light and dark mode; confirm all screens remain readable
      and the selection survives relaunch and rotation.
- [ ] Confirm diagnostics are sanitized and contain no VPN password or complete
      credentials.
- [ ] Confirm the plaintext password is absent from UI state, DataStore,
      backups, captured logs, and screenshots after provisioning; confirm the
      credential preference contains only ciphertext and an IV.

## Release sign-off

| Gate | Result | Evidence / Notes |
|---|---|---|
| API 30–32 full checklist |  |  |
| API 33+ full checklist |  |  |
| `testDebugUnitTest` |  |  |
| `lintDebug` |  |  |
| `assembleDebug` |  |  |
| Signed `assembleRelease` |  |  |
| APK signature verified |  |  |
| APK checksum verified |  |  |
| Signing key backup verified |  |  |

Do not mark v1.3.1 ready while any required gate has failed or remains
unexplained. Attach only sanitized evidence and record device-specific quirks.
