# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Emoji reactions for messages with long-press picker (`👍 ❤️ 😂 😮 😢 😡`) and grouped counters under each message.
- Mesh protocol support for reactions with a dedicated packet type (`MSG_REACTION`) plus encrypted private reactions over Noise transport.
- Official app language configuration for:
  - English (United States) `en-US`
  - Portuguese (Brazil) `pt-BR`
- App version update check backed by `https://cirabit.smaia.dev/api/latest-release`.
- Update check controls and status visibility in About settings.
- One-time "update available" prompt per release version.
- Localized strings for update check and update prompt (EN/PT-BR).

### Changed
- Automatic update checks now run on app resume with a 12-hour interval.
- Clicking the update-check row icon/text opens the official download page: `https://cirabit.smaia.dev/download`.
- Update check now uses a dedicated HTTP client with higher timeouts and endpoint fallback for better reliability.
- Update checks are fully skipped when the user disables the feature.
- Geohash heartbeat jitter now uses CSPRNG-backed randomness (`SecureRandom`) for less predictable broadcast timing.
- Chat user action sheet now includes a direct "Message" action for starting private conversations without replacing existing actions.
- Updated deprecated Android APIs in core runtime paths:
  - `stopForeground(Boolean)` migrated to `stopForeground(Service.STOP_FOREGROUND_*)`.
  - BLE characteristic notify/write flows migrated to modern API signatures on Android 13+ with compatibility fallback.
  - Compose deprecations migrated (`Icons.AutoMirrored.Filled.ArrowBack`, `LocalClipboard`, `TabRowDefaults.SecondaryIndicator`).

### Fixed
- Prevented false "could not verify updates" states caused by cancelled Compose coroutines.
- Replaced deprecated `requestSingleUpdate` in location provider with one-shot listener registration/removal.
- Removed unchecked generic casts in channel-key packet parsing, Nostr event tag parsing, and persisted channel creator map loading.
- Hardened incoming media/file handling against oversized payload DoS by enforcing `MAX_INCOMING_FILE_BYTES` during fragment reassembly, TLV decode, and message handling.
- Added disk-space preflight checks before persisting incoming files to reduce storage exhaustion risk.
- Replaced direct image decoding in chat/UI with bounded two-pass decode (`inJustDecodeBounds` + `inSampleSize`) on `Dispatchers.IO` and explicit OOM fallback.
- Removed file `readBytes()` from chat rendering path; file cards now render from metadata/path only.
- Fixed deterministic file/media message IDs across sender and receiver (including encrypted Noise file transfer), restoring emoji reaction targeting for media messages.
- Added pending private-reaction queue during Noise handshake with per-peer cap and TTL, then automatic flush after session establishment.
- Overrode AGP transitive `org.jdom:jdom2` to `2.0.6.1` in buildscript classpath to mitigate CVE-2021-33813 (XXE/DoS in `SAXBuilder`).
- Overrode AGP/UTP transitive protobuf artifacts to `3.25.5` (`protobuf-java`, `protobuf-javalite`, `protobuf-kotlin`, `protobuf-kotlin-lite`) to mitigate CVE-2024-7254 (unknown-field recursion DoS).
- Overrode AGP/UTP transitive Netty artifacts to `4.1.118.Final` (`netty-handler` and related core modules) to mitigate CVE-2025-24970 (native crash via crafted TLS packet).
- Overrode AGP transitive `org.bitbucket.b_c:jose4j` to `0.9.6` to mitigate CVE-2024-29371 (JWE decompression DoS).
- Overrode AGP transitive `org.apache.commons:commons-compress` to `1.26.0` to mitigate CVE-2024-26308 (Pack200 decompression OOM/DoS).
- Migrated app runtime Bouncy Castle provider from `org.bouncycastle:bcprov-jdk15on:1.70` to `org.bouncycastle:bcprov-jdk15to18:1.78.1` to address the RSA-handshake timing-leak advisory affecting pre-`1.78` releases.
- Replaced implicit narrowing compound assignments in `Curve448` carry propagation with explicit casts to avoid lossy-conversion ambiguity (`java/implicit-cast-in-compound-assignment`).
- Removed user-controlled channel names from `LocationChannelManager.select()` logs to prevent log injection (`java/log-injection`).
- Hardened fragment reassembly against multi-set memory pressure by enforcing active-set/global-buffer caps and synchronized state transitions.
- Added protocol decode guardrails for oversized payload declarations (`MAX_PAYLOAD_LENGTH`) before allocation.
- Added geohash DM resolution helpers for nickname and short-id lookup (`startGeohashDMByNickname`, `startGeohashDMByShortId`, `findPubkeyByShortId`).

## [1.4.0] - 2025-10-15
### Fixed
- fix: Resolve debug settings bottom sheet crash on some devices (Issue #472)
  - Fixed IllegalFormatConversionException in DebugSettingsSheet.kt when scrolling through debug settings
  - Corrected string formatting for debug_target_fpr_fmt and debug_derived_p_fmt string resources
  - Improved string resource parameter handling for numeric values

## [0.7.2] - 2025-07-20
### Fixed
- fix: battery optimization screen content scrollable with fixed buttons

## [0.7.1] - 2025-07-19

### Added
- feat(battery): add battery optimization management for background reliability

### Fixed
- fix: center align toolbar item in ChatHeader - passed modifier.fillmaxHeight so the content inside the row can actually be centered
- fix: update sidebar text to use string resources
- fix(chat): cursor location and enhance message input with slash command styling

### Changed
- refactor: remove context attribute at ChatViewModel.kt
- Refactor: Migrate MainViewModel to use StateFlow

### Improved
- Use HorizontalDivider instead of deprecated Divider
- Use contentPadding instead of padding so items remain fully visible


## [0.7]

### Added
- Location services check during app startup with educational UI
- Message text selection functionality in chat interface
- Enhanced RSSI tracking and unread message indicators
- Major Bluetooth connection architecture refactoring with dedicated managers

### Fixed
- **Critical**: message fragmentation compatibility issues
  - Fixed fragment size (500→150 bytes) and ID generation for protocol interoperability
  - Ensures Android can properly communicate with compatible clients
- DirectMessage notifications and text copying functionality
- Smart routing optimizations (no relay loops, targeted delivery)
- Build system compilation issues and null pointer exceptions

### Changed
- Comprehensive dependency updates (AGP 8.10.1, Kotlin 2.2.0, Compose 2025.06.01)
- Optimized BLE scan intervals for better battery performance
- Reduced excessive logging output

### Improved
- Cross-client compatibility improvements
- Connection stability through architectural improvements
- Battery performance via scan duty cycling
- User onboarding with location services education

## [0.6]

### Added
- Channel password management with `/pass` command for channel owners
- Monochrome/themed launcher icon for Android 12+ dynamic theming support
- Unit tests package with initial testing infrastructure
- Production build optimization with code minification and shrinking
- Native back gesture/button handling for all app views

### Fixed
- Favorite peer functionality completely restored and improved
  - Enhanced favorite system with fallback mechanism for peers without key exchange
  - Fixed UI state updates for favorite stars in both header and sidebar
  - Improved favorite persistence across app sessions
- `/w` command now displays user nicknames instead of peer IDs
- Button styling and layout improvements across the app
  - Enhanced back button positioning and styling
  - Improved private chat and channel header button layouts
  - Fixed button padding and alignment issues
- Color scheme consistency updates
  - Updated orange color throughout the app for visual consistency
  - Consistent color usage for private messages and UI elements
- App startup reliability improvements
  - Better initialization sequence handling
  - Fixed null pointer exceptions during startup
  - Enhanced error handling and logging
- Input field styling and behavior improvements
- Sidebar user interaction enhancements
- Permission explanation screen layout fixes with proper vertical padding

### Changed
- Updated GitHub organization references in project files
- Improved README documentation with updated clone URLs
- Enhanced logging throughout the application for better debugging

## [0.5.1] - 2025-07-10

### Added
- Bluetooth startup check with user prompt to enable Bluetooth if disabled

### Fixed
- Improved Bluetooth initialization reliability on first app launch

## [0.5] - 2025-07-10

### Added
- New user onboarding screen with permission explanations
- Educational content explaining why each permission is required
- Privacy assurance messaging (no tracking, no servers, local-only data)

### Fixed
- Comprehensive permission validation - ensures all required permissions are granted
- Proper Bluetooth stack initialization on first app load
- Eliminated need for manual app restart after installation
- Enhanced permission request coordination and error handling

### Changed
- Improved first-time user experience with guided setup flow

## [0.4] - 2025-07-10

### Added
- Push notifications for direct messages
- Enhanced notification system with proper click handling and grouping

### Improved
- Direct message (DM) view with better user interface
- Enhanced private messaging experience

### Known Issues
- Favorite peer functionality currently broken

## [0.3] - 2025-07-09

### Added
- Battery-aware scanning policies for improved power management
- Dynamic scan behavior based on device battery state

### Fixed
- Android-to-Android Bluetooth Low Energy connections
- Peer discovery reliability between Android devices
- Connection stability improvements

## [0.2] - 2025-07-09

### Added
- Initial Android implementation of cirabit protocol
- Bluetooth Low Energy mesh networking
- End-to-end encryption for private messages
- Channel-based messaging with password protection
- Store-and-forward message delivery
- IRC-style commands (/msg, /join, /clear, etc.)
- RSSI-based signal quality indicators

### Fixed
- Various Bluetooth handling improvements
- User interface refinements
- Connection reliability enhancements

## [0.1] - 2025-07-08

### Added
- Initial release of cirabit Android client
- Basic mesh networking functionality
- Core messaging features
- Protocol compatibility improvements for interoperable clients

[Unreleased]: https://github.com/sarahsec/cirabit-android/compare/0.5.1...HEAD
[0.5.1]: https://github.com/sarahsec/cirabit-android/compare/0.5...0.5.1
[0.5]: https://github.com/sarahsec/cirabit-android/compare/0.4...0.5
[0.4]: https://github.com/sarahsec/cirabit-android/compare/0.3...0.4
[0.3]: https://github.com/sarahsec/cirabit-android/compare/0.2...0.3
[0.2]: https://github.com/sarahsec/cirabit-android/compare/0.1...0.2
[0.1]: https://github.com/sarahsec/cirabit-android/releases/tag/0.1
