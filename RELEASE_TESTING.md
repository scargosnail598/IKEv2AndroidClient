# Android v1.1.0 Release Testing

Complete this checklist against the release candidate before creating
`android-v1.1.0`. Record only observed results as `PASS`, `FAIL`, or `BLOCKED`;
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
- [ ] Enter the password, select **Save / Provision VPN**, approve Android VPN
      consent, and confirm provisioning succeeds.
- [ ] Reprovision through **Edit Profile** with valid manual configuration and
      the correct public CA.
- [ ] Deny VPN consent independently; confirm no provisioned state is retained
      and a later valid attempt succeeds.

## Connection and network behavior

- [ ] Select **CONNECT**; confirm Connected appears only with Android platform
      evidence and the server reports an established IKEv2 session.
- [ ] Confirm a virtual IP is assigned and DNS resolves a fresh hostname.
- [ ] Confirm all IPv4 Internet traffic works and public IPv4 matches the VPN
      server.
- [ ] Select **DISCONNECT**; confirm the tunnel closes and the original network,
      DNS behavior, Internet access, and public IPv4 return.
- [ ] Connect again after disconnect and repeat the network checks.
- [ ] Relaunch with a provisioned but disconnected profile; confirm
      connect/disconnect still works and no false state appears.

## `.ikev` import rejection checks

Perform each case independently and restore the valid profile afterward:

- [ ] Empty file and malformed JSON are rejected.
- [ ] Unsupported schema version is rejected.
- [ ] Missing required profile, server, username, authentication, tunnel-mode,
      or certificate fields are rejected.
- [ ] Malformed Base64 certificate data is rejected.
- [ ] A non-CA, expired, or not-yet-valid certificate is rejected.
- [ ] A CA fingerprint mismatch is rejected.
- [ ] Unsupported authentication or tunnel mode is rejected.
- [ ] A Remote ID that differs from the server is rejected.
- [ ] Rejection does not overwrite the previously committed profile or CA.

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
- [ ] Confirm the password is absent from UI state, app-private preference data,
      files, backups, captured logs, and screenshots after provisioning.

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

Do not mark v1.1.0 ready while any required gate has failed or remains
unexplained. Attach only sanitized evidence and record device-specific quirks.
