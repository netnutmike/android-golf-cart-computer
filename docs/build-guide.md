# Build Guide

Step-by-step instructions for building, testing, and deploying the Android Golf Cart Computer application.

## Prerequisites

### Required Software

| Software | Version | Notes |
|----------|---------|-------|
| Android Studio | Hedgehog (2023.1.1)+ | Includes bundled JDK 17 |
| JDK | 17 | Android Studio bundles this |
| Android SDK | API 34 (compileSdk) | Install via SDK Manager |
| Android SDK | API 26 (minSdk) | Minimum supported version |
| Git | 2.x | For version control |

### Hardware Requirements

- **Development machine**: macOS, Linux, or Windows with 8GB+ RAM
- **Test device**: Physical Android device with Bluetooth (API 26+)
  - BLE testing requires a real device (emulator does not support BLE)
  - Meshtastic radio device for end-to-end BLE testing
  - GCI ESP-32 device for telemetry testing (optional)

### Android SDK Components

Install these via Android Studio's SDK Manager (Settings → Languages & Frameworks → Android SDK):

- **SDK Platforms**: Android 14 (API 34)
- **SDK Tools**:
  - Android SDK Build-Tools 34.0.0
  - Android SDK Platform-Tools
  - Android Emulator (for non-BLE UI testing)

## Getting Started

### 1. Clone the Repository

```bash
git clone <repository-url>
cd android-golf-cart-computer
```

### 2. Open in Android Studio

1. Launch Android Studio
2. Select "Open" and navigate to the cloned directory
3. Wait for Gradle sync to complete (first sync downloads dependencies)

### 3. Verify the Build

```bash
./gradlew assembleDebug
```

This compiles the app, generates protobuf classes from `.proto` files, and produces a debug APK.

### 4. Run Tests

```bash
# Unit tests and property tests
./gradlew test

# Instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```

## Build Commands Reference

### Common Tasks

| Command | Description |
|---------|-------------|
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew assembleRelease` | Build release APK (requires signing config) |
| `./gradlew test` | Run all unit and property tests |
| `./gradlew connectedAndroidTest` | Run instrumented tests on device |
| `./gradlew lint` | Run Android lint checks |
| `./gradlew clean` | Clean build outputs |
| `./gradlew dependencies` | List all project dependencies |

### Protobuf Generation

Wire-generated Kotlin classes are produced automatically during build from `.proto` files in `app/src/main/proto/meshtastic/`.

```bash
# Regenerate protobuf classes manually
./gradlew generateProtos

# Clean and regenerate
./gradlew clean assembleDebug
```

### Test-Specific Commands

```bash
# Run only property tests (jqwik)
./gradlew test --tests "*Property*"

# Run a specific test class
./gradlew test --tests "com.golfcart.gcd.domain.parser.WeatherPacketPropertyTest"

# Run tests with verbose output
./gradlew test --info

# Generate test report
./gradlew test jacocoTestReport
```

## Project Configuration

### Gradle Files

| File | Purpose |
|------|---------|
| `build.gradle.kts` (root) | Project-level plugins and repositories |
| `app/build.gradle.kts` | App module: dependencies, SDK versions, plugins |
| `settings.gradle.kts` | Module inclusion and repository settings |
| `gradle/libs.versions.toml` | Centralized dependency version catalog |
| `gradle.properties` | Gradle and Android build properties |

### Key Build Configuration

```kotlin
// app/build.gradle.kts (key settings)
android {
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.golfcart.gcd"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### Version Catalog

Dependencies are managed in `gradle/libs.versions.toml`:

```toml
[versions]
compose-bom = "..."
hilt = "..."
kable = "..."
wire = "..."
datastore = "..."
coroutines = "..."
jqwik = "..."
mockk = "..."
turbine = "..."
robolectric = "..."
```

## Running on a Physical Device

### Setup

1. **Enable Developer Options** on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   
2. **Enable USB Debugging**:
   - Settings → Developer Options → USB Debugging → On

3. **Connect via USB** and authorize the computer when prompted

4. **Verify connection**:
   ```bash
   adb devices
   # Should show your device listed
   ```

### Install and Run

```bash
# Install debug build
./gradlew installDebug

# Or use Android Studio: Run → Run 'app' (Shift+F10)
```

### Wireless Debugging (Android 11+)

```bash
# Enable wireless debugging on device
# Settings → Developer Options → Wireless Debugging

# Pair (first time)
adb pair <ip>:<port>

# Connect
adb connect <ip>:<port>
```

## Testing with Bluetooth Devices

### Meshtastic Radio Testing

1. Power on a Meshtastic radio device
2. Ensure the radio's Bluetooth is enabled and advertising
3. The app scans for devices matching name pattern `^.*_([0-9a-fA-F]{4})$`
4. Grant Bluetooth permissions when prompted
5. The app will auto-connect and perform the handshake

### GCI ESP-32 Testing

1. Power on the GCI ESP-32 device
2. Use the Configuration screen to initiate pairing
3. The 6-second pairing window broadcasts discovery
4. Verify telemetry data appears on the main display

### Mock Testing (No Hardware)

For development without physical Bluetooth devices:
- Unit tests use MockK to mock Kable peripherals
- Integration tests use mock BLE peripheral implementations
- UI tests use fake data sources injected via Hilt test modules

## Signing and Release

### Debug Signing

Debug builds use the auto-generated debug keystore. No configuration needed.

### Release Signing

For release builds, create a signing configuration:

1. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore release-keystore.jks \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -alias gcd-release
   ```

2. Add to `local.properties` (not committed to git):
   ```properties
   RELEASE_STORE_FILE=../release-keystore.jks
   RELEASE_STORE_PASSWORD=your_password
   RELEASE_KEY_ALIAS=gcd-release
   RELEASE_KEY_PASSWORD=your_password
   ```

3. Build release APK:
   ```bash
   ./gradlew assembleRelease
   ```

Output: `app/build/outputs/apk/release/app-release.apk`

## Troubleshooting

### Build Issues

| Problem | Solution |
|---------|----------|
| Gradle sync fails | File → Invalidate Caches → Restart |
| Protobuf generation fails | Check `.proto` files in `app/src/main/proto/meshtastic/` |
| JDK version mismatch | Ensure JDK 17 in File → Project Structure → SDK Location |
| Out of memory | Increase `org.gradle.jvmargs` in `gradle.properties` |
| Dependency resolution failure | Check network, try `./gradlew --refresh-dependencies` |

### Test Issues

| Problem | Solution |
|---------|----------|
| jqwik tests not found | Ensure JUnit 5 platform is configured in `build.gradle.kts` |
| MockK errors | Verify MockK version matches Kotlin version |
| Robolectric download fails | Check network; Robolectric downloads Android JARs on first run |
| Instrumented tests fail to start | Verify device is connected: `adb devices` |

### Device Issues

| Problem | Solution |
|---------|----------|
| Device not detected | Try different USB cable; enable USB debugging |
| BLE scan returns nothing | Check Bluetooth and Location permissions; ensure radio is advertising |
| Connection drops immediately | Check if another app is connected to the Meshtastic radio |
| Permission denied at runtime | Uninstall and reinstall app to re-trigger permission dialogs |

## Continuous Integration

### GitHub Actions (Recommended)

The project includes Dependabot and Renovate configurations for automated dependency updates. For CI builds:

```yaml
# .github/workflows/build.yml (example)
name: Build and Test
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build
        run: ./gradlew assembleDebug
      - name: Test
        run: ./gradlew test
      - name: Lint
        run: ./gradlew lint
```

## Directory Structure After Build

```
app/build/
├── generated/
│   └── source/
│       └── wire/          # Wire-generated protobuf classes
├── outputs/
│   └── apk/
│       ├── debug/         # Debug APK
│       └── release/       # Release APK (if signed)
├── reports/
│   ├── tests/             # HTML test reports
│   └── lint/              # Lint report
└── test-results/          # JUnit XML results
```
