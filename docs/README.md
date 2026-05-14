# Android Golf Cart Computer — Documentation

Welcome to the documentation for the Android Golf Cart Computer (GCD) application. This index provides an overview of all available documentation and guides.

## Documentation Index

| Document | Description |
|----------|-------------|
| [Developer Guide](developer-guide.md) | Architecture deep-dive, module descriptions, data flow, coding patterns, and testing instructions |
| [Design Documentation](design.md) | System design, component interfaces, data models, protocols, and correctness properties |
| [Features List](features.md) | Complete list of application features organized by category |
| [Build Guide](build-guide.md) | Step-by-step instructions for building, testing, and deploying the application |
| [Future Ideas](future-ideas.md) | Planned enhancements, feature requests, and long-term roadmap |

## Project Overview

The Android Golf Cart Computer is a native Android application designed for The Villages Retirement Community golf cart ecosystem. It replaces the memory-constrained ESP-32 based Golf Cart Display Computer with a more capable Android platform.

### System Context

The GCD operates as part of a three-component system:

```
┌─────────────────┐     BLE      ┌─────────────────┐    LoRa Mesh    ┌─────────────────┐
│   GCD (Android) │◄────────────►│  GCM (Meshtastic│◄──────────────►│  Other Mesh     │
│   Display App   │              │  Radio)          │                │  Nodes          │
└────────┬────────┘              └─────────────────┘                └─────────────────┘
         │
         │ Bluetooth
         │
┌────────▼────────┐
│  GCI (ESP-32)   │
│  Vehicle        │
│  Telemetry      │
└─────────────────┘
```

- **GCD** — Golf Cart Display (this app): Shows speed, navigation, weather, entertainment, and vehicle telemetry
- **GCM** — Golf Cart Meshtastic: A Meshtastic radio providing mesh messaging, GPS relay, and data transmission
- **GCI** — Golf Cart Internal: An ESP-32 computer providing battery voltage, fuel level, temperature, and headlight status

### Quick Links

- [Root README](../README.md) — Project overview and quick start
- [CONTRIBUTING.md](../CONTRIBUTING.md) — Contribution guidelines and coding standards
- [CHANGES.md](../CHANGES.md) — Version history and changelog
- [LICENSE](../LICENSE) — MIT License

## Getting Help

If you're new to the project:
1. Start with the [Build Guide](build-guide.md) to get the project running
2. Read the [Features List](features.md) to understand what the app does
3. Dive into the [Developer Guide](developer-guide.md) for architecture and code details
4. Check [Design Documentation](design.md) for protocol specifications and data models
