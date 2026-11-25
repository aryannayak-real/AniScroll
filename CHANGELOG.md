# Changelog

## [1.1.6] - 2026-11-22

### Added

- Optional password-protected backups using AES-256-GCM with PBKDF2 key derivation. Legacy unencrypted backups remain readable.
- Password entry when restoring encrypted backups.
- Dedicated unit-test workflow for pull requests, pushes to `main`, and manual runs.

### Security

- Novel source plugins are now verified against repository-provided SHA-256 checksums; unverified remote JavaScript is refused.
- Hardened reader and dictionary WebViews by disabling universal file-origin access and restricting mixed content.
- Release builds now trust only system certificate authorities; debug builds may still trust user-installed CAs for local proxies.
- Enabled WebView Safe Browsing.
- Exported video player intents now validate media URI schemes and readability, and restrict YouTube links to trusted hosts.
- Removed the non-functional “Encrypt database” setting.

### Fixed

- Startup no longer blocks the first frame on novel and dictionary directory migrations.
- Download workers no longer block on synchronous WorkManager queries.
- Resumed downloads now detect mismatched `Content-Range` offsets and incomplete files.
- Restore validation now runs off the main thread.
- Backup and restore fail closed if an encryption password expired before the worker started, instead of silently falling back to plaintext.

### Changed

- Aligned the SQLDelight dialect version with the runtime.

## [1.1.7] - 2026-11-22

### Changed

- Replaced the legacy app icon with the new AniScroll logo across the launcher, splash screen, in-app header, widgets, and notifications.

## [Unreleased]

## [1.1.8] - 2026-11-25

### Added

- Online dictionary fallback when no offline dictionary is imported: Jisho/JMdict for Japanese and Wiktionary for Chinese, Korean, and other languages. Offline ZIP dictionary imports remain available.
- Simplified Chinese lookups follow the traditional form on Wiktionary when a page only contains a character redirect.
