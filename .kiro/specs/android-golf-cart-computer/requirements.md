# Requirements Document

## Introduction

This document specifies the requirements for the Android Golf Cart Computer application, a native Android app that replicates and expands the functionality of the existing ESP-32 based Golf Cart Display Computer (GCD). The ESP-32 system is running out of memory, preventing feature expansion. The Android platform provides more memory, a larger display, and better connectivity options.

The system is part of a three-component golf cart computer ecosystem designed for The Villages Retirement Community:
- **GCM (Golf Cart Meshtastic):** A Meshtastic radio providing mesh messaging, GPS, and data relay
- **GCD (Golf Cart Display):** The display computer (this application replaces the ESP-32 version)
- **GCI (Golf Cart Internal):** An ESP-32 central computer providing vehicle telemetry (battery voltage, fuel level, temperature, headlight status) over Bluetooth

The Android app must handle two simultaneous Bluetooth connections: one to the Meshtastic radio (BLE) and one to the GCI telemetry radio (Bluetooth Classic or BLE).

## Glossary

- **GCD:** Golf Cart Display — the display computer application (this Android app)
- **GCM:** Golf Cart Meshtastic — the Meshtastic radio unit connected via Bluetooth LE
- **GCI:** Golf Cart Internal — the ESP-32 vehicle telemetry computer connected via Bluetooth
- **Meshtastic:** An open-source mesh networking protocol for LoRa radios
- **HoT_Packet:** "Hands-off-Transmission" packet — a structured data packet received via Meshtastic containing weather or venue/event data, identified by a leading `|` character
- **ESP-NOW:** A connectionless Wi-Fi communication protocol used by the current ESP-32 system (replaced by Bluetooth in the Android version)
- **BLE:** Bluetooth Low Energy — the protocol used to communicate with the Meshtastic radio
- **GATT:** Generic Attribute Profile — the BLE protocol layer for data exchange
- **Protobuf:** Protocol Buffers — the serialization format used by Meshtastic for radio communication
- **Wire:** The Square Wire library used for Kotlin protobuf code generation
- **Kable:** A Kotlin Multiplatform BLE library used for Bluetooth communication
- **NVS:** Non-Volatile Storage — persistent storage for preferences and cached data (Android SharedPreferences equivalent)
- **LVGL:** Light and Versatile Graphics Library — the UI framework used in the ESP-32 version (replaced by Jetpack Compose in Android)
- **Geofence:** A virtual geographic boundary defined by GPS coordinates and a radius
- **NMEA:** National Marine Electronics Association — GPS data sentence format
- **Odometer:** Accumulated distance traveled counter
- **Trip_Odometer:** Resettable distance counter for individual trips
- **Service_Reminder:** A maintenance tracking system based on accumulated driving hours
- **Backlight_Manager:** The component managing display brightness based on time of day and activity
- **Sleep_Manager:** The component managing power states and screen timeout behavior
- **Hot_Packet_Parser:** The component that parses structured weather and venue/event data from Meshtastic messages
- **Telemetry_Service:** The component managing Bluetooth communication with the GCI for vehicle data
- **Meshtastic_Service:** The component managing BLE communication with the Meshtastic radio
- **Widget:** A configurable UI element on the main display screen

## Requirements

### Requirement 1: Meshtastic BLE Connection

**User Story:** As a golf cart operator, I want the app to connect to my Meshtastic radio via Bluetooth LE, so that I can send and receive mesh messages without a wired serial connection.

#### Acceptance Criteria

1. THE Meshtastic_Service SHALL connect to the Meshtastic radio using Bluetooth Low Energy with the service UUID `6ba1b218-15a8-461f-9fa8-5dcae273eafd`
2. THE Meshtastic_Service SHALL write outbound packets to the TORADIO characteristic (UUID: `f75c76d2-129e-4dad-a1dd-7866124401e7`)
3. THE Meshtastic_Service SHALL subscribe to notifications on the FROMNUM characteristic (UUID: `ed9da18c-a800-4f66-a670-aa7547e34453`) to detect when new data is available
4. WHEN a FROMNUM notification is received, THE Meshtastic_Service SHALL read all available data from the FROMRADIO characteristic (UUID: `2c55e69e-4993-11ed-b878-0242ac120002`) until an empty response is returned
5. THE Meshtastic_Service SHALL encode outbound messages using Protocol Buffers (protobuf) with the Meshtastic `ToRadio` message format
6. THE Meshtastic_Service SHALL decode inbound messages using Protocol Buffers (protobuf) with the Meshtastic `FromRadio` message format
7. THE Meshtastic_Service SHALL use the Square Wire library for Kotlin protobuf code generation from Meshtastic `.proto` definition files
8. THE Meshtastic_Service SHALL use the Kable library for BLE communication
9. WHEN the BLE connection is established, THE Meshtastic_Service SHALL perform the Meshtastic handshake by sending a `want_config_id` in the `ToRadio` message to initiate configuration download
10. THE Meshtastic_Service SHALL maintain a 30-second heartbeat interval to detect connection liveness
11. IF no data is received from the radio within 60 seconds after a heartbeat, THEN THE Meshtastic_Service SHALL treat the connection as dead and attempt reconnection
12. WHEN disconnecting, THE Meshtastic_Service SHALL send a `ToRadio(disconnect=true)` frame before closing the BLE connection
13. THE Meshtastic_Service SHALL support bonding with the Meshtastic device and persist the bonded device address
14. THE Meshtastic_Service SHALL scan for Meshtastic devices matching the name pattern `^.*_([0-9a-fA-F]{4})$`
15. THE Meshtastic_Service SHALL request Android Bluetooth permissions (BLUETOOTH_CONNECT, BLUETOOTH_SCAN on Android 12+)

### Requirement 2: Meshtastic Messaging

**User Story:** As a golf cart operator, I want to send and receive text messages via the Meshtastic mesh network, so that I can communicate with other mesh users.

#### Acceptance Criteria

1. WHEN a text message is received via Meshtastic, THE GCD SHALL display the message with sender node ID, channel number, and timestamp
2. THE GCD SHALL support receiving broadcast messages (destination `0xFFFFFFFF`) and direct messages (destination matching local node number)
3. THE GCD SHALL support sending text messages to broadcast address on any configured channel
4. THE GCD SHALL support sending direct messages to specific node numbers
5. THE GCD SHALL support sending preformatted messages selectable from a configurable list
6. THE GCD SHALL support composing custom text messages via on-screen keyboard
7. THE GCD SHALL limit outbound message payload to 237 bytes (Meshtastic maximum payload)
8. WHEN the Meshtastic connection is first established, THE GCD SHALL send an AWAKE notification message (`~#01#GC#AWAKE#`) on channel 0 to broadcast address
9. THE GCD SHALL encode text messages using the `TEXT_MESSAGE_APP` port number for transmission
10. THE GCD SHALL play an audible notification tone when a new message is received

### Requirement 3: Weather Data Reception and Display

**User Story:** As a golf cart operator, I want to receive and display weather forecasts via Meshtastic, so that I can check the weather without using my phone.

#### Acceptance Criteria

1. WHEN a HoT_Packet with type `01` (weather) is received, THE Hot_Packet_Parser SHALL parse the weather data
2. THE Hot_Packet_Parser SHALL validate weather packets contain exactly 7 `#` delimiters and 12 `,` delimiters before parsing
3. THE Hot_Packet_Parser SHALL parse the weather packet format: `|#01#<current_temp>#<hr>,<glyph>,<temp>,<precip>#<hr>,<glyph>,<temp>,<precip>#<hr>,<glyph>,<temp>,<precip>#<hr>,<glyph>,<temp>,<precip>#`
4. THE GCD SHALL display current temperature and 4-hour forecast with hour label, weather glyph icon, temperature, and precipitation probability for each hour
5. THE GCD SHALL display the timestamp when weather data was last received
6. THE GCD SHALL persist weather data to local storage with the date received
7. WHEN the app starts and no live weather data has been received, THE GCD SHALL load stored weather data if it is from the current day
8. IF stored weather data is from a previous day or absent, THEN THE GCD SHALL send a request message (`~#01#GC#REQ_WX_ENT#`) to request fresh weather and entertainment data
9. THE Hot_Packet_Parser SHALL validate temperature fields are within range -99 to 999 degrees
10. THE Hot_Packet_Parser SHALL validate hour labels are 6 characters or fewer
11. THE Hot_Packet_Parser SHALL clear zero precipitation values (`0.0`) from display
12. IF a weather packet is malformed, THEN THE Hot_Packet_Parser SHALL discard the packet and log a diagnostic message

### Requirement 4: Venue and Event Entertainment Display

**User Story:** As a golf cart operator in The Villages, I want to see today's entertainment schedule at local venues, so that I can plan my evening activities.

#### Acceptance Criteria

1. WHEN a HoT_Packet with type `02` (venue/event) is received, THE Hot_Packet_Parser SHALL parse the venue and event data
2. THE Hot_Packet_Parser SHALL parse the venue/event packet format: `|#02#<venue>,<event>#<venue>,<event>#...#` where each venue-event pair is separated by `#` and venue name is separated from event name by `,`
3. THE GCD SHALL display venue/event data in a scrollable two-column table with venue names in column 1 and event names in column 2
4. THE GCD SHALL support displaying up to 12 venue/event entries
5. THE GCD SHALL display the timestamp when venue/event data was last received
6. THE GCD SHALL persist venue/event data to local storage with the date received
7. WHEN the app starts and no live venue/event data has been received, THE GCD SHALL load stored venue/event data if it is from the current day
8. WHEN new venue/event data is received while the entertainment screen is active, THE GCD SHALL refresh the display with the updated data

### Requirement 5: GPS and Navigation

**User Story:** As a golf cart operator, I want to see my current speed, heading, and location, so that I can navigate safely.

#### Acceptance Criteria

1. THE GCD SHALL obtain GPS data from the Android device's internal GPS sensor
2. THE GCD SHALL also support receiving GPS position data from the connected Meshtastic radio
3. THE GCD SHALL display current speed in miles per hour as an integer value
4. THE GCD SHALL filter GPS speed values below 2.5 mph to zero to eliminate GPS dither when stationary
5. THE GCD SHALL filter speed spikes exceeding 8 mph acceleration per second as GPS errors
6. WHEN speed is below 4 mph and decreasing, THE GCD SHALL report speed as zero for responsive stop detection
7. THE GCD SHALL require 2 consecutive speed readings above threshold before reporting movement (3 consecutive when screen is dimmed)
8. THE GCD SHALL display compass heading as a 16-point cardinal direction (N, NNE, NE, ENE, E, ESE, SE, SSE, S, SSW, SW, WSW, W, WNW, NW, NNW)
9. THE GCD SHALL display current latitude and longitude coordinates
10. THE GCD SHALL display satellite count and HDOP (Horizontal Dilution of Precision) value in format "sats/hdop" (e.g., "8/1.50")
11. THE GCD SHALL require 3 consecutive zero-satellite readings before displaying zero satellite count to prevent display flicker during brief signal dropouts
12. THE GCD SHALL estimate HDOP from satellite count when direct HDOP data is unavailable: ≥6 sats = 1.5, 4-5 sats = 2.0, <4 sats = 99.0
13. THE GCD SHALL display current date in format `Day, Mon DD` (e.g., "Mon, Jan 15")
14. THE GCD SHALL display current time in 12-hour format with AM/PM indicator
15. THE GCD SHALL handle timezone conversion with automatic daylight saving time transitions based on device locale
16. IF no GPS time update is received for 60 seconds, THEN THE GCD SHALL display "NO GPS" for the date field
17. THE GCD SHALL calculate sunrise and sunset times based on current GPS location and date
18. THE GCD SHALL display sunrise and sunset times in 12-hour format
19. WHEN GPS speed is invalid but location is valid and last known speed was below 5 mph, THE GCD SHALL report speed as zero; otherwise retain last known speed to avoid jarring display changes during brief signal loss

### Requirement 6: Odometer and Distance Tracking

**User Story:** As a golf cart operator, I want to track total distance traveled and trip distance, so that I can monitor usage and plan maintenance.

#### Acceptance Criteria

1. THE GCD SHALL accumulate total distance traveled (odometer) using GPS position-based calculation
2. THE GCD SHALL maintain a resettable trip odometer
3. THE GCD SHALL display both odometer and trip odometer with 1 decimal place precision in miles
4. THE GCD SHALL only accumulate distance when filtered speed is greater than zero (Doppler speed gating as primary gate)
5. THE GCD SHALL require position change of at least 2.6 feet (0.0005 miles) minimum before accumulating distance when Doppler speed confirms motion
6. IF Doppler speed data is unavailable, THEN THE GCD SHALL use a fallback minimum distance threshold of 10 feet (0.002 miles) before accumulating
7. THE GCD SHALL reject position-based speed calculations exceeding 30 mph as GPS errors
7. THE GCD SHALL roll over the odometer at 100,000 miles
8. THE GCD SHALL persist odometer values to local storage every 0.5 miles of travel
9. THE GCD SHALL persist odometer values to local storage before entering sleep/shutdown
10. THE GCD SHALL load persisted odometer values on startup

### Requirement 7: Service Reminder and Maintenance Tracking

**User Story:** As a golf cart operator, I want to track driving hours since last service, so that I know when maintenance is due.

#### Acceptance Criteria

1. THE GCD SHALL accumulate driving hours only when the vehicle is in motion (speed greater than zero)
2. THE GCD SHALL store driving hours in tenths of hours (6-minute resolution)
3. THE GCD SHALL use GPS time as the primary time source for hour accumulation (atomic clock accuracy)
4. IF GPS time is unavailable, THEN THE GCD SHALL fall back to system clock for hour accumulation
5. THE GCD SHALL only accept time deltas between 0 and 10 seconds to filter GPS glitches and device reboots
6. THE GCD SHALL display hours since last service
7. THE GCD SHALL provide a configurable service interval (default: 100 hours)
8. THE GCD SHALL persist driving hours to local storage every 1.0 hours of driving
9. THE GCD SHALL allow the user to reset the service hour counter after maintenance is performed

### Requirement 8: GCI Telemetry Connection

**User Story:** As a golf cart operator, I want the app to receive vehicle telemetry data from the GCI computer via Bluetooth, so that I can monitor battery voltage, fuel level, and temperature.

#### Acceptance Criteria

1. THE Telemetry_Service SHALL connect to the GCI ESP-32 computer via Bluetooth (Classic SPP or BLE)
2. THE Telemetry_Service SHALL receive telemetry data packets containing: headlight mode (int), outdoor luminosity (int), air temperature (float), battery voltage (float), and fuel level (float)
3. THE GCD SHALL display battery voltage
4. THE GCD SHALL display fuel level
5. THE GCD SHALL display outdoor air temperature with a user-configurable temperature offset adjustment
6. THE GCD SHALL display headlight mode status
7. THE Telemetry_Service SHALL send periodic heartbeat messages every 10 seconds to maintain connection
8. IF no heartbeat response is received from GCI within 40 seconds (4 missed heartbeats), THEN THE Telemetry_Service SHALL mark the GCI as disconnected
9. THE Telemetry_Service SHALL support pairing with a new GCI device via a broadcast discovery mechanism with a 6-second pairing timeout window
10. WHEN pairing is initiated, THE Telemetry_Service SHALL broadcast a pairing command containing the GCD's MAC address and wait for an ACK response from the GCI within the timeout window
11. IF no ACK is received within the 6-second pairing window, THEN THE Telemetry_Service SHALL restore the previously paired device address (if any)
12. THE Telemetry_Service SHALL persist the paired GCI device address for automatic reconnection
13. WHEN the GCI connection is established, THE GCD SHALL send current "at home" and "is daytime" status to the GCI
14. WHEN the "at home" or "is daytime" status changes, THE GCD SHALL notify the GCI of the change
15. THE Telemetry_Service SHALL support sending GPS data (latitude, longitude, altitude, speed, heading, satellite info) to the GCI periodically

### Requirement 9: Home Location and Geofencing

**User Story:** As a golf cart operator, I want to set my home location and know when I am within my home area, so that the system can adjust behavior (like GPS update frequency) based on whether I am home or away.

#### Acceptance Criteria

1. THE GCD SHALL allow the user to set the current GPS position as the home location
2. THE GCD SHALL allow the user to clear the saved home location
3. THE GCD SHALL provide a configurable geofence radius (default: 500 meters)
4. THE GCD SHALL continuously calculate distance from current position to home location
5. WHEN the device enters the home geofence, THE GCD SHALL set the "at home" status to true and notify the GCI
6. WHEN the device leaves the home geofence, THE GCD SHALL set the "at home" status to false and notify the GCI
7. THE GCD SHALL persist home location coordinates to local storage
8. IF GPS is not available when the user attempts to set home location, THEN THE GCD SHALL reject the request and display an error message

### Requirement 10: Display Brightness Management

**User Story:** As a golf cart operator, I want the display brightness to automatically adjust based on time of day, so that the screen is readable in daylight and not blinding at night.

#### Acceptance Criteria

1. THE Backlight_Manager SHALL automatically set display brightness to the day brightness level between sunrise and sunset
2. THE Backlight_Manager SHALL automatically set display brightness to the night brightness level between sunset and sunrise
3. THE GCD SHALL provide separate configurable brightness levels for day and night (range 0-10 scale)
4. THE GCD SHALL provide a configurable inactivity timeout (in minutes) for screen dimming
5. WHEN no touch or movement activity is detected for the configured timeout period, THE Backlight_Manager SHALL dim the display to off
6. WHEN touch or movement activity is detected while the display is dimmed, THE Backlight_Manager SHALL restore the display to the appropriate brightness level
7. IF the inactivity timeout is set to 0, THEN THE Backlight_Manager SHALL disable automatic dimming

### Requirement 11: Power and Sleep Management

**User Story:** As a golf cart operator, I want the app to manage power efficiently, so that it does not drain the device battery unnecessarily when the cart is parked.

#### Acceptance Criteria

1. THE Sleep_Manager SHALL implement a three-state power management system: STARTUP_GRACE, GCI_MODE, and STANDALONE_MODE (NO_GCI_MODE)
2. THE Sleep_Manager SHALL implement a startup grace period equal to the backlight timeout setting (minimum 30 seconds) to allow the GCI to connect before determining operating mode
3. WHEN the GCI is connected and communicating, THE Sleep_Manager SHALL operate in GCI mode where sleep is controlled by the GCI connection status
4. WHEN the GCI has never connected or has been disconnected for the timeout period, THE Sleep_Manager SHALL operate in standalone mode with backlight dimming only (never enters deep sleep)
5. IF the GCI was previously connected during the current session but has since disconnected for the backlight timeout period, THEN THE Sleep_Manager SHALL transition to standalone mode
6. IN standalone mode, THE Sleep_Manager SHALL adjust the Meshtastic radio GPS update interval based on at_home status: 120 seconds (2 minutes) when at home, 8 seconds when away
7. THE Sleep_Manager SHALL persist odometer and driving hour values before entering sleep
8. THE GCD SHALL provide a manual reboot option in the configuration screen

### Requirement 12: Meshtastic Radio Administration

**User Story:** As a golf cart operator, I want to configure and manage my Meshtastic radio from the app, so that I can adjust settings without a separate computer.

#### Acceptance Criteria

1. THE GCD SHALL display the connected Meshtastic radio's node ID in hex format (e.g., `!a1b2c3d4`)
2. THE GCD SHALL support sending a reboot command to the Meshtastic radio
3. THE GCD SHALL support reading the radio's position configuration during the connection handshake
4. THE GCD SHALL support setting the GPS update interval on the Meshtastic radio (default: 8 seconds when away, 120 seconds when at home)
5. THE GCD SHALL use a read-modify-write pattern when updating radio configuration to preserve unmodified fields
6. THE GCD SHALL encode admin commands using the `ADMIN_APP` port number with protobuf `AdminMessage` format
7. THE GCD SHALL allow enabling/disabling the Meshtastic connection from the configuration screen

### Requirement 13: User Interface and Navigation

**User Story:** As a golf cart operator, I want a clean, widget-based main display with easy access to configuration, so that I can see important information at a glance while driving.

#### Acceptance Criteria

1. THE GCD SHALL provide a main display screen with configurable widgets showing: speed, heading, time, date, temperature, satellite/HDOP info, and connection status indicators
2. THE GCD SHALL provide a weather forecast screen showing current temperature and 4-hour forecast
3. THE GCD SHALL provide a venue/event entertainment screen showing today's schedule in a scrollable table
4. THE GCD SHALL provide a configuration screen accessible from the main display with unobtrusive navigation
5. THE GCD SHALL provide configuration controls for: day brightness, night brightness, speaker volume, screen flip, backlight timeout, temperature offset, service interval, home location, GCI pairing, and Meshtastic enable/disable
6. THE GCD SHALL display connection status indicators for both Meshtastic and GCI connections
7. THE GCD SHALL display the app version string on the configuration screen
8. THE GCD SHALL display the device MAC/identifier on the configuration screen
9. THE GCD SHALL support screen rotation (flip) configurable by the user
10. THE GCD SHALL provide a "new data received" visual indicator when fresh weather or entertainment data arrives
11. THE GCD SHALL auto-clear the "new data received" indicator after 5 seconds

### Requirement 14: Audio Feedback

**User Story:** As a golf cart operator, I want audible feedback for important events, so that I am alerted to new messages and system events without looking at the screen.

#### Acceptance Criteria

1. THE GCD SHALL play a startup tone when the application launches
2. THE GCD SHALL play a message notification tone when a new Meshtastic message is received
3. THE GCD SHALL play an alert tone for important notifications
4. THE GCD SHALL play a confirmation tone for successful user actions
5. THE GCD SHALL play a click tone for button presses
6. THE GCD SHALL play an error tone for failed operations
7. THE GCD SHALL provide a configurable speaker volume level (range 0-20)
8. THE GCD SHALL persist the speaker volume setting

### Requirement 15: Persistent Configuration Storage

**User Story:** As a golf cart operator, I want my settings to be saved between app restarts, so that I do not have to reconfigure the app every time.

#### Acceptance Criteria

1. THE GCD SHALL persist the following settings to local storage: day brightness, night brightness, speaker volume, screen flip, backlight timeout, temperature offset, service interval hours, GCI MAC address, home location coordinates, home geofence radius
2. THE GCD SHALL load all persisted settings on application startup
3. THE GCD SHALL debounce writes to persistent storage by 2 seconds for slider/spinner values to reduce storage wear
4. THE GCD SHALL provide a "reset all preferences" option that clears all saved settings and restarts the application
5. THE GCD SHALL persist weather data, venue/event data, and their timestamps for same-day cache restoration
6. THE GCD SHALL persist odometer values, trip odometer values, and driving hours

### Requirement 16: Project Repository Setup

**User Story:** As a developer, I want the project repository to follow best practices with proper tooling and documentation, so that the project is maintainable and welcoming to contributors.

#### Acceptance Criteria

1. THE Repository SHALL use Android Studio with Java as the primary language
2. THE Repository SHALL use Gradle as the build system with Kotlin DSL for build scripts
3. THE Repository SHALL include a Renovate configuration file for automated dependency updates
4. THE Repository SHALL include a Dependabot configuration file for GitHub security updates
5. THE Repository SHALL include a comprehensive README.md with project overview, setup instructions, architecture description, and screenshots placeholder
6. THE Repository SHALL include a CONTRIBUTING.md with coding standards, branch naming conventions, PR process, and development setup instructions
7. THE Repository SHALL include a CHANGES.md changelog following Keep a Changelog format
8. THE Repository SHALL include an MIT License file
9. THE Repository SHALL follow semantic versioning (MAJOR.MINOR.PATCH)
10. THE Repository SHALL include a `.gitignore` file appropriate for Android/Gradle projects
11. THE Repository SHALL include developer documentation covering: architecture overview, module descriptions, Bluetooth communication protocol details, protobuf message formats, data flow diagrams, and testing instructions
12. THE Repository SHALL include the Meshtastic protobuf definition files (`.proto`) as a source dependency for code generation
13. THE Repository SHALL structure the project with clear separation of concerns: UI layer, domain/business logic layer, data/communication layer, and dependency injection

### Requirement 17: Dual Bluetooth Connection Management

**User Story:** As a golf cart operator, I want the app to maintain simultaneous Bluetooth connections to both the Meshtastic radio and the GCI telemetry computer, so that all features work together seamlessly.

#### Acceptance Criteria

1. THE GCD SHALL support two simultaneous Bluetooth connections: one BLE connection to the Meshtastic radio and one connection to the GCI
2. THE GCD SHALL manage each Bluetooth connection independently with separate connection state tracking
3. IF the Meshtastic BLE connection is lost, THEN THE Meshtastic_Service SHALL attempt automatic reconnection without affecting the GCI connection
4. IF the GCI Bluetooth connection is lost, THEN THE Telemetry_Service SHALL attempt automatic reconnection without affecting the Meshtastic connection
5. THE GCD SHALL display independent connection status indicators for each Bluetooth connection
6. THE GCD SHALL request all necessary Android Bluetooth permissions at runtime with appropriate user-facing rationale

### Requirement 18: Meshtastic Protocol Implementation Details

**User Story:** As a developer, I want the Meshtastic BLE protocol implementation to be fully documented, so that the app can communicate correctly with the radio after the reference repositories are removed.

#### Acceptance Criteria

1. THE Meshtastic_Service SHALL implement the following BLE GATT interaction pattern: subscribe to FROMNUM notifications, then write `ToRadio` packets to TORADIO characteristic, then poll FROMRADIO characteristic when FROMNUM notification arrives
2. THE Meshtastic_Service SHALL frame `ToRadio` protobuf messages with a 4-byte big-endian length prefix before writing to the TORADIO characteristic
3. THE Meshtastic_Service SHALL handle MTU negotiation and split large packets if the write value length exceeds the negotiated MTU minus 3 bytes (default safe payload: 20 bytes without MTU negotiation)
4. THE Meshtastic_Service SHALL process the following `FromRadio` message types during handshake: `my_info` (tag 3 — node info and node number), `config` (tag 5 — radio configuration including position config), and `config_complete_id` (tag 7 — handshake complete signal)
5. THE Meshtastic_Service SHALL extract the local node number from the `my_info` message for use in addressing
6. THE Meshtastic_Service SHALL process incoming `MeshPacket` messages and route them based on `portnum`: `TEXT_MESSAGE_APP` for text messages, `ADMIN_APP` for admin responses, `POSITION_APP` for position data, `TELEMETRY_APP` for node telemetry
7. THE Meshtastic_Service SHALL construct outbound `MeshPacket` messages with: unique random packet ID, destination node number, channel index, decoded payload with appropriate port number, and payload bytes
8. THE Meshtastic_Service SHALL wrap outbound `MeshPacket` in a `ToRadio` message with the `packet` variant
9. THE GCD SHALL include all necessary Meshtastic `.proto` files: `mesh.proto`, `admin.proto`, `config.proto`, `portnums.proto`, `telemetry.proto`, `module_config.proto`, `channel.proto`, and supporting definitions
10. THE GCD SHALL generate Kotlin data classes from `.proto` files using the Square Wire protobuf compiler

### Requirement 19: Data Synchronization and Caching

**User Story:** As a golf cart operator, I want the app to cache received data and restore it on restart, so that I see the latest information immediately without waiting for new transmissions.

#### Acceptance Criteria

1. WHEN weather data is received with a valid GPS timestamp, THE GCD SHALL persist the raw packet data, parsed timestamp, and current date (YYYYMMDD format) to local storage
2. WHEN venue/event data is received with a valid GPS timestamp, THE GCD SHALL persist the raw packet data, parsed timestamp, and current date (YYYYMMDD format) to local storage
3. WHEN the app starts and GPS time becomes available, THE GCD SHALL validate cached data by comparing the stored date against today's date
4. IF cached data is from today, THEN THE GCD SHALL restore it to the display with a "(stored)" indicator appended to the timestamp
5. IF cached data is from a previous day or absent, THEN THE GCD SHALL request fresh data from the mesh network
6. WHEN live data is received that replaces cached data, THE GCD SHALL clear the "(stored)" indicator

