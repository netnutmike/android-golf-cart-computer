# Implementation Plan: Android Golf Cart Computer

## Overview

This plan implements the Android Golf Cart Computer (GCD) application — a native Android app replacing the ESP-32 based Golf Cart Display Computer. The implementation uses Java as the primary language with Kotlin interop for BLE (Kable) and protobuf (Wire) libraries. The architecture follows a three-layer pattern (UI, Domain, Data) with Jetpack Compose, Hilt DI, and foreground services for Bluetooth connections.

Tasks are ordered to build foundational infrastructure first, then layer on communication, domain logic, UI, and integration.

## Rules

1. **Reference repos are read-only** — The `Meshtastic-Android/` and `GCD-Golf-Cart-Display-Computer/` directories are for reference only. Do NOT write any code, modify any files, or create any files within these directories.
2. **All source code lives at the repo root** — All project source code, configuration files, and documentation must be created at the root of the repository, NOT in a subdirectory. The Android project structure (app/, gradle/, build.gradle.kts, etc.) should be at the top level.
3. **Reference repos will be deleted** — These repos are temporary and will be removed before task execution begins. All necessary information has been captured in the requirements and design documents.

## Tasks

- [ ] 1. Repository and project setup
  - [x] 1.1 Create Android project structure with Gradle Kotlin DSL
    - Initialize Android project with `com.golfcart.gcd` package
    - Configure `build.gradle.kts` (app and project level) with compileSdk, minSdk (API 26+), targetSdk
    - Add dependency versions catalog (`libs.versions.toml`) with pinned versions for: Jetpack Compose BOM, Hilt, Kable, Wire, DataStore, Coroutines, jqwik, MockK, Turbine, Robolectric
    - Configure Java 17 source/target compatibility
    - Configure Kotlin JVM toolchain for Kotlin interop modules
    - Set up Wire protobuf Gradle plugin for `.proto` code generation
    - Set up Hilt Gradle plugin
    - Set initial version to `0.1.0` following semantic versioning
    - _Requirements: 16.1, 16.2, 16.9, 16.12_

  - [x] 1.2 Create project directory structure with separation of concerns
    - Create package structure: `ui/`, `domain/`, `data/`, `di/`
    - Create sub-packages: `data/bluetooth/`, `data/location/`, `data/persistence/`, `data/proto/`
    - Create sub-packages: `domain/gps/`, `domain/odometer/`, `domain/service/`, `domain/sleep/`, `domain/brightness/`, `domain/geofence/`, `domain/audio/`, `domain/parser/`
    - Create sub-packages: `ui/main/`, `ui/weather/`, `ui/entertainment/`, `ui/config/`
    - Create `di/` package with placeholder Hilt modules: `AppModule`, `BluetoothModule`, `LocationModule`, `ServiceModule`
    - _Requirements: 16.13_

  - [x] 1.3 Add Meshtastic protobuf definition files
    - Copy `.proto` files into `app/src/main/proto/meshtastic/`: `mesh.proto`, `admin.proto`, `config.proto`, `portnums.proto`, `telemetry.proto`, `module_config.proto`, `channel.proto`, and supporting definitions
    - Configure Wire Gradle plugin to generate Kotlin data classes from `.proto` files
    - Verify protobuf code generation compiles successfully
    - _Requirements: 16.12, 18.9, 18.10_

  - [x] 1.4 Create repository documentation and configuration files
    - Create `README.md` with project overview, setup instructions, architecture description (three-layer), Bluetooth protocol summary, and screenshots placeholder
    - Create `CONTRIBUTING.md` with coding standards (Java primary, Kotlin for BLE/proto), branch naming conventions, PR process, and development setup instructions
    - Create `CHANGES.md` following Keep a Changelog format with initial `[0.1.0] - Unreleased` section
    - Create `LICENSE` file with MIT License text
    - Create `.gitignore` for Android/Gradle projects (build/, .gradle/, .idea/, *.apk, local.properties, etc.)
    - _Requirements: 16.5, 16.6, 16.7, 16.8, 16.10_

  - [x] 1.5 Add Renovate and Dependabot configuration
    - Create `renovate.json` with Android/Gradle preset configuration
    - Create `.github/dependabot.yml` for GitHub security updates on Gradle dependencies
    - _Requirements: 16.3, 16.4_

  - [x] 1.6 Set up test infrastructure
    - Configure JUnit 5 and jqwik in `build.gradle.kts` for unit/property tests
    - Configure MockK, Turbine, and Robolectric for test dependencies
    - Create `app/src/test/` directory structure mirroring main source
    - Create `app/src/androidTest/` directory structure for instrumented tests
    - Add a placeholder test class to verify test framework runs
    - _Requirements: 16.11_

- [x] 2. Checkpoint - Verify project builds and tests run
  - Ensure the project compiles, protobuf code generation succeeds, and the placeholder test passes. Ask the user if questions arise.

- [ ] 3. Core data layer and persistence
  - [x] 3.1 Implement DataStoreRepository with Jetpack DataStore
    - Create `UserPreferences` data class with all preference fields (dayBrightness, nightBrightness, speakerVolume, flipScreen, backlightTimeoutMinutes, temperatureOffset, serviceIntervalHours, gciMacAddress, homeLatitude, homeLongitude, homeFenceRadiusMeters, meshtasticEnabled, meshtasticDeviceAddress)
    - Implement `DataStoreRepository` interface with preference read/write using Jetpack DataStore Preferences
    - Implement 2-second debounce for slider/spinner value writes
    - Implement `resetAllPreferences()` that clears all saved settings
    - _Requirements: 15.1, 15.2, 15.3, 15.4_

  - [x] 3.2 Implement cache storage for weather and venue data
    - Implement `cacheWeatherData(rawPacket, timestamp, dateYYYYMMDD)` in DataStoreRepository
    - Implement `cacheVenueData(rawPacket, timestamp, dateYYYYMMDD)` in DataStoreRepository
    - Implement `getCachedWeather()` and `getCachedVenueEvents()` flows
    - Implement date validation logic: restore cache only if stored date matches current date
    - _Requirements: 15.5, 19.1, 19.2, 19.3, 19.4, 19.5_

  - [x] 3.3 Implement odometer and driving hours persistence
    - Implement `persistOdometer(accumDistance, tripDistance)` method
    - Implement `persistDrivingHours(tenthsOfHours)` method
    - Implement load-on-startup for odometer and driving hours values
    - _Requirements: 15.6, 6.9, 6.10, 7.8_

  - [x] 3.4 Write property test for cache date validation
    - **Property 10: Cache date validation**
    - Test that cached data is restored if and only if stored date equals current date
    - Generate random YYYYMMDD dates and verify restore/stale logic
    - **Validates: Requirements 3.7, 3.8, 4.7, 19.3, 19.4, 19.5**

- [ ] 4. Bluetooth communication - Meshtastic BLE Service
  - [x] 4.1 Implement MeshtasticService as Android Foreground Service
    - Create `MeshtasticService` extending `LifecycleService` with foreground notification
    - Implement `MeshtasticConnection` interface with StateFlow for connection state and node ID
    - Implement BLE scanning using Kable with device name pattern `^.*_([0-9a-fA-F]{4})`
    - Implement BLE connection with MTU negotiation
    - Implement GATT characteristic discovery (service UUID, TORADIO, FROMRADIO, FROMNUM)
    - Request Android Bluetooth permissions (BLUETOOTH_CONNECT, BLUETOOTH_SCAN on Android 12+)
    - _Requirements: 1.1, 1.2, 1.3, 1.8, 1.14, 1.15, 18.1, 18.3_

  - [x] 4.2 Implement Meshtastic packet framing and protocol
    - Implement 4-byte big-endian length prefix framing for outbound ToRadio messages
    - Implement packet splitting for payloads exceeding (MTU - 3) bytes with 20-byte default
    - Implement FROMNUM notification subscription and FROMRADIO polling loop (read until empty)
    - Implement protobuf encoding/decoding using Wire-generated classes for ToRadio/FromRadio
    - _Requirements: 1.4, 1.5, 1.6, 1.7, 18.2, 18.3_

  - [x] 4.3 Write property tests for packet framing
    - **Property 1: Protobuf serialization round-trip**
    - **Property 2: Packet framing round-trip**
    - Generate random byte arrays, verify framing + unframing produces original
    - Generate valid ToRadio/FromRadio messages, verify encode + decode equivalence
    - **Validates: Requirements 1.5, 1.6, 18.2, 18.8**

  - [x] 4.4 Implement Meshtastic connection handshake and lifecycle
    - Implement handshake: send `ToRadio(want_config_id=<random>)` after subscribing to FROMNUM
    - Process `FromRadio` responses: extract node number from `my_info` (tag 3), read position config from `config` (tag 5), detect `config_complete_id` (tag 7)
    - Implement 30-second heartbeat interval
    - Implement 60-second liveness timeout with reconnection
    - Implement graceful disconnect: send `ToRadio(disconnect=true)` before closing
    - Implement device bonding and persist bonded device address
    - _Requirements: 1.9, 1.10, 1.11, 1.12, 1.13, 18.4, 18.5_

  - [x] 4.5 Implement Meshtastic message sending and receiving
    - Implement `sendTextMessage(text, destination, channel)` constructing MeshPacket with TEXT_MESSAGE_APP port, random packet ID, and specified destination/channel
    - Implement `sendAdminMessage(AdminMessage)` using ADMIN_APP port
    - Implement incoming MeshPacket routing based on portnum (TEXT_MESSAGE_APP, ADMIN_APP, POSITION_APP, TELEMETRY_APP)
    - Implement SharedFlow emission for incoming packets
    - Enforce 237-byte payload limit on outbound messages
    - _Requirements: 2.3, 2.4, 2.7, 2.9, 18.6, 18.7, 18.8_

  - [x] 4.6 Write property tests for message routing and construction
    - **Property 3: Message routing acceptance** — message accepted iff destination is broadcast (0xFFFFFFFF) or matches local node number
    - **Property 4: Outbound message construction** — verify correct destination, channel, port, non-zero ID, encoded payload
    - **Property 5: Outbound payload size limit** — encoded payload never exceeds 237 bytes
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.7, 2.9, 18.7**

- [ ] 5. Bluetooth communication - GCI Telemetry Service
  - [x] 5.1 Implement TelemetryService as Android Foreground Service
    - Create `TelemetryService` extending `LifecycleService` with foreground notification
    - Implement `TelemetryConnection` interface with StateFlow for connection state and telemetry data
    - Implement Bluetooth connection to GCI (Classic SPP or BLE)
    - Implement message envelope parsing: type (1 byte), timestamp (4 bytes), seq_num (2 bytes), data_len (2 bytes), data (variable)
    - Parse telemetry payload: modeLights (int), outdoorLum (int), airTemp (float), battVolts (float), fuel (float)
    - _Requirements: 8.1, 8.2_

  - [x] 5.2 Implement GCI heartbeat and connection management
    - Implement 10-second heartbeat send interval
    - Implement 40-second timeout (4 missed heartbeats) marking GCI as disconnected
    - Implement automatic reconnection on disconnect without affecting Meshtastic connection
    - Implement `sendGpsData()`, `sendIsHome()`, `sendIsDaytime()` outbound messages
    - _Requirements: 8.7, 8.8, 8.13, 8.14, 8.15_

  - [x] 5.3 Implement GCI pairing mechanism
    - Implement 6-second pairing window with broadcast discovery
    - Implement pairing command containing GCD's MAC address
    - Implement ACK response handling within timeout window
    - If no ACK received, restore previously paired device address
    - Persist paired GCI device address for automatic reconnection
    - _Requirements: 8.9, 8.10, 8.11, 8.12_

- [x] 6. Checkpoint - Verify Bluetooth services compile and basic connectivity works
  - Ensure both foreground services compile, protobuf message construction works, and connection state flows emit correctly. Ask the user if questions arise.

- [x] 7. GPS processing and navigation
  - [x] 7.1 Implement GpsProcessor with speed filtering
    - Create `GpsProcessor` class consuming Android FusedLocationProvider updates
    - Implement speed filtering pipeline: filter < 2.5 mph to zero, reject > 8 mph/s spikes, report zero when < 4 mph and decreasing, require 2 consecutive readings above threshold (3 when dimmed)
    - Implement fallback: if speed invalid but location valid and last speed < 5 mph → zero; else retain last speed
    - Emit `ProcessedGpsData` via StateFlow
    - _Requirements: 5.1, 5.3, 5.4, 5.5, 5.6, 5.7, 5.19_

  - [x] 7.2 Write property test for GPS speed filtering
    - **Property 11: GPS speed filtering**
    - Generate sequences of raw speed readings, verify all filtering rules hold simultaneously
    - **Validates: Requirements 5.4, 5.5, 5.6, 5.7**

  - [x] 7.3 Implement heading, satellite, and HDOP processing
    - Implement 16-point cardinal direction mapping from bearing degrees (22.5-degree intervals)
    - Implement satellite count debounce: require 3 consecutive zeros before displaying zero
    - Implement HDOP estimation when unavailable: ≥6 sats = 1.5, 4-5 sats = 2.0, <4 sats = 99.0
    - Display satellite/HDOP in format "sats/hdop" (e.g., "8/1.50")
    - _Requirements: 5.8, 5.10, 5.11, 5.12_

  - [x] 7.4 Write property tests for navigation calculations
    - **Property 12: Cardinal direction mapping** — verify correct direction for any bearing [0, 360)
    - **Property 13: Satellite count debounce** — zero only after 3 consecutive zeros
    - **Property 14: HDOP estimation from satellite count** — verify thresholds
    - **Validates: Requirements 5.8, 5.11, 5.12**

  - [x] 7.5 Implement NavigationData (date, time, sunrise/sunset)
    - Format date as "Day, Mon DD" (e.g., "Mon, Jan 15")
    - Format time in 12-hour format with AM/PM
    - Handle timezone conversion with automatic DST transitions
    - Calculate sunrise/sunset times based on GPS location and date
    - Display "NO GPS" for date field if no GPS time update for 60 seconds
    - Emit `NavigationData` via StateFlow
    - _Requirements: 5.13, 5.14, 5.15, 5.16, 5.17, 5.18_

  - [x] 7.6 Implement Meshtastic position data integration
    - Support receiving GPS position data from connected Meshtastic radio (POSITION_APP port)
    - Merge Meshtastic GPS data with Android GPS as secondary source
    - _Requirements: 5.2_

- [x] 8. Domain logic - HotPacketParser
  - [x] 8.1 Implement weather packet parsing
    - Implement `isHotPacket(text)` — check for leading `|` character
    - Implement `parsePacketType(text)` — extract type code after `|#`
    - Implement weather packet validation: starts with `|#01#`, exactly 7 `#` delimiters, exactly 12 `,` delimiters
    - Implement weather packet parsing: extract current temp, 4 forecast hours (hourLabel, glyphCode, temperature, precipitation)
    - Validate temperature range -99 to 999, hour labels ≤ 6 chars
    - Clear zero precipitation values (`0.0`) to empty string
    - Return `Result<WeatherData>` — discard malformed packets with diagnostic log
    - _Requirements: 3.1, 3.2, 3.3, 3.9, 3.10, 3.11, 3.12_

  - [x] 8.2 Write property tests for weather packet parsing
    - **Property 6: Weather packet structural validation** — accepted iff correct format, delimiter counts, temp range, label length
    - **Property 7: Weather packet parsing correctness** — valid packets produce WeatherData with 4 forecasts
    - **Property 8: Precipitation zero-clearing** — "0.0" maps to empty string, others preserved
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.9, 3.10, 3.11, 3.12**

  - [x] 8.3 Implement venue/event packet parsing
    - Implement venue/event packet validation: starts with `|#02#`
    - Parse `#`-delimited venue,event pairs (comma separates venue from event)
    - Support up to 12 venue/event entries
    - Return `Result<List<VenueEvent>>` — discard malformed packets with diagnostic log
    - _Requirements: 4.1, 4.2, 4.4_

  - [x] 8.4 Write property test for venue/event parsing
    - **Property 9: Venue/event packet parsing** — valid packets produce correct list of VenueEvent objects matching input pairs
    - **Validates: Requirements 4.1, 4.2, 4.4**

- [x] 9. Domain logic - OdometerManager and ServiceReminderManager
  - [x] 9.1 Implement OdometerManager
    - Implement distance accumulation using GPS position calculations
    - Gate accumulation: only when filtered speed > 0
    - Minimum position change: 2.6 feet (0.0005 miles) with Doppler confirmation
    - Fallback minimum (no Doppler): 10 feet (0.002 miles)
    - Reject position-based speed > 30 mph as GPS error
    - Implement odometer rollover at 100,000 miles
    - Implement trip odometer reset
    - Display both values with 1 decimal place precision in miles
    - Persist every 0.5 miles and before sleep/shutdown
    - Load persisted values on startup
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10_

  - [x] 9.2 Write property tests for odometer
    - **Property 15: Distance accumulation gating** — distance only accumulated when speed > 0, position change exceeds threshold, implied speed ≤ 30 mph
    - **Property 16: Odometer invariants** — trip reset doesn't affect total, total equals sum of segments, rollover at 100,000
    - **Validates: Requirements 6.1, 6.2, 6.4, 6.5, 6.6, 6.7, 6.8**

  - [x] 9.3 Implement ServiceReminderManager
    - Accumulate driving hours only when speed > 0
    - Store in tenths of hours (6-minute resolution)
    - Use GPS time as primary source, fall back to system clock
    - Only accept time deltas between 0 and 10 seconds
    - Display hours since last service
    - Configurable service interval (default: 100 hours)
    - Persist every 1.0 hours of driving
    - Allow user reset of service hour counter
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9_

  - [x] 9.4 Write property test for driving hours accumulation
    - **Property 17: Driving hours accumulation gating** — hours accumulate only when speed > 0 AND time delta is 0-10 seconds
    - **Validates: Requirements 7.1, 7.5**

- [x] 10. Domain logic - SleepManager, BacklightManager, GeofenceManager
  - [x] 10.1 Implement GeofenceManager
    - Implement home location set/clear with GPS validation
    - Implement configurable geofence radius (default: 500 meters)
    - Implement continuous distance calculation from current position to home
    - Emit `at_home` status changes and notify GCI
    - Persist home location coordinates
    - Reject set-home request if GPS unavailable with error message
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8_

  - [x] 10.2 Write property test for geofence
    - **Property 18: Geofence status determination** — at_home is true iff distance ≤ configured radius
    - **Validates: Requirements 9.4, 9.5, 9.6**

  - [x] 10.3 Implement BacklightManager
    - Set day brightness between sunrise and sunset, night brightness otherwise
    - Implement configurable day/night brightness levels (0-10 scale)
    - Implement inactivity timeout for screen dimming (configurable minutes, 0 disables)
    - Restore brightness on touch or movement activity
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_

  - [x] 10.4 Write property test for brightness selection
    - **Property 19: Brightness level selection** — day brightness when between sunrise/sunset, night brightness otherwise
    - **Validates: Requirements 10.1, 10.2**

  - [x] 10.5 Implement SleepManager with three-state power management
    - Implement STARTUP_GRACE, GCI_MODE, STANDALONE_MODE states
    - Implement startup grace period (= backlight timeout, minimum 30 seconds)
    - GCI_MODE: sleep controlled by GCI connection status
    - STANDALONE_MODE: backlight dimming only, never deep sleep
    - Transition GCI_MODE → STANDALONE_MODE when GCI disconnected for timeout period
    - Adjust Meshtastic GPS interval in standalone mode: 120s at home, 8s away
    - Persist odometer and driving hours before sleep
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

  - [x] 10.6 Write property test for sleep state machine
    - **Property 20: Sleep state machine transitions** — verify all valid transitions and reject invalid ones
    - **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**

- [x] 11. Checkpoint - Verify domain logic compiles and property tests pass
  - Ensure all domain managers compile, state flows emit correctly, and property tests pass. Ask the user if questions arise.

- [x] 12. UI - Main Display Screen
  - [x] 12.1 Implement MainViewModel and main screen Compose layout
    - Create `MainViewModel` aggregating state from all domain managers
    - Implement main display with configurable widgets: speed, heading, time, date, temperature, satellite/HDOP, connection status indicators
    - Display odometer and trip odometer with 1 decimal place
    - Display battery voltage, fuel level, headlight mode from GCI telemetry
    - Display outdoor air temperature with configurable offset
    - Display connection status indicators for Meshtastic and GCI (independent)
    - Display "new data received" indicator with 5-second auto-clear
    - _Requirements: 13.1, 13.6, 13.10, 13.11, 8.3, 8.4, 8.5, 8.6_

  - [x] 12.2 Implement Meshtastic messaging UI
    - Display received messages with sender node ID, channel number, and timestamp
    - Support broadcast and direct message display
    - Implement preformatted message selection from configurable list
    - Implement custom message composition via on-screen keyboard
    - Send AWAKE notification (`~#01#GC#AWAKE#`) on connection establishment
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.8_

- [x] 13. UI - Weather Screen
  - [x] 13.1 Implement Weather screen with forecast display
    - Display current temperature prominently
    - Display 4-hour forecast with hour label, weather glyph icon, temperature, and precipitation for each hour
    - Display timestamp of last weather data receipt
    - Load stored weather data on start if from current day
    - Send request message (`~#01#GC#REQ_WX_ENT#`) if cache is stale or absent
    - _Requirements: 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 14. UI - Entertainment Screen
  - [x] 14.1 Implement Entertainment screen with venue/event table
    - Display venue/event data in scrollable two-column table (venue name | event name)
    - Support up to 12 entries
    - Display timestamp of last venue/event data receipt
    - Load stored venue/event data on start if from current day
    - Refresh display when new data arrives while screen is active
    - _Requirements: 4.3, 4.4, 4.5, 4.6, 4.7, 4.8_

- [x] 15. UI - Configuration Screen
  - [x] 15.1 Implement Configuration screen with all settings controls
    - Implement navigation from main display (unobtrusive access)
    - Implement controls: day brightness slider, night brightness slider, speaker volume slider, screen flip toggle, backlight timeout spinner, temperature offset, service interval
    - Implement home location set/clear buttons
    - Implement GCI pairing button with status feedback
    - Implement Meshtastic enable/disable toggle
    - Display app version string and device MAC/identifier
    - Display connected Meshtastic radio node ID in hex format (e.g., `!a1b2c3d4`)
    - Implement Meshtastic radio reboot command button
    - Implement "reset all preferences" option
    - Implement manual reboot option
    - Implement screen rotation (flip) configuration
    - _Requirements: 13.4, 13.5, 13.7, 13.8, 13.9, 12.1, 12.2, 12.7_

- [x] 16. Checkpoint - Verify UI screens render and navigate correctly
  - Ensure all Compose screens compile, render with mock data, and navigation between screens works. Ask the user if questions arise.

- [x] 17. Integration - Audio feedback system
  - [x] 17.1 Implement AudioManager with all system tones
    - Implement startup tone on application launch
    - Implement message notification tone for new Meshtastic messages
    - Implement alert, confirmation, click, and error tones
    - Implement configurable speaker volume (0-20 range)
    - Persist speaker volume setting
    - Wire audio events to appropriate triggers throughout the app
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8_

- [x] 18. Integration - Dual Bluetooth connection management
  - [x] 18.1 Implement independent dual Bluetooth connection management
    - Ensure Meshtastic BLE and GCI connections operate independently with separate state tracking
    - Implement automatic reconnection for each connection without affecting the other
    - Display independent connection status indicators
    - Request all necessary Android Bluetooth permissions at runtime with rationale
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6_

  - [x] 18.2 Implement Meshtastic radio administration commands
    - Implement radio reboot command via ADMIN_APP port
    - Implement position config read during handshake
    - Implement GPS update interval setting (read-modify-write pattern)
    - Implement GPS interval adjustment based on at_home status (120s home, 8s away)
    - _Requirements: 12.2, 12.3, 12.4, 12.5, 12.6_

  - [x] 18.3 Wire data synchronization and caching end-to-end
    - Wire weather data reception → parse → cache → display flow
    - Wire venue/event data reception → parse → cache → display flow
    - Implement "(stored)" indicator for cached data
    - Clear "(stored)" indicator when live data replaces cache
    - Implement fresh data request when cache is stale
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6_

- [ ] 19. Integration - Final wiring and polish
  - [x] 19.1 Wire all components together via Hilt dependency injection
    - Complete Hilt module bindings for all interfaces and implementations
    - Wire ViewModels to domain managers
    - Wire domain managers to data layer services
    - Ensure foreground services start on app launch
    - Wire GCI status notifications (at_home, is_daytime) on connection and state changes
    - _Requirements: 16.13, 8.13, 8.14_

  - [x] 19.2 Write integration tests for end-to-end flows
    - Test BLE connection lifecycle with mock peripheral
    - Test dual Bluetooth independence (one failure doesn't affect other)
    - Test DataStore persistence round-trip
    - Test foreground service lifecycle survives backgrounding
    - _Requirements: 17.2, 17.3, 17.4_

- [x] 20. Final checkpoint - Ensure all tests pass
  - Ensure all unit tests, property tests, and integration tests pass. Verify the app compiles cleanly with no warnings. Ask the user if questions arise.

## Notes

- All tasks are required
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation at natural break points
- Property tests validate the 20 universal correctness properties defined in the design document
- The project uses Java as primary language with Kotlin interop for Kable (BLE) and Wire (protobuf) libraries
- jqwik is the property-based testing framework (JVM/Java-native)
- All Bluetooth communication runs in Android foreground services for reliability
