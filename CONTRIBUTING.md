# Contributing to Android Golf Cart Computer

Thank you for your interest in contributing to the Android Golf Cart Computer project. This document outlines the coding standards, development workflow, and contribution process.

## Coding Standards

### Language Usage

| Language | Usage |
|----------|-------|
| **Java** | Primary language for all application code |
| **Kotlin** | BLE communication (Kable library), Protobuf (Wire library), coroutine-heavy modules |

When in doubt, use Java. Kotlin is reserved for modules that directly interface with Kotlin-native libraries (Kable, Wire) or where coroutine support is essential.

### Style Guidelines

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) for Java code
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) for Kotlin code
- Use 4-space indentation (no tabs)
- Maximum line length: 120 characters
- All public classes and methods must have Javadoc/KDoc comments
- Use meaningful variable and method names over comments

### Architecture Rules

- Respect the three-layer architecture: UI → Domain → Data
- No direct references from UI to Data layer (always go through Domain)
- Use Hilt for all dependency injection (no manual instantiation of services)
- All state exposed to UI must flow through `StateFlow` or `SharedFlow`
- Bluetooth connections must run in Foreground Services

### Testing Requirements

- All new domain logic must include property-based tests (jqwik)
- All new features must include unit tests (JUnit 5)
- Property tests must reference their requirement: `Validates: Requirements X.Y`
- Minimum 100 iterations per property test
- Do not mock core domain logic in tests

## Branch Naming Conventions

Use the following prefixes:

| Prefix | Purpose | Example |
|--------|---------|---------|
| `feature/` | New features | `feature/weather-display` |
| `bugfix/` | Bug fixes | `bugfix/ble-reconnect-crash` |
| `hotfix/` | Urgent production fixes | `hotfix/data-loss-on-sleep` |
| `refactor/` | Code refactoring | `refactor/gps-processor-cleanup` |
| `docs/` | Documentation changes | `docs/bluetooth-protocol` |
| `test/` | Test additions/fixes | `test/odometer-properties` |

Branch names should be lowercase with hyphens separating words.

## Pull Request Process

1. **Create a branch** from `main` using the naming convention above
2. **Make your changes** with clear, atomic commits
3. **Write/update tests** for any changed functionality
4. **Run the full test suite** locally:
   ```bash
   ./gradlew test
   ./gradlew lint
   ```
5. **Open a Pull Request** with:
   - A clear title describing the change
   - Description of what was changed and why
   - Reference to any related issues
   - Screenshots for UI changes
6. **Address review feedback** promptly
7. **Squash and merge** once approved

### PR Checklist

- [ ] Code compiles without warnings
- [ ] All existing tests pass
- [ ] New tests added for new functionality
- [ ] Property tests validate correctness properties where applicable
- [ ] No new lint warnings introduced
- [ ] Documentation updated if needed
- [ ] Changelog updated in `CHANGES.md`

## Development Setup

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17** (Android Studio bundles this)
- **Android SDK** with:
  - Build Tools 34.0.0
  - Platform API 34 (compileSdk)
  - Platform API 26 (minSdk for testing)
- **Physical Android device** with Bluetooth for BLE testing

### Getting Started

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd android-golf-cart-computer
   ```

2. Open the project in Android Studio

3. Sync Gradle (Android Studio will prompt automatically)

4. Verify the build:
   ```bash
   ./gradlew assembleDebug
   ```

5. Run tests:
   ```bash
   ./gradlew test
   ```

### Protobuf Code Generation

Wire-generated Kotlin classes are produced from `.proto` files in `app/src/main/proto/meshtastic/`. The Wire Gradle plugin handles generation automatically during build. If you modify `.proto` files:

```bash
./gradlew generateProtos
```

### Running on Device

BLE functionality requires a physical device. To test:

1. Enable Developer Options on your Android device
2. Enable USB Debugging
3. Connect via USB and select your device in Android Studio
4. Run the app (Shift+F10)

For Meshtastic testing, you need a Meshtastic radio device powered on and in range.

## Reporting Issues

When reporting bugs, please include:
- Android version and device model
- Steps to reproduce
- Expected vs actual behavior
- Relevant log output (`adb logcat`)
- Meshtastic firmware version (if BLE-related)

## Code of Conduct

Be respectful, constructive, and collaborative. We're all here to build something useful for the golf cart community.
