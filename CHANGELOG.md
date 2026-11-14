# Changelog

## Unreleased

### Changed
- NetherNet signaling prefers HTTPS and falls back to HTTP with a visible warning.
- The bridge accepts exactly one connected device at a time; a second device receives a clear rejection.
- Microsoft refresh tokens are now stored encrypted using the Android Keystore.
- Signaling messages are validated against the active connection and stale peers are cleaned up.
- The foreground notification now has a stop action, its own icon, and shows the active server.
- Status reporting uses explicit phases and clearer error messages.
- The UI is translatable (German and English), more accessible, and survives rotation.
- Favorites are normalized, sorted, deduplicated, can be named, and mark the active server.
- Update checks are cached, show release notes, and can skip a version.
- Release APKs are built with R8 and resource shrinking.

### Added
- In-app diagnostics with redacted event ring buffer, crash-log rotation, and manual export.
- Battery and notification-permission guidance.
- IPv6 address parsing support.
- Automated JVM tests, Gradle wrapper, CI checks for pushes and pull requests, and tag-based release builds.

### Fixed
- Incorrect bind-error message now references UDP port 7551 instead of 19132.
- Login packets are never forwarded unmodified when token refresh or login rewriting fails.
- Multiple lifecycle and reconnection races in service, signaling, and outbox handling.
