# Developer Guide

This guide covers the architecture, module structure, data flow patterns, and testing approach for the Android Golf Cart Computer application.

## Architecture Overview

The application follows a **three-layer architecture** with strict dependency rules:

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│  Jetpack Compose screens observe state via StateFlow    │
├─────────────────────────────────────────────────────────┤
│                    Domain Layer                         │
│  Business logic, state coordination, data transforms    │
├─────────────────────────────────────────────────────────┤
│                     Data Layer                          │
│  BLE services, persistence, platform APIs               │
└─────────────────────────────────────────────────────────┘
```

**Dependency rules:**
- UI depends on Domain (never directly on Data)
- Domain depends on Data interfaces (not implementations)
- Data implements interfaces defined in Domain
- All cross-layer communication uses Kotlin `StateFlow` / `SharedFlow`

## Project Structure

```
app/src/main/java/com/golfcart/gcd/
├── ui/                          # Jetpack Compose UI layer
│   ├── main/                    # Main display screen (speed, heading, status)
│   ├── weather/                 # Weather forecast screen
│   ├── entertainment/           # Venue/event entertainment screen
│   └── config/                  # Configuration/settings screen
├── domain/                      # Business logic layer
│   ├── gps/                     # GpsProcessor — speed filtering, heading, navigation
│   ├── odometer/                # OdometerManager — distance tracking
│   ├── service/                 # ServiceReminderManager — driving hours
│   ├── sleep/                   # SleepManager — three-state power management
│   ├── brightness/              # BacklightManager — day/night brightness
│   ├── geofence/                # GeofenceManager — home location, at-home status
│   ├── audio/                   # AudioManager — system tones and volume
│   └── parser/                  # HotPacketParser — weather/venue packet parsing
├── data/                        # Data/communication layer
│   ├── bluetooth/               # MeshtasticService, TelemetryService
│   ├── location/                # Android GPS location provider
│   ├── persistence/             # DataStoreRepository, cache management
│   └── proto/                   # Wire-generated protobuf classes
└── di/                          # Hilt dependency injection modules
    ├── AppModule.kt             # Singletons: DataStore, AudioManager, repos
    ├── BluetoothModule.kt       # BLE scanner, Kable peripheral factories
    ├── LocationModule.kt        # FusedLocationProviderClient
    └── ServiceModule.kt         # Service-scoped dependencies

app/src/main/proto/meshtastic/   # Meshtastic .proto definition files
app/src/test/                    # Unit tests + property tests (jqwik, JUnit 5)
app/src/androidTest/             # Instrumented tests (Compose UI, integration)
```

## Module Descriptions

### UI Layer

#### MainViewModel
Aggregates state from all domain managers into a single UI state object. Compose screens observe this ViewModel via `collectAsState()`.

#### Screens
- **Main Display** — Speed (mph), heading (16-point cardinal), time, date, temperature, satellite/HDOP, connection indicators, odometer, battery/fuel
- **Weather** — Current temperature, 4-hour forecast with glyphs, last-received timestamp
- **Entertainment** — Scrollable two-column table of venue/event pairs (up to 12)
- **Configuration** — Brightness sliders, volume, timeout, home location, pairing, Meshtastic controls

### Domain Layer

#### GpsProcessor
Consumes raw GPS from Android's FusedLocationProvider and Meshtastic position packets. Applies a multi-stage speed filtering pipeline:

1. **Dither elimination** — Speeds below 2.5 mph → zero
2. **Spike rejection** — Acceleration > 8 mph/s discarded
3. **Stop detection** — Speed < 4 mph and decreasing → zero
4. **Consecutive threshold** — 2 readings above threshold required (3 when dimmed)
5. **Invalid speed fallback** — If location valid and last speed < 5 mph → zero

Also handles:
- 16-point cardinal direction from bearing (22.5° intervals)
- Satellite count debounce (3 consecutive zeros before displaying zero)
- HDOP estimation (≥6 sats = 1.5, 4-5 = 2.0, <4 = 99.0)
- Date/time formatting with DST transitions
- Sunrise/sunset calculation

#### OdometerManager
Accumulates distance using GPS position deltas with strict gating:
- Doppler speed must be > 0 (primary gate)
- Minimum 2.6 feet position change with Doppler, 10 feet without
- Position-implied speed must be ≤ 30 mph
- Rolls over at 100,000 miles
- Persists every 0.5 miles and before sleep

#### ServiceReminderManager
Tracks driving hours for maintenance scheduling:
- Accumulates only when speed > 0
- Stores in tenths of hours (6-minute resolution)
- GPS time preferred, system clock fallback
- Only accepts 0-10 second time deltas
- Persists every 1.0 hours

#### SleepManager
Three-state power management:
- **STARTUP_GRACE** — Waits for GCI connection (duration = backlight timeout, min 30s)
- **GCI_MODE** — Sleep controlled by GCI connection status
- **STANDALONE_MODE** — Backlight dimming only, never deep sleep

Adjusts Meshtastic GPS interval in standalone mode: 120s at home, 8s away.

#### BacklightManager
- Day brightness between sunrise and sunset
- Night brightness between sunset and sunrise
- Configurable inactivity timeout (0 disables)
- Restores on touch or movement

#### GeofenceManager
- Configurable home location and radius (default 500m)
- Continuous distance calculation
- Emits at_home status changes
- Notifies GCI on status change

#### HotPacketParser
Parses structured "Hands-off-Transmission" packets from Meshtastic messages:
- Weather (`|#01#`): 7 `#` delimiters, 12 `,` delimiters, temp range -99..999
- Venue/Event (`|#02#`): Up to 12 comma-separated venue,event pairs

#### AudioManager
System tones: startup, message notification, alert, confirmation, click, error. Volume range 0-20.

### Data Layer

#### MeshtasticService
Android Foreground Service managing BLE connection via Kable:
- Scans for devices matching `^.*_([0-9a-fA-F]{4})$`
- Implements full Meshtastic handshake protocol
- 4-byte length-prefix framing with MTU-aware packet splitting
- 30s heartbeat, 60s liveness timeout
- Exposes `MeshtasticConnection` interface to domain layer

#### TelemetryService
Android Foreground Service managing GCI Bluetooth connection:
- Message envelope: type(1B) + timestamp(4B) + seq_num(2B) + data_len(2B) + data
- 10s heartbeat, 40s disconnect timeout
- 6-second pairing window with broadcast discovery
- Sends GPS data, at_home, is_daytime to GCI

#### DataStoreRepository
Jetpack DataStore wrapper providing:
- User preferences with 2-second write debounce
- Weather/venue cache with date validation
- Odometer and driving hours persistence
- Reset all preferences

## Data Flow Patterns

### Meshtastic Message Reception
```
FROMNUM notification → Poll FROMRADIO → Decode protobuf FromRadio
→ Extract MeshPacket → Route by portnum:
  - TEXT_MESSAGE_APP → Check if HoT packet → Parse weather/venue OR display message
  - POSITION_APP → Feed to GpsProcessor
  - ADMIN_APP → Handle admin response
  - TELEMETRY_APP → Display node telemetry
```

### Weather Data Flow
```
Meshtastic text message → HotPacketParser.isHotPacket() → parseWeatherPacket()
→ Cache to DataStore with date → Emit to UI via StateFlow
→ On next startup: validate cache date → restore if same day, else request fresh
```

### GPS → Odometer Flow
```
FusedLocationProvider update → GpsProcessor speed filter pipeline
→ OdometerManager: check speed > 0, position delta > threshold, implied speed ≤ 30
→ Accumulate distance → Persist every 0.5 miles
```

### Sleep State Machine
```
App start → STARTUP_GRACE (wait for GCI)
  ├── GCI connects → GCI_MODE (sleep controlled by GCI)
  │     └── GCI disconnects for timeout → STANDALONE_MODE
  │           └── GCI reconnects → GCI_MODE
  └── Grace expires → STANDALONE_MODE (backlight dimming only)
```

## Dependency Injection

Hilt modules provide all dependencies:

| Module | Scope | Provides |
|--------|-------|----------|
| `AppModule` | Singleton | DataStore, AudioManager, repositories |
| `BluetoothModule` | Singleton | BLE scanner, Kable peripheral factories |
| `LocationModule` | Singleton | FusedLocationProviderClient, LocationRequest |
| `ServiceModule` | Service | Service-scoped dependencies |

ViewModels are injected with `@HiltViewModel` and receive domain managers via constructor injection.

## Reactive State Management

All state flows through the system using Kotlin coroutines:

```kotlin
// Data layer emits raw state
class MeshtasticServiceImpl : MeshtasticConnection {
    override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val incomingPackets = MutableSharedFlow<MeshPacket>()
}

// Domain layer transforms and combines
class GpsProcessorImpl(
    private val locationProvider: LocationProvider,
    private val meshtasticConnection: MeshtasticConnection
) : GpsProcessor {
    override val gpsState: StateFlow<ProcessedGpsData> = combine(
        locationProvider.locationUpdates,
        meshtasticConnection.incomingPackets.filterIsInstance<PositionPacket>()
    ) { androidGps, meshGps -> processAndFilter(androidGps, meshGps) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), ProcessedGpsData.EMPTY)
}

// UI layer observes
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val gpsState by viewModel.gpsState.collectAsState()
    SpeedDisplay(speed = gpsState.speedMph)
}
```

## Error Handling Strategy

| Category | Approach |
|----------|----------|
| BLE connection loss | Exponential backoff reconnection (1s, 2s, 4s, 8s, max 30s) |
| BLE write failure | Retry 3x, then report to UI |
| MTU negotiation failure | Fall back to 20-byte safe payload |
| GPS spike | Discard reading, retain previous value |
| Malformed packet | Discard, log diagnostic |
| DataStore failure | Retry with backoff, log error |
| Service killed by OS | Foreground notification ensures restart |
| Permission denied | Rationale dialog, guide to settings |

## Coding Patterns

### Java/Kotlin Interop
```java
// Java code calling Kotlin coroutine
ListenableFuture<Void> future = CoroutineUtils.toListenableFuture(
    scope, () -> meshtasticConnection.sendTextMessage(text, dest, channel)
);
```

### StateFlow Observation in Java
```java
// Java ViewModel observing Kotlin StateFlow
public class MainViewModel extends ViewModel {
    private final GpsProcessor gpsProcessor;
    
    public LiveData<ProcessedGpsData> getGpsState() {
        return gpsProcessor.getGpsState().asLiveData();
    }
}
```

### Foreground Service Pattern
```java
public class MeshtasticService extends LifecycleService {
    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        // Initialize BLE connection in coroutine scope
    }
}
```

## Testing

See the [Build Guide](build-guide.md) for commands. Key testing concepts:

### Property-Based Tests (jqwik)
20 correctness properties validated with random input generation. Each property runs minimum 100 iterations. Properties cover serialization, routing, parsing, filtering, accumulation, and state machines.

### Unit Tests (JUnit 5)
Example-based tests for specific scenarios: handshake sequences, timing, formatting, audio triggers.

### Integration Tests
End-to-end flows with mock peripherals: BLE lifecycle, dual Bluetooth independence, DataStore round-trips, foreground service lifecycle.

### Test Dependencies
- **jqwik** — Property-based testing framework
- **MockK** — Kotlin mocking library
- **Turbine** — Kotlin Flow testing
- **Robolectric** — Android framework mocking for unit tests
- **Compose UI Test** — Compose screen testing
