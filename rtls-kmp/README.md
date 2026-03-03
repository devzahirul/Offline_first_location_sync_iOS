# rtls-kmp

**Kotlin Multiplatform (KMP) shared module** for offline-first location sync on Android. Implements the same logical contract as the Swift [RTLSyncKit](https://github.com/devzahirul/Offline_first_location_sync_iOS): local persistence, batch upload, sync engine with configurable batching and retry, and an event stream for UI feedback.

Consumed by the **native Android app** (`rtls-android-app`) and by the **Flutter plugin** (`rtls_flutter`) on Android. All clients use the same backend API: `POST /v1/locations/batch`, `GET /v1/locations/latest`, and optional WebSocket `/v1/ws`.

---

## Architecture

The module is split into **commonMain** (platform-agnostic types and engine) and **androidMain** (concrete implementations). No `expect`/`actual` for store or API in the current design — Android is the only target, and implementations live in androidMain.

```
commonMain
├── Models.kt           LocationPoint, GeoCoordinate, LocationUploadBatch, LocationUploadResult, RejectedPoint
├── LocationStore.kt     Interface: insert, fetchPendingPoints, pendingCount, oldestPendingRecordedAt, markSent, markFailed
├── LocationSyncAPI.kt  Interface: upload batch → result
├── AuthTokenProvider.kt Fun interface for token supply
├── SyncEngine.kt       Flush loop, SyncEngineEvent (UploadSuccess / UploadFailed), SharedFlow<SyncEngineEvent>
└── LocationSyncClient.kt  Facade: startCollectingLocation(Flow<LocationPoint>), stopTracking, stats(), flushNow(), events: SharedFlow<LocationSyncClientEvent>

androidMain
├── SqliteLocationStore.kt   SQLite on file path; table create, CRUD, mark sent/failed
├── OkHttpLocationSyncAPI.kt POST to baseUrl/v1/locations/batch, Bearer token, kotlinx.serialization JSON
├── AndroidLocationProvider.kt FusedLocationProviderClient → Flow<LocationPoint>
└── RTLSKmp.kt           Factory: createLocationSyncClient(...), createLocationFlow(...)
```

- **Location flow:** Supplied by the host (app or Flutter plugin). The client does not start/stop the system location provider; it consumes a `Flow<LocationPoint>` and writes to the store, then triggers the sync engine.
- **Sync engine:** Runs a coroutine that periodically flushes pending points (batch size and interval configurable), marks success/failure in the store, and emits `SyncEngineEvent` to the client’s event stream.
- **Events:** `LocationSyncClientEvent` includes `Recorded(point)`, `SyncEvent(engineEvent)`, `Error(message)`, `TrackingStarted`, `TrackingStopped`. UI can subscribe to update pending count and last activity.

---

## Technology stack

| Concern | Technology |
|---------|------------|
| **Build** | Gradle Kotlin DSL; `kotlin("multiplatform")`, `androidTarget()`; Kotlin 1.9.x |
| **Serialization** | kotlinx.serialization (JSON) for API payloads |
| **Concurrency** | Kotlin Coroutines; Flow / SharedFlow |
| **Persistence** | Android SQLite (raw; no Room/SQLDelight) |
| **Network** | OkHttp 4.x |
| **Location** | FusedLocationProviderClient (Play Services) |

---

## Build

From the module directory (or from a parent that includes it):

```bash
./gradlew :rtls-kmp:assembleDebug
# or, if this project is the root:
./gradlew assembleDebug
```

Publish or include as a subproject so that the **Native Android app** and the **Flutter plugin** (Android) can depend on it.

---

## Including in a host project

The host (e.g. `rtls-android-app` or a Flutter app’s Android build) must include the KMP project in its settings and add a dependency.

**settings.gradle.kts** (host):

```kotlin
include(":app")
include(":rtls-kmp")   // or ":rtls_kmp" — name used in include()
project(":rtls-kmp").projectDir = file("../rtls-kmp")
```

**app/build.gradle.kts** (or Flutter plugin’s android/build.gradle):

```kotlin
dependencies {
    implementation(project(":rtls-kmp"))
}
```

Use `:rtls_kmp` if the Flutter plugin expects that name (e.g. `implementation project(':rtls_kmp')`).

---

## Usage (Android / Flutter Android)

1. **Create client and scope:**

   ```kotlin
   val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
   val client = RTLSKmp.createLocationSyncClient(
       context,
       baseUrl = "https://your-api.example.com",
       userId = "user-1",
       deviceId = "device-1",
       accessToken = "your-jwt",
       scope = scope
   )
   ```

2. **Create location flow and start collecting:**

   ```kotlin
   val locationFlow = RTLSKmp.createLocationFlow(context, userId, deviceId)
   client.startCollectingLocation(locationFlow)
   ```

3. **Collect events (e.g. in UI):**

   ```kotlin
   scope.launch {
       client.events.collect { event ->
           when (event) {
               is LocationSyncClientEvent.Recorded -> { /* point stored */ }
               is LocationSyncClientEvent.SyncEvent -> { /* upload success/failure */ }
               is LocationSyncClientEvent.Error -> { /* handle error */ }
               LocationSyncClientEvent.TrackingStarted -> { }
               LocationSyncClientEvent.TrackingStopped -> { }
           }
       }
   }
   ```

4. **Stop, stats, flush:**

   ```kotlin
   client.stopTracking()
   val stats = client.stats()  // LocationSyncClientStats(pendingCount, oldestPendingRecordedAtMs)
   client.flushNow()
   ```

**Database path:** The factory uses `context.filesDir/rtls_kmp/rtlsync.db`. The host can substitute a custom path by constructing `LocationSyncClient` manually with a `SqliteLocationStore(path)` and `OkHttpLocationSyncAPI` + `SyncEngine`.

---

## Backend contract

- **POST /v1/locations/batch** — JSON body: `{ schemaVersion, points: [ { id, userId, deviceId, recordedAt (ms), lat, lng, ... } ] }`. Response: `{ acceptedIds, rejected, serverTime }`. `Authorization: Bearer <token>`.
- **GET /v1/locations/latest?userId=** — Optional; not used by the sync engine in this module but available for dashboard or other clients.
- **WS /v1/ws** — Optional; not implemented in this module; dashboard uses it for live view.

---

## License

See repository [LICENSE](../LICENSE).
