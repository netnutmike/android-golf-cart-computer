# Features List

Complete list of features in the Android Golf Cart Computer application, organized by category.

## Bluetooth Communication

### Meshtastic BLE Connection
- Automatic BLE scanning for Meshtastic radios (name pattern matching)
- Full Meshtastic protocol handshake with config download
- 4-byte length-prefix packet framing with MTU-aware splitting
- 30-second heartbeat with 60-second liveness timeout
- Automatic reconnection with exponential backoff
- Device bonding and persistent address storage
- Graceful disconnect with `ToRadio(disconnect=true)`
- Android 12+ Bluetooth permission handling

### Meshtastic Messaging
- Send and receive text messages via mesh network
- Broadcast and direct message support
- Preformatted message selection from configurable list
- Custom message composition via on-screen keyboard
- Message display with sender node ID, channel, and timestamp
- AWAKE notification on connection establishment
- 237-byte payload limit enforcement
- Audible notification on message receipt

### GCI Telemetry Connection
- Bluetooth Classic/BLE connection to GCI ESP-32
- Real-time vehicle telemetry reception (battery, fuel, temperature, headlights)
- 10-second heartbeat with 40-second disconnect timeout
- 6-second pairing window with broadcast discovery
- Independent connection management (doesn't affect Meshtastic)
- Persistent paired device address
- Sends GPS data, at-home status, and daytime status to GCI

### Dual Bluetooth Management
- Two simultaneous Bluetooth connections (BLE + Classic/BLE)
- Independent connection state tracking per device
- Independent automatic reconnection per device
- Separate connection status indicators in UI
- Runtime permission requests with user-facing rationale

## GPS and Navigation

### Speed Display
- Current speed in miles per hour (integer)
- Multi-stage speed filtering pipeline:
  - Dither elimination (< 2.5 mph → zero)
  - Spike rejection (> 8 mph/s acceleration)
  - Responsive stop detection (< 4 mph and decreasing → zero)
  - Consecutive threshold (2 readings, 3 when dimmed)
  - Invalid speed fallback logic

### Heading and Position
- 16-point cardinal direction display (N, NNE, NE, ENE, etc.)
- Current latitude and longitude coordinates
- Satellite count with 3-reading debounce
- HDOP display with estimation when unavailable
- Format: "sats/hdop" (e.g., "8/1.50")

### Date and Time
- Date in "Day, Mon DD" format (e.g., "Mon, Jan 15")
- Time in 12-hour format with AM/PM
- Automatic timezone and DST handling
- "NO GPS" indicator after 60 seconds without GPS time
- Sunrise and sunset time calculation and display

### GPS Sources
- Android device internal GPS (primary)
- Meshtastic radio position data (secondary)

## Distance and Maintenance Tracking

### Odometer
- Total distance accumulation with GPS position calculation
- Trip odometer (resettable)
- 1 decimal place precision in miles
- Doppler speed gating (only accumulates when moving)
- Minimum position change thresholds (2.6 ft with Doppler, 10 ft without)
- GPS error rejection (> 30 mph implied speed)
- Rollover at 100,000 miles
- Persistence every 0.5 miles and before sleep

### Service Reminder
- Driving hours accumulation (only when speed > 0)
- Tenths-of-hours resolution (6 minutes)
- GPS time preferred, system clock fallback
- Time delta validation (0-10 seconds only)
- Configurable service interval (default: 100 hours)
- User-resettable counter
- Persistence every 1.0 hours

## Weather and Entertainment

### Weather Display
- Current temperature display
- 4-hour forecast with:
  - Hour label
  - Weather glyph icon
  - Temperature
  - Precipitation probability
- Last-received timestamp
- Same-day cache restoration on startup
- Automatic fresh data request when cache is stale
- Zero precipitation values cleared from display

### Entertainment Display
- Venue/event data in scrollable two-column table
- Up to 12 venue/event entries
- Last-received timestamp
- Same-day cache restoration on startup
- Live refresh when new data arrives

### Data Caching
- Weather and venue data persisted with date stamp
- Same-day cache validation and restoration
- "(stored)" indicator for cached data
- Automatic fresh data request (`~#01#GC#REQ_WX_ENT#`)
- Cache cleared when live data replaces it

## Power and Display Management

### Sleep Manager (Three-State)
- **STARTUP_GRACE** — Waits for GCI connection (configurable duration, min 30s)
- **GCI_MODE** — Sleep controlled by GCI connection status
- **STANDALONE_MODE** — Backlight dimming only, never deep sleep
- Automatic mode transitions based on GCI connectivity
- GPS interval adjustment in standalone mode (120s home, 8s away)
- Persists odometer and driving hours before sleep

### Backlight Manager
- Automatic day/night brightness based on sunrise/sunset
- Configurable day brightness (0-10 scale)
- Configurable night brightness (0-10 scale)
- Inactivity timeout for screen dimming (configurable minutes)
- Brightness restore on touch or movement
- Timeout of 0 disables auto-dimming

### Home Location and Geofencing
- Set current GPS position as home
- Clear saved home location
- Configurable geofence radius (default: 500 meters)
- Continuous distance calculation from home
- At-home status notification to GCI
- GPS validation before setting home (rejects if unavailable)

## Audio Feedback

### System Tones
- Startup tone on app launch
- Message notification tone (new Meshtastic message)
- Alert tone for important notifications
- Confirmation tone for successful actions
- Click tone for button presses
- Error tone for failed operations

### Volume Control
- Configurable speaker volume (0-20 range)
- Persistent volume setting

## Configuration

### Display Settings
- Day brightness slider (0-10)
- Night brightness slider (0-10)
- Screen flip/rotation toggle
- Backlight timeout spinner (minutes, 0 = disabled)

### Vehicle Settings
- Temperature offset adjustment
- Service interval configuration (hours)
- Trip odometer reset
- Service hour counter reset

### Connectivity Settings
- GCI pairing button with status feedback
- Meshtastic enable/disable toggle
- Meshtastic radio reboot command
- Connected radio node ID display (hex format)

### System Information
- App version string display
- Device MAC/identifier display
- Manual reboot option
- Reset all preferences option

## Meshtastic Radio Administration

- Display connected node ID in hex (e.g., `!a1b2c3d4`)
- Send reboot command to radio
- Read position configuration during handshake
- Set GPS update interval (8s away, 120s home)
- Read-modify-write pattern for config updates
- Admin commands via ADMIN_APP port with protobuf encoding

## Persistent Storage

### User Preferences
- All settings persisted via Jetpack DataStore
- 2-second write debounce for slider/spinner values
- Load all settings on startup
- Reset all preferences option

### Cached Data
- Weather data with date stamp
- Venue/event data with date stamp
- Odometer values (total and trip)
- Driving hours (tenths of hours)
- Paired device addresses (GCI and Meshtastic)
- Home location coordinates

## Technical Features

### Architecture
- Three-layer architecture (UI, Domain, Data)
- Jetpack Compose with Material 3
- Hilt dependency injection
- Kotlin Coroutines and StateFlow
- Android Foreground Services for Bluetooth

### Testing
- 20 property-based correctness tests (jqwik)
- Unit tests (JUnit 5)
- Integration tests
- Mock BLE peripheral testing
- Flow testing with Turbine

### Build and Tooling
- Gradle Kotlin DSL
- Version catalog (libs.versions.toml)
- Wire protobuf code generation
- Renovate for dependency updates
- Dependabot for security updates
- Semantic versioning
