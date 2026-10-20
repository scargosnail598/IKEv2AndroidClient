# Android v1.4.6 Release Testing

Complete this checklist against the release candidate before creating
`android-v1.4.6`. Record only observed results as `PASS`, `FAIL`, or `BLOCKED`;
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
- [ ] Enter the password, select **Save profile**, approve Android VPN
      consent, and confirm provisioning succeeds.
- [ ] Disconnect and reconnect without another password prompt; confirm the
      stored encrypted credential authenticates successfully.
- [ ] Save through **Profiles → More → Edit configuration** with valid manual configuration and
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

Do not mark v1.4.6 ready while any required gate has failed or remains
unexplained. Attach only sanitized evidence and record device-specific quirks.

## Connection and profiles UX

- [ ] Check both tabs in light/dark mode, portrait/landscape, at 320dp width and
      enlarged system font. Keep names readable, actions reachable, and the
      editor save button above the keyboard.
- [ ] Start with no profiles. Add through all four import methods and manual
      setup, cancel, and confirm that no configuration is saved before Save.
- [ ] Browse profiles during an active tunnel. Mutations must stay disabled;
      returning to Connection must preserve the current session and its timer.
- [ ] Rename selected and inactive profiles. Verify names persist after restart
      and that selection, credentials, certificates, and app routing are retained.
- [ ] Edit an inactive profile without activating it. Cancel to retain the
      original; save to select it. Reuse a saved password or explicitly replace it.
- [ ] Delete an inactive, selected, and final profile separately. Cancel first,
      then confirm. Retain all other profiles and their saved credentials.
- [ ] Connect, disconnect, reconnect, and reopen the app. Only a confirmed active
      tunnel should display current-session timing and ID; no previous-session
      values should remain after disconnect.
- [ ] Verify per-app routing and certificate details expand when validation
      needs them, private CA consent remains available, and diagnostics/appearance
      stay reachable from the Connection menu.

## VPN session traffic

- [ ] Connect with IPv4 and IPv6, download a known file, then upload one. Verify
      Download/In and Upload/Out increase in the correct directions and update
      about once per second, including with the screen reopened.
- [ ] With per-app routing enabled, transfer from a bypassed app and verify it
      does not increase the TUN totals. Allow for background VPN app traffic.
- [ ] Rekey and change Wi-Fi/mobile networks; retain totals within the same
      native connection. Disconnect/reconnect and switch profiles; start fresh
      totals without briefly showing the previous session's values.
- [ ] Before connecting, after disconnecting, and when native stats are
      unavailable, show dashes. Confirm the zero-traffic connected case displays
      a real zero rather than a loading/unavailable value.
- [ ] Check both light/dark mode and enlarged fonts. Verify readable KiB/MiB/GiB
      values and that traffic updates do not interrupt scrolling or profile navigation.

## About and in-app updater

- [ ] About displays version 1.4.6 / code 12, the build type, source link, and update controls.
- [ ] Verify no-release, offline, GitHub rate-limit, already-current, and newer-release states.
- [ ] Download a newer official release with the same signing key; verify progress,
      cancellation/retry, release notes, SHA-256, and no profile/credential changes.
- [ ] Reject checksum/size mismatch, truncated APK, wrong package, non-increasing
      versionCode, unexpected versionName, unsupported minSdk, and incompatible keys.
- [ ] Deny unknown-source installation, return to the app, allow it, and retry.
      Cancel Android's installer, then retry installation from the ready state.
- [ ] Rotate during download and installation-permission setup. Background/return
      and cancel/retry without duplicate downloads or installer launches.
- [ ] Complete a same-key upgrade on API 30 and API 33+; verify profiles, per-app
      routing and saved passwords survive. Reopen About and confirm the new version.
- [ ] Installation may restart an active VPN. Verify reconnection afterward;
      a debug build must clearly reject a production APK signed by another key.

## v1.4.1 publication record — 2026-10-18

This first official release uses a new production signing key. It cannot replace
the locally signed debug APK in place. Reimport profiles after removing a debug
installation; later official releases must retain this production signing key.

The maintainer requested publication with this known exception: physical-device
release validation has not been performed in this environment. The API 30–32 and
API 33+ device rows remain unverified, including end-to-end installation and VPN
reconnection after upgrade. Publication does not mark those checks as passed.
Automated results and signed-artifact verification are recorded in the release
notes; the unchecked device checklist remains required follow-up work.

## v1.4.2 widget and upgrade checks

The maintainer requested this update to test the in-app updater from official
v1.4.1. It retains the same production signing key, with versionCode increased
from 7 to 8. Physical launcher behavior and end-to-end installation remain
unverified in the build environment; these are the requested device checks:

- [ ] On official 1.4.1, check updates, download 1.4.2, allow installation if
      requested, and install without uninstalling. Verify profiles are retained.
- [ ] Reopen About: version 1.4.2 / code 8; checking again reports current.
- [ ] Add IKEv2 VPN through the launcher widget picker on API 30 and API 31+.
- [ ] Verify selected profile, connected/disconnected/transitional/error states,
      and no false connected state during startup.
- [ ] Tap the widget from a cold app, About, Profiles, and a clean editor; verify
      the Connection screen opens and the normal connection controls work.
- [ ] Add two widget instances, resize with large text, remove one, then remove
      all. Re-add and verify updates resume. Check after reboot and app update.

## v1.4.3 notification controls

- [ ] On Android 13+, after profile setup, allow notifications. Verify one
      permission request, no repeated prompts after denial, and recovery through
      More → Notifications. Check permission and channel blocking separately.
- [ ] With the app backgrounded, tap Connect on the disconnected notification;
      verify the selected profile connects without an Activity opening.
- [ ] Tap Disconnect while connected and while connecting; verify teardown without
      a confirmation Activity, followed by one idle Connect notification.
- [ ] Test error/retry, double taps, stale actions after profile deletion or
      pending setup, and cold-process actions. Verify no credential is in a
      PendingIntent or public notification.
- [ ] Revoke VPN consent; verify an actionable error, then finish consent in-app.
- [ ] On API 30 and API 33+, verify foreground service timing, notification
      transitions, lock-screen privacy, channel settings and launcher widget.

Physical notification interaction and device installation remain unverified
in this build environment. Automated tests cover action guards and idle-display
policy. This change does not add a Quick Settings tile.

### v1.4.3 publication record — 2026-10-18

The maintainer requested publication after the local signed build, 140 JVM
tests, native traffic tests and lint passed. Physical notification interaction
and the API 30–32 / API 33+ device matrix remain unverified in this environment;
publication proceeds with that documented exception and does not mark those
device checks as passed. The existing production signing key is retained.

## v1.4.4 server ping

- [ ] On a connected home screen, verify Server ping shows ICMP RTT in ms and
      the selected server hostname. Compare against a device ICMP ping.
- [ ] Block ICMP or use a nonresponsive server; verify Unavailable replaces old
      measurements without changing the VPN connection status.
- [ ] Disconnect/reconnect or switch profiles/sessions; verify the old latency
      disappears. Leave Connection or background the app: sampling must stop.
- [ ] Check ALL, ONLY_SELECTED and EXCLUDE_SELECTED routing. Outside VPN must
      appear when the VPN app is excluded; routing must not be modified.
- [ ] Verify cold start, absent ping utility, large font and narrow layouts.

Physical-device ICMP execution and launcher/network transitions remain unverified
in this build environment. Automated tests cover parsing, bounded commands,
process cleanup/cancellation, sampling reset and latency formatting.

### v1.4.4 publication record — 2026-10-18

The maintainer requested publication after the local signed build, 149 JVM
tests, native traffic tests and lint passed. Physical-device ICMP execution
and the API 30–32 / API 33+ device matrix remain unverified in this environment.
Publication proceeds with that documented exception and does not mark those
device checks as passed. The existing production signing key is retained.

## v1.4.5 manual server ping

- [ ] Connect and verify one initial ICMP echo runs. Wait at least 30 seconds:
      the result must stay unchanged and no periodic echoes should run.
- [ ] Tap the Server ping row: show Checking, send one echo, then keep its result.
      Tap repeatedly while checking: only one probe may run at a time.
- [ ] Switch to Profiles/About and back, background/resume, and rotate the device:
      retain the result for the same session without starting another echo.
- [ ] Block ICMP: one test shows Unavailable and stays there without automatic
      retries. Unblock it and tap again: show the new result.
- [ ] Disconnect during a probe and reconnect: cancel the old test, clear its
      result and run one initial test for the new session. Late replies from the
      old session must never replace the new reading.
- [ ] Verify TalkBack announces a test action, a usable touch target, and readable
      layout with large fonts. Disconnected/checking rows must not trigger tests.
- [ ] Upgrade the signed v1.4.4 APK to v1.4.5 / code 11 without losing profiles.

Physical-device ping execution, UI interaction and installation remain unverified
in this build environment. Automated coverage checks one-shot sampling, retained
results, explicit refresh, overlapping-tap prevention, connection changes and
cancellation/stale replies; device checks above are still required.

### v1.4.5 publication record — 2026-10-18

The maintainer requested publication after the local signed build, 157 JVM
tests, native traffic tests and lint passed. Physical-device ping execution,
UI interaction, installation and the API 30–32 / API 33+ device matrix remain
unverified in this environment. Publication proceeds with that documented
exception and does not mark those device checks as passed. The existing
production signing key is retained.


## v1.4.6 configuration sharing

- [ ] On private-CA and public-trust profiles, open Profiles → ⋯ → Share link / QR.
      Scan with a second device and import the shared PNG through Choose a QR image.
      Verify server, username, profile name, CA fingerprint/trust and successful setup.
- [ ] Copy/paste and share the link through another app; test both optional-password
      states. Without a password the recipient must enter one; with a password
      leave the password field empty and verify it is used after explicit Save.
- [ ] Share selected and inactive profiles while connected. Verify the correct
      profile/credential is exported and the active VPN remains unchanged.
- [ ] Toggle Include saved password repeatedly. Never show or share the previous
      QR/link while regenerating. Missing credentials and oversized configurations
      must show a useful error; disabling the option must permit retry.
- [ ] Verify light/dark mode, 320dp width, large fonts, TalkBack, scrolling, rotation,
      background/resume, share cancellation, and absence of a receiving app.
      Backgrounding/rotation must close the dialog and reset password inclusion.
- [ ] Confirm sharing remains local, no network request or camera permission is
      needed, and per-app routing remains on the source device.


### v1.4.6 publication record — 2026-10-20

The maintainer requested an official release to update through the app after
167 JVM tests, native traffic-counter checks, Android lint and the debug build
passed. Physical-device QR scanning/sharing, UI interaction, in-place installation
and the API 30–32 / API 33+ device matrix remain unverified in this environment.
Publication proceeds with this documented exception and does not mark the device
checks as passed. The release retains the existing production signing key and
increases versionCode from 11 to 12. Local production-signed build, APK signature verification and checksum verification
passed. The signing certificate SHA-256 matches official v1.4.5:
`9dd62e924ae88a07c49e180173469b70d90efad5235172fe0c4f33d27cd453eb`.
The publication workflow repeats automated validation and publishes the signed artifacts.
