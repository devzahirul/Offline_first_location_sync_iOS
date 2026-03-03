# RTLS Native Android App

**Reference Android client** for the Real-Time Location Sync backend. Standalone Kotlin app that consumes the rtls-kmp library—single Activity, ViewBinding, FusedLocationProvider/LocationManager fallback, and a minimal config + status UI.

---

## Architecture

Single-Activity app with a Gradle subproject dependency on `rtls-kmp`. The KMP library owns persistence (SQLite), batching, retry, retention, and network gating; the app wires `LocationSyncClient` and `SyncEngine` to Android location APIs and a simple UI.

| Layer | Responsibility |
|-------|----------------|
| **MainActivity** | Config (baseUrl, userId, deviceId, token), Start/Stop, Flush Now, permission flow, event collection via `SharedFlow` |
| **LocationSyncClient** | Consumes `Flow<LocationPoint>`, inserts into store, notifies `SyncEngine`, exposes `events: SharedFlow<LocationSyncClientEvent>` |
| **SyncEngine** | Mutex-serialized flush loop; `BatchingPolicy` (batch size, interval, max age), `SyncRetryPolicy` (exponential backoff), `RetentionPolicy` (sent-point pruning), `AndroidNetworkMonitor` (online gate) |
| **AndroidLocationProvider** | `FusedLocationProviderClient` on API 28+, `LocationManager` fallback on API ≤28 |

---

## Features

- **Config:** baseUrl, userId, deviceId, token—applied once via Configure; used to instantiate `RTLSKmp.createLocationSyncClient()`.
- **Tracking:** Start/Stop; location collected via `RTLSKmp.createLocationFlow()` (Fused or LocationManager), fed into `LocationSyncClient.startCollectingLocation()`.
- **Flush:** "Flush now" calls `client.flushNow()` for immediate upload within engine policy.
- **Status:** Pending count and last event from `client.stats()` and `client.events`.
- **Permissions:** Runtime request for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (Android 10+); requested before tracking.

**Event stream (`LocationSyncClient.events`):** `Recorded`, `SyncEvent` (wraps `UploadSuccess(accepted, rejected)` / `UploadFailed(message)`), `Error`, `TrackingStarted`, `TrackingStopped`.

---

## Build & Run

**Prerequisites:** Android SDK (API 21+). `rtls-kmp` must be a sibling project (`../rtls-kmp`) or path-adjusted in `settings.gradle.kts`.

```bash
cd rtls-android-app
./gradlew assembleDebug
./gradlew installDebug
```

Or open in Android Studio and run the `app` configuration.

**First run:** Configure baseUrl (e.g. `http://10.0.2.2:3000` for emulator), userId, deviceId, token → grant location permissions → Start tracking → Flush Now for immediate upload. Pending count and last event update from the event stream.

---

## Integration Notes

- **Subproject:** `implementation(project(":rtls-kmp"))`; `project(":rtls-kmp").projectDir = file("../rtls-kmp")`. Adjust path if repo layout changes.
- **Backend:** Same API as iOS/Flutter: `POST /v1/locations/batch` (Bearer token). Emulator: `http://10.0.2.2:3000`; physical device: host LAN IP, backend on `0.0.0.0`.
- **Min SDK 21, target 34.**

---

## License

See repository [LICENSE](../LICENSE).
