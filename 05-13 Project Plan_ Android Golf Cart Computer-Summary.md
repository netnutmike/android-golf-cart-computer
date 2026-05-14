# 05-13 Project Plan: Android Golf Cart Computer

[image]

## Android Golf Cart Computer Project Plan
### Project Goal
Develop an Android-based golf cart computer application to replace the current ESP32 system, replicating and expanding existing functionality.
### Core Problem with Current System
- **Memory Limitations:** The ESP32 device is running out of memory.
- **Feature Scalability:** Memory constraints prevent adding desired new features.
- **Hardware Constraints:** A larger display is desired and better supported on Android.
### High-Level Requirements & Features
#### 1. Main Display Screen & UI
- A flexible, widget-based user interface.
- Users can choose which widgets to display and their placement.
- Provide a default layout with:
  - Speedometer
  - Fuel Gauge
  - Direction (Compass)
  - Outside Temperature
#### 2. Data Points to Display
- **Primary Data:**
  - Speed
  - Direction
  - Fuel level (represented as battery voltage if possible)
  - Outside temperature
- **Odometers:**
  - Standard Odometer
  - Trip Odometer (potentially multiple)
#### 3. Connectivity & Data Sources
Handle two simultaneous Bluetooth connections:
- **Connection 1 (Meshtastic):** Messaging, GPS, and other data.
- **Connection 2 (Telemetry Radio):** ESP32-based central computer providing vehicle data (e.g., battery voltage, fuel level) over Bluetooth.
#### 4. Meshtastic Integration
- **Receive Messages:** Display messages via Meshtastic.
- **Send Messages:** Support preformatted and custom messages via Meshtastic.
- **Data Reception:** Continue existing functionalities:
  - Receive telemetry data from other Meshtastic nodes.
  - Display preformatted messages for weather updates.
  - Display preformatted messages for live local events and music schedules.
#### 5. GPS Functionality
Support GPS data from:
- The Android device’s internal GPS.
- The connected Meshtastic unit’s GPS.
#### 6. Configuration
- Provide a dedicated configuration area ("config area").
- Define how to access settings or change screens from the main display, referencing the current ESP32 implementation.
- Keep the main display clean, with configuration controls hidden or unobtrusive.
### Development & Technology
- **Platform:** Android (not cross-platform).
- **IDE/Language:** Android Studio using standard Java/Android methodology.
- **Code Reuse:** Use the Android-based Meshtastic app Git repository as a reference for Meshtastic integration.
## 📅 Next Arrangements & Action Items
- [ ] Review the ESP32 golf cart computer source code to identify all existing features.
- [ ] Create a detailed requirements document based on the feature review and the new requirements outlined.
- [ ] Define a flexible, widget-based UI, including a default layout.
- [ ] Investigate and architect two concurrent Bluetooth connections (Meshtastic and telemetry radio), including connection management and data handling.
- [ ] Locate and evaluate the Git repository for the Android-based Meshtastic application as an integration reference.
- [ ] Design the configuration screen and user flow for accessing it from the main display, ensuring unobtrusive controls.
- [ ] Set up the initial Android project in Android Studio to begin development.