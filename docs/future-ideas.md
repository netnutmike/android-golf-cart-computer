# Future Ideas

Planned enhancements, feature requests, and long-term roadmap for the Android Golf Cart Computer.

## Near-Term Enhancements

### UI Improvements

- **Dark/Light theme toggle** — Allow users to switch between dark and light themes independent of time of day
- **Customizable widget layout** — Drag-and-drop arrangement of main screen widgets
- **Landscape/portrait auto-rotation** — Detect device orientation and adjust layout
- **Font size configuration** — Adjustable text size for readability at different mounting distances
- **Animated weather glyphs** — Replace static weather icons with subtle animations
- **Message history screen** — Dedicated screen showing full Meshtastic message history with search

### Navigation Enhancements

- **Turn-by-turn directions** — Basic routing within The Villages using OpenStreetMap data
- **Points of interest** — Mark and navigate to favorite locations (pools, recreation centers, restaurants)
- **Speed limit display** — Show posted speed limits for golf cart paths
- **Trail/path mapping** — Record and display frequently traveled routes
- **Elevation display** — Show current elevation from GPS data

### Connectivity

- **Wi-Fi data sync** — Sync weather/venue data over Wi-Fi when available (reduce mesh traffic)
- **OTA firmware updates for GCI** — Push ESP-32 firmware updates from the Android app
- **Multiple Meshtastic channel support** — Monitor and send on multiple channels simultaneously
- **Node map display** — Show positions of other Meshtastic nodes on a simple map
- **Message delivery confirmation** — Track whether sent messages were received by destination

## Medium-Term Features

### Vehicle Monitoring

- **Battery health trending** — Graph battery voltage over time to detect degradation
- **Fuel consumption tracking** — Calculate fuel efficiency based on distance and fuel level changes
- **Maintenance log** — Record service history with dates, mileage, and notes
- **Tire pressure monitoring** — Support TPMS sensors via Bluetooth (if hardware available)
- **Charging session tracking** — Log charge start/end times and energy consumed

### Social Features

- **Group ride coordination** — Share location with a group of golf carts in real-time
- **Convoy mode** — Follow-the-leader display showing distance to lead cart
- **Quick status messages** — One-tap status broadcasts ("At the pool", "Heading home", "Need help")
- **Buddy list** — Track online/offline status of known Meshtastic nodes

### Weather and Events

- **Multi-day forecast** — Extend weather display beyond 4 hours
- **Severe weather alerts** — Push notifications for weather warnings
- **Event reminders** — Set reminders for venue events
- **Event filtering** — Filter entertainment by venue, time, or event type
- **Calendar integration** — Sync venue events with device calendar

### Data and Analytics

- **Trip logging** — Automatic trip recording with start/end times, distance, and route
- **Usage statistics dashboard** — Daily/weekly/monthly driving summaries
- **Export data** — Export trip logs, maintenance records, and usage stats as CSV
- **Cloud backup** — Optional backup of settings and data to Google Drive

## Long-Term Vision

### Platform Expansion

- **Tablet support** — Optimized layout for larger screens (7-10 inch tablets)
- **Android Auto integration** — Display key information on Android Auto head units
- **Wear OS companion** — Glanceable speed and navigation on a smartwatch
- **iOS port** — Evaluate feasibility of an iOS version using Kotlin Multiplatform

### Advanced Navigation

- **Offline maps** — Download and display vector maps of The Villages
- **Golf course integration** — Show hole layouts, distances, and cart path restrictions
- **Traffic/congestion display** — Aggregate speed data from multiple carts to show congestion
- **Parking assistance** — Remember where you parked with GPS breadcrumb

### Hardware Integration

- **Dashcam integration** — Record video with GPS overlay
- **Backup camera display** — Show rear camera feed when in reverse
- **LED strip control** — Control underglow or accent lighting via GCI
- **Solar panel monitoring** — Display solar charge status for solar-equipped carts
- **Custom gauge cluster** — Replace physical gauges with digital display

### Community Features

- **Shared POI database** — Community-contributed points of interest
- **Event crowdsourcing** — Users report live entertainment updates
- **Cart-to-cart messaging** — Direct messaging between nearby golf carts
- **Lost cart finder** — Locate your cart using last-known GPS position
- **Community announcements** — Receive community-wide broadcasts via mesh

## Technical Debt and Improvements

### Code Quality

- **Increase test coverage** — Target 90%+ line coverage for domain layer
- **Mutation testing** — Add PIT mutation testing to validate test effectiveness
- **Static analysis** — Integrate detekt (Kotlin) and SpotBugs (Java) into CI
- **API documentation** — Generate Dokka/Javadoc for all public interfaces
- **Performance profiling** — Baseline and optimize battery consumption

### Architecture

- **Modularization** — Split into Gradle modules (`:core`, `:bluetooth`, `:ui`, `:domain`)
- **Compose Navigation** — Migrate to type-safe Compose Navigation with deep links
- **Room database** — Consider Room for complex data (trip logs, message history)
- **WorkManager** — Use WorkManager for periodic background tasks (cache cleanup, sync)
- **Baseline profiles** — Add Jetpack Baseline Profiles for startup optimization

### DevOps

- **CI/CD pipeline** — Automated build, test, lint, and APK distribution
- **Crash reporting** — Integrate Firebase Crashlytics or similar
- **Analytics** — Anonymous usage analytics for feature prioritization
- **Beta distribution** — Firebase App Distribution for beta testers
- **Automated screenshots** — Generate Play Store screenshots from UI tests

## Contributing Ideas

Have a feature idea? Open an issue on GitHub with:
- **Title**: Brief description of the feature
- **Use case**: Who benefits and how
- **Priority**: Nice-to-have vs. important for daily use
- **Complexity estimate**: Simple, moderate, or complex
- **Hardware requirements**: Any new hardware needed?

Ideas are evaluated based on:
1. Benefit to daily golf cart operation in The Villages
2. Technical feasibility with current hardware
3. Alignment with the three-component ecosystem (GCD/GCM/GCI)
4. Community interest and demand
