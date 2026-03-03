# RTLS Native Android App

Standalone **Android application** for offline-first location sync. Uses the shared [rtls-kmp](https://github.com/devzahirul/Offline_first_location_sync_iOS/tree/main/rtls-kmp) module for persistence, batching, and upload; provides a minimal UI to configure the backend, start/stop tracking, flush pending data, and display sync status.

---

## Overview

- **Role:** Reference Android client for the Real-Time Location Sync backend. Demonstrates integration of the KMP sync engine with FusedLocationProvider, permission handling, and a simple settings + status screen.
- **Backend:** Same API as iOS and Flutter clients: `POST /v1/locations/batch` (Bearer token), optional `GET /v1/locations/latest`, optional WebSocket `/v1/ws`.
- **Architecture:** Single-Activity app; View-based UI (XML layouts). Config (base URL, userId, deviceId, token) is applied once; tracking and flush are driven by the KMP `LocationSyncClient` and event flow.

---

## Features

- **Configuration:** Base URL, User ID, Device ID, and access token; persisted for the session and used to create the KMP client.
- **Tracking:** Start / Stop tracking; location is collected via KMP’s `AndroidLocationProvider` and written to SQLite; sync engine runs in the background.
- **Flush:** “Flush now” triggers an immediate upload of pending points (within engine policy).
- **Status:** Pending count and last sync/recorded event displayed on the main screen; updates from the client’s event stream.
- **Permissions:** Requests `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, and `ACCESS_BACKGROUND_LOCATION` (Android 10+); runtime permission flow before starting tracking.

---

## Technology stack

| Concern | Technology |
|---------|------------|
| **Language** | Kotlin 1.9 |
| **Build** | Gradle Kotlin DSL; AGP 8.2 |
| **Min SDK** | 21 |
| **UI** | XML layouts, ViewBinding; single Activity |
| **Sync** | rtls-kmp (subproject): SQLite, OkHttp, FusedLocationProvider, Coroutines |
| **Concurrency** | Coroutines; Main dispatcher for UI updates |

---

## Build and run

### Prerequisites

- Android SDK (API 21+); Android Studio or CLI.
- **rtls-kmp** must be available as a sibling project or at the path referenced in `settings.gradle.kts`.

### Commands

```bash
cd rtls-android-app
./gradlew assembleDebug
./gradlew installDebug   # install on connected device/emulator
```

Or open the project in Android Studio and run the `app` configuration.

### First run

1. **Configure:** Enter Base URL (e.g. `http://10.0.2.2:3000` for emulator, or `http://<host-ip>:3000` for a physical device on the same network), User ID, Device ID, and access token. Tap **Configure**.
2. **Permissions:** When prompted, grant location (and background location if required).
3. **Start:** Tap **Start** to begin collecting and syncing location. Use **Flush now** to force an immediate upload. **Stop** ends collection and stops the sync engine from processing new points.
4. **Status:** Pending count and last event update as events are received from the KMP client.

---

## Project structure

| Path | Purpose |
|------|----------|
| `settings.gradle.kts` | Includes `:app` and `:rtls-kmp` (projectDir → `../rtls-kmp`) |
| `app/build.gradle.kts` | Application module; `implementation(project(":rtls-kmp"))` |
| `app/src/main/AndroidManifest.xml` | Location permissions; single Activity |
| `app/src/main/java/.../MainActivity.kt` | Config, start/stop/flush, event collection, permission request, UI refresh |
| `app/src/main/res/layout/activity_main.xml` | Inputs for URL, userId, deviceId, token; buttons; status text |

---

## Dependency on rtls-kmp

The app expects the KMP module at `../rtls-kmp` relative to `rtls-android-app`. If you move the repo layout, update `settings.gradle.kts`:

```kotlin
project(":rtls-kmp").projectDir = file("<path-to-rtls-kmp>")
```

---

## Backend and network

- Ensure the backend is running (see [backend-nodejs/README.md](../backend-nodejs/README.md)).
- Emulator: use `http://10.0.2.2:3000` to reach the host’s loopback.
- Physical device: use the host machine’s LAN IP and ensure the backend listens on `0.0.0.0` (e.g. `HOST=0.0.0.0` in `.env`).

---

## License

See repository [LICENSE](../LICENSE).
