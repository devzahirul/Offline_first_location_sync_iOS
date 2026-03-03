# Offline_first_location_sync_iOS

Offline-first real-time location sync for iOS. Capture and sync location updates in the background, with support for deferred upload when back online.

## Project structure

- **RealTimeLocationUpdateBackground** – iOS app (Swift) with background location updates and demo UI
- **Sources/RTLSCore** – Core types, store, API, and policies for location sync
- **Sources/RTLSData** – SQLite store and URLSession/WebSocket sync implementation
- **Sources/RTLSSync** – Sync engine and network monitoring
- **Sources/RTLSyncKit** – Sync client, app lifecycle hooks, and background sync
- **Sources/RTLSPlatformiOS** – iOS location provider
- **Tests/RTLSCoreTests** – Unit tests for core logic
- **rtls-dashboard** – Web dashboard (React + TypeScript) to view locations
- **backend-nodejs** – Node.js backend for receiving and serving location data

## Requirements

- Xcode (iOS app)
- Node.js (backend & dashboard)
- iOS device or simulator

## Getting started

1. Open `RealTimeLocationUpdateBackground/RealTimeLocationUpdateBackground.xcodeproj` in Xcode.
2. Run the iOS app on a device or simulator.
3. Start the backend and dashboard from their directories as needed.

## License

See repository for details.
