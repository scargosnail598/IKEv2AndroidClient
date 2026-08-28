# IKEv2 Android Client — Roadmap & TODO

This document captures the current project review and the agreed development direction so future work can continue in a deliberate order without losing context.

Snapshot basis: `main` at commit `5ce94ad7a43f7293302689e273532f1adbf7b60f` (`ci added`), reviewed on 2026-08-28.

---

## 1. Current Baseline

The application is already beyond a prototype. The current v1.1.0 baseline includes:

* Android 11+ / API 30+ support.
* Kotlin + Jetpack Compose + Material 3 UI.
* Native Android IKEv2/IPsec through `VpnManager` and `Ikev2VpnProfile`.
* EAP-MSCHAPv2 authentication.
* Hostname or IPv4 server addresses.
* One IPv4 full-tunnel profile.
* Native VPN provisioning and Android consent handling.
* Connect, disconnect, reconnect, and conservative platform-confirmed state reporting.
* API 33+ `VpnProfileState` and protected VPN-manager event handling.
* API 30–32 owned-VPN-network observation for conservative state confirmation.
* Private CA import from DER or PEM.
* Portable `.ikev` schema v1 import.
* Strict certificate/profile validation including CA fingerprint checks.
* Manual profile configuration.
* Sanitized diagnostics.
* Persistent light/dark theme preference.
* VPN password used transiently for provisioning and intentionally not persisted.
* JVM tests for certificate handling, profile import, validation, ViewModel behavior, and VPN state reduction.
* CI for unit tests, lint, and debug assembly.
* Signed release workflow with:

  * tag/version validation,
  * signed APK generation,
  * SHA-256 checksum,
  * APK signature verification,
  * GitHub Release publishing.

---

# 2. Architectural Principles to Preserve

Unless a future requirement clearly justifies a redesign:

1. Prefer Android platform VPN APIs over a custom `VpnService` data plane.
2. Do not implement or embed a custom IKEv2 engine while the platform implementation satisfies the product scope.
3. Do not intercept VPN payload traffic merely to add convenience features.
4. Keep VPN passwords out of:

   * `StateFlow`,
   * DataStore,
   * normal files,
   * backups,
   * logs,
   * diagnostics,
   * screenshots.
5. Keep imported CA material public-only.
6. Never place CA/server private keys on the Android device.
7. Treat Android platform-confirmed VPN state as authoritative.
8. Avoid optimistic false `Connected` states.
9. Keep `.ikev` import bounded, versioned, validated, and backward-compatible.
10. Treat features requiring a custom data plane as separate architectural projects.

---

# 3. Priority Plan

## P0 — Stabilize and Close v1.1.0

Before adding large features, finish release/device validation of the current baseline.

### TODO

* [ ] Complete `RELEASE_TESTING.md` on at least one API 30–32 device.
* [ ] Complete `RELEASE_TESTING.md` on at least one API 33+ device.
* [ ] Prefer different OEM families when practical.
* [ ] Validate the signed release APK, not only debug builds.
* [ ] Verify real full-tunnel behavior.
* [ ] Verify DNS while connected.
* [ ] Verify public IPv4 switches to the VPN server.
* [ ] Verify clean disconnect and restoration of normal connectivity.
* [ ] Verify reconnect.
* [ ] Verify wrong-password behavior.
* [ ] Verify wrong-CA behavior.
* [ ] Verify wrong server identity behavior.
* [ ] Verify unreachable-server behavior.
* [ ] Verify malformed `.ikev` import rejection.
* [ ] Verify recovery after every failure.
* [ ] Verify rotation during provisioning and connection transitions.
* [ ] Verify background/resume behavior.
* [ ] Confirm no VPN password appears in persisted state, logs, diagnostics, backups, screenshots, or repository artifacts.
* [ ] Record OEM/device-specific quirks.
* [ ] Create/tag stable v1.1.0 only after required gates pass.

### Test Infrastructure Follow-up

The project currently has JVM tests but no meaningful `androidTest` layer.

* [ ] Add minimal instrumentation test infrastructure.
* [ ] Add Compose UI test infrastructure.
* [ ] Test important setup-screen interactions.
* [ ] Test lifecycle/recreation-sensitive UI state.
* [ ] Test consent-result handling where practical.
* [ ] Do not treat emulator tests as proof that a real IKEv2 tunnel works.
* [ ] Keep real-device VPN validation as a mandatory release gate.

### P0 Exit Criteria

P0 is complete when:

* real-device validation is recorded,
* v1.1.0 behavior is considered a known-good baseline,
* the signed release pipeline produces a verified artifact.

---

# 4. P1 — v1.2: Profile Management & Platform Integration

This should be the next main development phase.

---

## Phase 1 — Multiple Profile Library

### Goal

Allow the application to manage multiple locally saved VPN profiles.

Important platform constraint:

The app can store multiple logical profiles internally, but Android's package-owned `VpnManager` profile model effectively gives the application one provisioned platform profile at a time.

Therefore:

```text
Profile Repository

Profile A
Profile B
Profile C
Profile D
     │
     │ select
     ▼
Selected Profile
     │
     │ provision
     ▼
Android VpnManager
     │
     ▼
One platform-provisioned VPN profile
```

Switching logical profiles therefore means reprovisioning the selected profile into Android when required.

### Data Model

* [ ] Replace single-profile repository assumptions with a profile collection.
* [ ] Introduce stable profile IDs independent of display names.
* [ ] Store per-profile:

  * display name,
  * server address,
  * username,
  * canonical CA certificate,
  * CA subject,
  * CA issuer,
  * CA SHA-256 fingerprint,
  * provisioning metadata,
  * imported `.ikev` metadata where applicable.
* [ ] Add `selectedProfileId`.
* [ ] Add active/platform-provisioned profile metadata.
* [ ] Add migration from v1.1 single-profile storage.
* [ ] Preserve existing profiles during migration.
* [ ] Keep passwords non-persistent by default.

### Target UI

```text
My VPNs

● Germany
  vpn-de.example.com
  Connected

○ Netherlands
  vpn-nl.example.com

○ Office
  vpn.company.com

+ Import .ikev
+ Add manually
```

### UI TODO

* [ ] Add profile-list/home screen.
* [ ] Add profile detail screen.
* [ ] Add manual profile creation.
* [ ] Add `.ikev` import into the profile library.
* [ ] Add edit profile.
* [ ] Add delete profile with confirmation.
* [ ] Clearly indicate selected profile.
* [ ] Clearly indicate currently connected profile.
* [ ] Prevent profile switching during unsafe connection transitions.
* [ ] Explain when password re-entry is required.
* [ ] Preserve existing profiles if provisioning a new profile fails.

### Provisioning Workflow

Define a deterministic profile switch:

```text
Select profile
    ↓
Check current VPN state
    ↓
Disconnect current VPN if necessary
    ↓
Load selected profile
    ↓
Request password if provisioning required
    ↓
Provision with Android
    ↓
VPN consent if needed
    ↓
Verify provisioning
    ↓
Commit selected profile
    ↓
Ready to connect
```

### Safety Requirements

* [ ] Never mark the new profile active before Android accepts provisioning.
* [ ] Preserve previous logical profiles if provisioning fails.
* [ ] Recover cleanly after denied VPN consent.
* [ ] Recover cleanly after Android rejects the profile.
* [ ] Avoid repository/platform state divergence.
* [ ] Do not overwrite unrelated profile data during import.
* [ ] Do not persist password during profile switching.

### Tests

* [ ] Repository migration tests.
* [ ] Multiple-profile CRUD tests.
* [ ] Profile selection tests.
* [ ] Profile switching tests.
* [ ] Failed-switch rollback tests.
* [ ] Consent-denied rollback tests.
* [ ] Import without damaging existing profiles.
* [ ] Delete inactive profile tests.
* [ ] Delete selected profile tests.
* [ ] Delete connected profile behavior tests.

---

## Phase 2 — Better Connection Status & Diagnostics

### Goal

Make the main screen substantially more informative without intercepting VPN traffic.

### Target UI

```text
CONNECTED

Germany VPN
vpn.example.com

Duration       01:42:18
Network        Wi-Fi
VPN state      Confirmed
Internet       Validated
Always-on      Enabled
Lockdown       Disabled
```

### TODO

* [ ] Show active profile name.
* [ ] Show active server.
* [ ] Show connection duration.
* [ ] Show current underlying network type:

  * Wi-Fi,
  * Cellular,
  * Ethernet,
  * Other.
* [ ] Show state evidence source.
* [ ] Show whether state is platform-confirmed.
* [ ] Show connection/session information in Diagnostics.
* [ ] Show Internet validation status where safely observable.
* [ ] Improve recoverable error presentation.
* [ ] Improve terminal error presentation.
* [ ] Add recovery action for `UNKNOWN`.
* [ ] Add recovery action for transition timeout.
* [ ] Add sanitized diagnostic copy/export.
* [ ] Include app version in diagnostics.
* [ ] Include Android API/device information in diagnostics.
* [ ] Include active profile name without exposing password.
* [ ] Never export passwords.
* [ ] Never export private keys.
* [ ] Avoid unnecessary sensitive certificate material.

---

## Phase 3 — Always-on & Lockdown Awareness

### Goal

Integrate with Android's native Always-on VPN behavior rather than creating a custom background reconnect daemon.

### TODO

* [ ] Detect Always-on VPN state where supported.
* [ ] Detect Lockdown / Block connections without VPN state where supported.
* [ ] Show status on main screen or diagnostics.
* [ ] React properly to `CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED`.
* [ ] Add action to open Android VPN settings when possible.
* [ ] Explain to the user that Always-on is controlled by Android.
* [ ] Test Always-on with the application selected.
* [ ] Test Always-on disabled.
* [ ] Test another VPN application being configured as Always-on.
* [ ] Test Lockdown behavior.
* [ ] Verify app state after Android automatically restarts the VPN.

### Non-goal

Do not create a custom always-running auto-reconnect service unless a concrete Android platform limitation is demonstrated.

---

## Phase 4 — API 34+ Reliability Improvements

Evaluate newer `Ikev2VpnProfile.Builder` capabilities behind SDK checks.

### Candidates

* [ ] Automatic IP version selection.
* [ ] Automatic NAT-T keepalive timer selection.
* [ ] Improved Wi-Fi → cellular transition testing.
* [ ] Improved cellular → Wi-Fi transition testing.
* [ ] MOBIKE transition validation.
* [ ] Network-loss recovery validation.
* [ ] OEM comparison.

### Requirements

* Keep API 30+ compatibility.
* Guard newer APIs with SDK checks.
* Do not break current known-good behavior just to enable newer API options.
* Validate reliability changes on real devices.
* Document OEM quirks.

---

## Phase 5 — Better `.ikev` Android Integration

### Goal

Make portable profile onboarding feel native on Android.

### TODO

* [ ] Allow opening `.ikev` files with the application where Android intent handling allows it.
* [ ] Support Open With flows.
* [ ] Support Share-to-app flows where appropriate.
* [ ] Load imported profile into review/setup UI.
* [ ] Never provision immediately after receiving a file.
* [ ] Require explicit user review.
* [ ] Require explicit provisioning action.
* [ ] Preserve strict schema validation.
* [ ] Preserve certificate validation.
* [ ] Preserve fingerprint validation.
* [ ] Preserve authentication-mode validation.
* [ ] Preserve tunnel-mode validation.
* [ ] Add malformed external-intent tests.
* [ ] Define `.ikev` schema migration/version strategy before introducing v2.

---

# 5. P2 — v1.3: Convenience & Onboarding

Start after the v1.2 profile-management foundation is stable.

---

## Quick Settings Tile

### Goal

Connect/disconnect without opening the full application.

### TODO

* [ ] Add Android Quick Settings tile.
* [ ] Show conservative VPN state.
* [ ] Support Connect.
* [ ] Support Disconnect.
* [ ] Handle Connecting.
* [ ] Handle Disconnecting.
* [ ] Handle Unknown/Error.
* [ ] Handle no selected profile.
* [ ] Handle profile requiring reprovision/password.
* [ ] Never show Connected before platform confirmation.

---

## QR Provisioning

### Goal

Provide easy profile onboarding without copying files manually.

### Design Questions

* Reuse `.ikev` JSON directly?
* Compress/base64 it?
* Define a compact transport wrapper?
* Support only QR version 1 initially?

### Security Requirements

* [ ] Never put VPN passwords in QR.
* [ ] Never put private keys in QR.
* [ ] Public CA certificate is acceptable.
* [ ] Validate decoded payload using the existing `.ikev` parser.
* [ ] Limit maximum QR payload size.
* [ ] Reject malformed input.
* [ ] Require review before provisioning.
* [ ] Display server, username, and CA fingerprint before acceptance.

---

## Optional Secure Password Storage

### Status

Decision required.

Current default remains:

```text
VPN password is NOT stored.
```

If this feature is implemented:

* [ ] Make it explicitly opt-in per profile.
* [ ] Use Android Keystore-backed encryption.
* [ ] Never store plaintext password in DataStore.
* [ ] Never store plaintext password in SharedPreferences.
* [ ] Never write plaintext password to files.
* [ ] Never put plaintext password in logs.
* [ ] Never export password through `.ikev`.
* [ ] Allow forgetting the password without deleting the profile.
* [ ] Consider biometric/device-authentication protection.
* [ ] Define recovery behavior.
* [ ] Document the threat model before implementation.

---

## IPv6 / Dual-stack

### TODO

* [ ] Decide whether IPv6 literal VPN server addresses are required.
* [ ] Extend validation first.
* [ ] Add parsing tests.
* [ ] Test IPv6-only networks.
* [ ] Test dual-stack networks.
* [ ] Test dual-stack VPN servers.
* [ ] Test transition between IPv4 and IPv6-capable networks.
* [ ] Preserve existing IPv4 behavior.

---

# 6. P3 — Separate Architectural Investigations

These features should not be added casually to the current architecture.

---

## Per-app VPN

### Status

Deferred / Research first.

The current native `Ikev2VpnProfile` design does not provide the same per-application routing model available through a custom `VpnService`.

Before implementation:

* [ ] Write an Architecture Decision Record (ADR).
* [ ] Define the exact per-app use case.
* [ ] Determine whether custom `VpnService` is required.
* [ ] Determine who performs IKEv2/IPsec if leaving the platform profile model.
* [ ] Evaluate battery impact.
* [ ] Evaluate security impact.
* [ ] Evaluate packet-processing requirements.
* [ ] Evaluate routing and DNS ownership.
* [ ] Evaluate maintenance cost.
* [ ] Keep experimental implementation isolated from the stable native client.

---

## SOCKS5 Proxy Mode

### Status

Deferred / Separate design required.

The server stack can expose a private SOCKS5 endpoint reachable through IKEv2, but Android profile-level proxy configuration is not equivalent to transparent SOCKS5 routing for arbitrary applications.

Before implementation, define the user requirement:

```text
A) Explicit SOCKS5-capable apps
B) Selected apps transparently routed through SOCKS5
C) System-wide proxy behavior
D) Destination-based proxy routing
```

### TODO

* [ ] Choose the actual product requirement.
* [ ] Determine whether platform-managed IKEv2 alone can satisfy it.
* [ ] Determine whether local SOCKS forwarding is required.
* [ ] Determine whether a custom `VpnService` is required.
* [ ] Keep proxy data-plane logic isolated from native VPN provisioning.
* [ ] Do not weaken full-tunnel behavior merely to add SOCKS5 support.

---

## Advanced Split Tunneling

### Status

Deferred until requirements are concrete.

Possible models:

```text
Destination-based split tunnel
Per-app split tunnel
LAN bypass
Include-only routes
Exclude routes
Proxy-only routing
```

### TODO

* [ ] Define actual routing requirements.
* [ ] Verify what native `Ikev2VpnProfile` APIs support.
* [ ] Research API-level differences.
* [ ] Write an ADR if unsupported requirements imply `VpnService`.
* [ ] Avoid introducing routing behavior without real-device tests.

---

# 7. Suggested Release Sequence

## v1.1.0 — Stabilization

Scope:

* Current native single-profile client.
* `.ikev` v1.
* Native platform IKEv2.
* Real-device acceptance.
* Signed release pipeline.

---

## v1.2.0 — Profile Management & Platform Integration

Primary scope:

1. Multiple profile library.
2. Profile selection/switching.
3. Safe reprovisioning.
4. Better connection status.
5. Improved diagnostics.
6. Always-on/Lockdown awareness.
7. API 34+ reliability tuning.
8. Better `.ikev` Android integration.

---

## v1.3.0 — Convenience & Onboarding

Candidates:

1. Quick Settings tile.
2. QR provisioning.
3. Optional secure password storage.
4. IPv6 / dual-stack improvements.

---

## Later / Separate Architecture

* Per-app VPN.
* SOCKS5 transparent routing.
* Advanced split tunneling.
* Custom `VpnService`.
* Custom VPN/IKEv2 engine.

---

# 8. Immediate Next Action

Do not start the multiple-profile refactor yet.

First complete:

```text
P0 — v1.1 Stabilization
```

Reason:

The current single-profile implementation should become the known-good reference baseline before storage, provisioning, navigation, and profile-selection logic become more complex.

Otherwise future failures may be difficult to classify as:

```text
existing Android/OEM behavior
vs
new profile-management regression
```

After P0 is complete:

```text
P1
└── Phase 1
    └── Multiple Profile Library
```

should be the first implementation phase.

---

# 9. Maintenance Rules

Whenever work progresses:

1. Check a TODO only when implementation/test evidence exists.
2. Do not check items based only on planned code.
3. Add newly discovered constraints to the relevant phase.
4. Keep rejected ideas as documented decisions instead of deleting their history.
5. Update this roadmap when a new stable release is tagged.
6. Keep this file aligned with:

   * `README.md`
   * `CHANGELOG.md`
   * `RELEASE_TESTING.md`
7. If a feature changes the fundamental VPN architecture, create an ADR before implementation.
8. Preserve the native-platform architecture unless a documented requirement proves it insufficient.
