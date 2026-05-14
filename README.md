# Android Golf Cart Computer (GCD)

A native Android application that replaces the ESP-32 based Golf Cart Display Computer. The app communicates with two external Bluetooth devices simultaneously: a Meshtastic radio (GCM) via BLE for mesh messaging and GPS, and a Golf Cart Internal computer (GCI) via Bluetooth Classic/BLE for vehicle telemetry.

Built for The Villages Retirement Community golf cart ecosystem.

## Architecture

The application follows a **three-layer architecture** with clear separation of concerns:

### UI Layer
- **Jetpack Compose** with Material 3 design
- Screens: Main Display, Weather, Entertainment, Configuration
- Reactive state observation via `collectAsState()`

### Domain Layer
- Business logic and data transformation
- **Kotlin Coroutines** and **StateFlow** for reactive state management
- Components: GpsProcessor, OdometerManager, ServiceReminderManager, HotPacketParser, SleepManager, BacklightManager, GeofenceManager, AudioManager

### Data Layer
- **Kable** for Bluetooth Low Energy communication
- **Wire** (Square) for Protobuf code generation from Meshtastic `.proto` files
- **Jetpack DataStore** for type-safe persistent storage
- Android FusedLocationProvider for GPS

### Dependency Injection
- **Hilt** provides the DI container
- Modules: AppModule, BluetoothModule, LocationModule, ServiceModule

## Dual Bluetooth Connections

The app maintains two independent, simultaneous Bluetooth connections:

| Connection | Device | Protocol | Purpose |
|-----------|--------|----------|---------|
| Meshtastic BLE | GCM Radio | BLE GATT + Protobuf | Mesh messaging, GPS relay, weather/event data |
| GCI Telemetry | GCI ESP-32 | Bluetooth Classic/BLE | Battery voltage, fuel, temperature, headlights |

Both connections run as **Android Foreground Services** to maintain connectivity when the app is backgrounded or the screen is off. Each connection has independent state tracking and automatic reconnection.

### Meshtastic BLE Protocol
- Service UUID: `6ba1b218-15a8-461f-9fa8-5dcae273eafd`
- Outbound: 4-byte big-endian length prefix + protobuf `ToRadio` bytes
- Inbound: FROMNUM notification triggers FROMRADIO polling
- Handshake: `want_config_id` → `my_info` → `config` → `config_complete_id`
- 30-second heartbeat, 60-second liveness timeout

### GCI Protocol
- Message envelope: type (1B) + timestamp (4B) + seq_num (2B) + data_len (2B) + data
- 10-second heartbeat, 40-second disconnect timeout
- 6-second pairing window with broadcast discovery

## Key Technologies

| Technology | Purpose |
|-----------|---------|
| Java | Primary language |
| Kotlin | BLE (Kable) and Protobuf (Wire) interop |
| Jetpack Compose | Declarative UI |
| Hilt | Dependency injection |
| Kable | Kotlin Multiplatform BLE library |
| Wire | Square's protobuf code generation |
| DataStore | Persistent preferences and cache |
| jqwik | Property-based testing (20 correctness properties) |
| JUnit 5 | Unit testing |
| MockK | Kotlin mocking |
| Turbine | Flow testing |

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK with API 34 (compileSdk) and API 26+ (minSdk)
- A physical Android device with Bluetooth (emulator does not support BLE)

### Build
```bash
# Clone the repository
git clone <repository-url>
cd android-golf-cart-computer

# Build the project
./gradlew assembleDebug

# Run unit and property tests
./gradlew test

# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

### Project Structure
```
app/
├── src/
│   ├── main/
│   │   ├── java/com/golfcart/gcd/
│   │   │   ├── ui/          # Jetpack Compose screens
│   │   │   ├── domain/      # Business logic managers
│   │   │   ├── data/        # BLE services, persistence
│   │   │   └── di/          # Hilt modules
│   │   └── proto/           # Meshtastic .proto definitions
│   ├── test/                # Unit + property tests (jqwik)
│   └── androidTest/         # Instrumented tests
├── build.gradle.kts
└── libs.versions.toml
```

## Testing

The project uses **property-based testing** with jqwik to verify 20 correctness properties covering:
- Protobuf serialization round-trips
- Packet framing and message routing
- Weather/venue packet parsing and validation
- GPS speed filtering pipeline
- Odometer distance accumulation
- State machine transitions
- Geofencing and brightness selection

Run all tests:
```bash
./gradlew test
```

## Documentation

Detailed documentation is available in the [`docs/`](docs/) directory:

| Document | Description |
|----------|-------------|
| [Documentation Index](docs/README.md) | Overview and index of all documentation |
| [Developer Guide](docs/developer-guide.md) | Architecture deep-dive, module descriptions, data flow, and coding patterns |
| [Design Documentation](docs/design.md) | Component interfaces, protocols, data models, and correctness properties |
| [Features List](docs/features.md) | Complete feature list organized by category |
| [Build Guide](docs/build-guide.md) | Step-by-step build, test, and deployment instructions |
| [Future Ideas](docs/future-ideas.md) | Planned enhancements and long-term roadmap |

## Screenshots

<!-- TODO: Add screenshots of the main display, weather, entertainment, and config screens -->

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
