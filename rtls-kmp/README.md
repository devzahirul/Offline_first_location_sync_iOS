# rtls-kmp — Android Location Sync Engine

Kotlin Multiplatform library (Android target) implementing offline-first location sync: GPS → SQLite → batched HTTP upload with exponential backoff, network gating, and configurable retention pruning. Single source of truth for all Android sync — consumed by native app, Flutter plugin, and React Native module.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          commonMain                                  │
│                                                                     │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────────┐  │
│  │  LocationStore   │  │ LocationSyncAPI   │  │  NetworkMonitor   │  │
│  │  (interface)     │  │ (interface)       │  │  (interface)      │  │
│  │  insert          │  │ upload(batch)     │  │  isOnline()       │  │
│  │  fetchPending    │  │   → Result        │  │  onlineFlow?      │  │
│  │  pendingCount    │  └──────────────────┘  └───────────────────┘  │
│  │  oldestPending   │                                               │
│  │  markSent        │  ┌──────────────────────────────────────────┐ │
│  │  markFailed      │  │           SyncEngine                     │ │
│  └─────────┬────────┘  │  Mutex-serialized flush loop             │ │
│            │           │  Timer job (flushIntervalSeconds)        │ │
│  ┌─────────▼────────┐  │  Network-online job (callbackFlow)      │ │
│  │SentPointsPrunable│  │  shouldFlush(force | count | age)       │ │
│  │  LocationStore   │  │  Exponential backoff + jitter           │ │
│  │  pruneSentPoints │  │  Retention pruning (hourly)             │ │
│  └──────────────────┘  └──────────────────────────────────────────┘ │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              LocationSyncClient  (facade)                     │  │
│  │  startCollectingLocation(Flow<LocationPoint>)                 │  │
│  │  stopTracking() · stats() · flushNow()                        │  │
│  │  events: SharedFlow<LocationSyncClientEvent>                  │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Policies:  BatchingPolicy · SyncRetryPolicy · RetentionPolicy     │
│  Models:    LocationPoint · GeoCoordinate · LocationUploadBatch     │
│             LocationUploadResult · RejectedPoint                    │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  │  implementations
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          androidMain                                 │
│                                                                     │
│  SqliteLocationStore          Raw SQLite, INSERT OR REPLACE,        │
│                               fetchPending WHERE sent_at IS NULL,   │
│                               pruneSentPoints DELETE WHERE          │
│                               sent_at IS NOT NULL AND               │
│                               recorded_at < cutoff                  │
│                                                                     │
│  OkHttpLocationSyncAPI        POST /v1/locations/batch,             │
│                               Bearer token,                         │
│                               kotlinx.serialization JSON            │
│                                                                     │
│  AndroidLocationProvider      FusedLocationProviderClient (API≥29)  │
│                               LocationManager fallback (API≤28)     │
│                               callbackFlow, getLastKnownLocation    │
│                               for immediate first fix               │
│                                                                     │
│  AndroidNetworkMonitor        ConnectivityManager +                 │
│                               registerDefaultNetworkCallback →      │
│                               callbackFlow onlineFlow               │
│                                                                     │
│  RTLSKmp                      Factory: createLocationSyncClient,    │
│                               createLocationFlow                    │
└─────────────────────────────────────────────────────────────────────┘
```

No `expect`/`actual` declarations — Android is the only target. `commonMain` defines interfaces and engine logic; `androidMain` provides concrete platform implementations. The boundary is clean: host code touches `RTLSKmp`, `LocationSyncClient`, events, stats, and `LocationRequestParams`. No direct dependency on SQLite or OkHttp types.

---

## Sync Engine Deep Dive

### Flush Algorithm

`SyncEngine.flushIfNeeded(force, maxBatches)` is the single entry point for all upload attempts. It is called by three triggers:

1. **Timer job** — fires every `BatchingPolicy.flushIntervalSeconds` (default 10s)
2. **Network-online job** — `AndroidNetworkMonitor.onlineFlow` emits `true` → conditional flush
3. **notifyNewData()** — called after every `LocationStore.insert()`, evaluates `shouldFlush` before proceeding

The flush is serialized by `Mutex.tryLock()` — non-blocking. If a flush is already in progress, the caller returns immediately. No queuing, no lock contention on the location collection coroutine.

```
flushIfNeeded(force, maxBatches)
│
├── running == false? → return
├── flushMutex.tryLock() fails? → return (another flush in progress)
├── !force && now < nextAllowedFlushAtMs? → return (backoff window)
├── networkMonitor.isOnline() == false? → return (network gate)
├── shouldFlush(force)?
│   ├── force == true → yes
│   ├── pendingCount >= maxBatchSize → yes
│   ├── oldestPending age >= maxBatchAgeSeconds → yes
│   └── otherwise → no, return
│
└── loop:
    ├── re-check network gate
    ├── fetchPendingPoints(maxBatchSize) → empty? → break
    ├── api.upload(batch)
    │   ├── success:
    │   │   ├── markSent(acceptedIds, sentAt)
    │   │   ├── markFailed(rejectedIds, reason)  ← server rejections only
    │   │   ├── failureCount = 0, nextAllowedFlushAtMs = null
    │   │   ├── emit UploadSuccess(accepted, rejected)
    │   │   └── maybePruneSentPoints()
    │   └── exception (transport error):
    │       ├── scheduleBackoffAfterFailure()
    │       ├── emit UploadFailed(message)
    │       └── break  ← points remain pending
    └── maxBatches reached? → break
```

**Key invariant:** transport errors never mark points as failed. Only explicit server rejections (returned in `LocationUploadResult.rejected`) trigger `markFailed()`. This means a network blip during upload results in a retry on the next flush cycle, not data loss.

### Exponential Backoff

`SyncRetryPolicy` computes delay per attempt:

```
delay = min(maxDelayMs, baseDelayMs · 2^(attempt-1)) ± (jitterFraction · base)
```

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `baseDelayMs` | 2,000 | Initial retry delay |
| `maxDelayMs` | 120,000 | Cap (2 minutes) |
| `jitterFraction` | 0.2 | ±20% randomization to decorrelate retries across devices |

`failureCount` is capped at 30 (`coerceAtMost`) to prevent floating-point overflow in the exponent. Resets to 0 on any successful upload.

The backoff is enforced via `nextAllowedFlushAtMs` — a timestamp. Timer ticks and network events still fire, but `flushIfNeeded` returns early if the current time is before the allowed window. A `force = true` call (manual `flushNow()`) bypasses the backoff check.

### Retention Pruning

`RetentionPolicy.sentPointsMaxAgeMs` controls how long successfully sent points remain on-device. Default: 7 days. `KeepForever` disables pruning entirely.

Pruning executes inside `maybePruneSentPoints()` after each successful upload batch:

- **Minimum interval:** 1 hour between prune operations (avoids thrashing)
- **Query:** `DELETE FROM location_points WHERE sent_at IS NOT NULL AND recorded_at < cutoff`
- **Guard:** store must implement `SentPointsPrunableLocationStore` (checked via `as?` cast). If not, pruning is a no-op.
- **Pending points are never deleted** — only rows with non-null `sent_at`

### Batching Policy

| Parameter | Default | Semantics |
|-----------|---------|-----------|
| `maxBatchSize` | 50 | Max points per HTTP request; also the `shouldFlush` count threshold |
| `flushIntervalSeconds` | 10 | Timer tick interval |
| `maxBatchAgeSeconds` | 60 | If the oldest pending point is older than this, flush regardless of count |

The three conditions form an OR: **count ≥ threshold** OR **age ≥ maxAge** OR **force**. This means a single point will upload within 60 seconds even if the batch never fills.

---

## API Surface

### RTLSKmp (factory)

```kotlin
RTLSKmp.createLocationSyncClient(
    context: Context,
    baseUrl: String,
    userId: String,
    deviceId: String,
    accessToken: String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    batchingPolicy: BatchingPolicy = BatchingPolicy(),
    retryPolicy: SyncRetryPolicy = SyncRetryPolicy.Default,
    retentionPolicy: RetentionPolicy = RetentionPolicy.Recommended,
    networkMonitor: NetworkMonitor? = AndroidNetworkMonitor(context)
): LocationSyncClient

RTLSKmp.createLocationFlow(
    context: Context,
    userId: String,
    deviceId: String,
    params: LocationRequestParams = LocationRequestParams()
): Flow<LocationPoint>
```

### LocationSyncClient

```kotlin
fun startCollectingLocation(locationFlow: Flow<LocationPoint>)
fun stopTracking()
suspend fun stats(): LocationSyncClientStats    // pendingCount, oldestPendingRecordedAtMs
suspend fun flushNow()
val events: SharedFlow<LocationSyncClientEvent>
```

### Event Stream

```kotlin
sealed class LocationSyncClientEvent {
    data class Recorded(val point: LocationPoint)
    data class SyncEvent(val event: SyncEngineEvent)  // UploadSuccess | UploadFailed
    data class Error(val message: String)
    object TrackingStarted
    object TrackingStopped
}
```

### LocationRequestParams

```kotlin
data class LocationRequestParams(
    val intervalMillis: Long = 10_000L,
    val minUpdateIntervalMillis: Long = 10_000L,
    val minUpdateDistanceMeters: Float = 10f
)
```

`AndroidLocationProvider` uses `FusedLocationProviderClient` on API 29+ with automatic fallback to `LocationManager` on API ≤ 28. On startup, `getLastKnownLocation` is requested for an immediate first fix before subscribing to continuous updates.

---

## Integration Guide

### 1. Include as Gradle subproject

**settings.gradle.kts** (host project):

```kotlin
include(":rtls-kmp")
project(":rtls-kmp").projectDir = file("../rtls-kmp")
```

**app/build.gradle.kts**:

```kotlin
dependencies {
    implementation(project(":rtls-kmp"))
}
```

### 2. Initialize and start tracking

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

val client = RTLSKmp.createLocationSyncClient(
    context = applicationContext,
    baseUrl = "https://api.example.com",
    userId = "user-1",
    deviceId = "device-1",
    accessToken = "jwt-token",
    scope = scope,
    batchingPolicy = BatchingPolicy(maxBatchSize = 50, flushIntervalSeconds = 10),
    retryPolicy = SyncRetryPolicy(baseDelayMs = 2000, maxDelayMs = 120_000),
    retentionPolicy = RetentionPolicy.Recommended
)

val locationFlow = RTLSKmp.createLocationFlow(
    context = applicationContext,
    userId = "user-1",
    deviceId = "device-1",
    params = LocationRequestParams(intervalMillis = 60_000L)
)

client.startCollectingLocation(locationFlow)
```

### 3. Observe events

```kotlin
scope.launch {
    client.events.collect { event ->
        when (event) {
            is LocationSyncClientEvent.Recorded -> { /* point persisted to SQLite */ }
            is LocationSyncClientEvent.SyncEvent -> {
                when (event.event) {
                    is SyncEngineEvent.UploadSuccess -> { /* batch accepted */ }
                    is SyncEngineEvent.UploadFailed -> { /* will retry with backoff */ }
                }
            }
            is LocationSyncClientEvent.Error -> { /* location or insert error */ }
            LocationSyncClientEvent.TrackingStarted -> {}
            LocationSyncClientEvent.TrackingStopped -> {}
        }
    }
}
```

### 4. Manual flush and stats

```kotlin
val stats = client.stats()   // pendingCount, oldestPendingRecordedAtMs
client.flushNow()            // bypasses backoff, immediate upload attempt
client.stopTracking()        // cancels location collection and sync engine
```

### Background execution

For reliable background location delivery, the **host application** must start a foreground service before calling `startCollectingLocation`. The Flutter plugin (`rtls_flutter`) handles this internally with `RtlsLocationForegroundService`. A native Android app using `rtls-kmp` directly is responsible for its own foreground service lifecycle.

**Database path:** `context.filesDir/rtls_kmp/rtlsync.db`. Override by constructing `LocationSyncClient` directly with a custom `SqliteLocationStore(path)`.

---

## Technology Stack

| Concern | Implementation |
|---------|----------------|
| **Build** | Gradle Kotlin DSL, `kotlin("multiplatform")`, `androidTarget()`, Kotlin 1.9.x |
| **Serialization** | `kotlinx.serialization` (JSON) for API request/response payloads |
| **Concurrency** | Kotlin Coroutines, `Flow`, `SharedFlow`, `Mutex` (non-blocking `tryLock` for flush serialization) |
| **Persistence** | Raw Android SQLite — `INSERT OR REPLACE` for idempotent writes, `sent_at IS NULL` index scan for pending fetch |
| **Network (HTTP)** | OkHttp 4.x, `POST /v1/locations/batch`, Bearer token auth |
| **Network (monitor)** | `ConnectivityManager.registerDefaultNetworkCallback` → `callbackFlow` for reactive online/offline transitions |
| **Location** | `FusedLocationProviderClient` (Play Services, API ≥ 29) with `LocationManager` fallback (API ≤ 28), `callbackFlow` emission |

---

## License

See repository [LICENSE](../LICENSE).
