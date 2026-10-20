# Changelog

All notable changes to this project will be documented in this file. The format
is based on Keep a Changelog, and the project uses semantic versioning for the
Android application version.

## [Unreleased]

### Changed

- Normalized the extracted Android project for standalone development and CI.
- Expanded repository hygiene, release, testing, and security documentation.

## [1.4.9]

### Added

- Three optional color/font themes: Terminal green, Amber console, and Rosewood,
  with live previews, saved selection, and independent light/dark appearance.

## [1.4.8]

### Added

- Device-wide traffic bypass with separate domain-suffix and country-IP options,
  explicit country-list downloads and expiring DNS-derived bypass routes.

## [1.4.7]

### Changed

- Simplified the Connection screen by replacing the separate session card with
  a compact account, routing and connection-start summary beneath Selected profile.

## [1.4.6]

### Added

- Share saved VPN configurations as offline links or QR images from Profiles.
- Optional password inclusion and private/public CA trust in shared configurations.

## [1.3.1]

### Fixed

- Encrypt the EAP password with Android Keystore when provisioning so later
  connections do not ask for it again.

## [1.3.0]

### Changed

- Replaced Android's native IKEv2 VPN manager with the bundled strongSwan engine.
- Added strongSwan profile provisioning, service status, and persistent in-app
  protocol diagnostics.
- Changed the project license from MIT to GPL-2.0-or-later for compatibility
  with the integrated strongSwan Android client.

## [1.1.0]

### Added

- Portable `.ikev` schema version 1 import.
- Native Android IKEv2/IPsec profile provisioning and connection controls.
- Certificate validation, sanitized diagnostics, and persistent theme choice.

Release dates are intentionally omitted because no tagged-release date is
present in this repository's current Git history.
