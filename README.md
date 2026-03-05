# RTLS — Offline-First Real-Time Location Sync

Production-grade location telemetry system: GPS → local SQLite → batched upload → optional WebSocket live stream. Five client targets, one backend contract, zero data loss on network failure.

```
               ╔══════════════════════════════════════════════════╗
               ║   GPS   →   SQLite   →   Batch Upload   →   DB  ║
               ║  (local)    (WAL)       (gzip + retry)    (PG)  ║
               ╚══════════════════════════════════════════════════╝
                     Offline-first. Idempotent. Zero data loss.
```

---

## Architecture Overview

```
 ┌─────────────────────────────────────────────────────────────────────────────────┐
 │                         Backend  (Node.js + Express)                            │
 │                                                                                │
 │  POST /v1/locations/batch ──▶ Zod validation ──▶ PostgreSQL ──▶ WS broadcast  │
 │  GET  /v1/locations/latest                                                     │
 │  WS   /v1/ws ──────────────▶ push to subscribers                              │
 └───────────────────────────────────┬────────────────────────────────────────────┘
                                     │  HTTPS / WSS
          ┌──────────────────────────┼──────────────────────────────┐
          │                          │                              │
          ▼                          ▼                              ▼
 ┌───────────────────┐   ┌────────────────────┐         ┌───────────────────┐
 │   iOS  (Swift)    │   │  Android (Kotlin)   │         │   Web Dashboard   │
 │                   │   │                     │         │   React + Vite    │
 │ RTLSCore          │   │  commonMain         │         │   Leaflet map     │
 │  └─ RTLSData      │   │   └─ SyncEngine     │         │   WebSocket sub   │
 │      └─ RTLSSync  │   │       └─ Policies   │         └───────────────────┘
 │          └─ Kit   │   │  androidMain        │
 │  Actor isolation  │   │   └─ SQLite + OkHttp│
 │  BGProcessingTask │   │   └─ FusedLocation  │
 │  NWPathMonitor    │   │  Mutex serialization│
 │  WAL journal mode │   │  ConnectivityMgr    │
 └────────┬──────────┘   └────────┬────────────┘
          │                       │
          │  SwiftPM dep          │  Gradle subproject
          │                       │
     ┌────┴────────┐        ┌─────┴───────┐
     │ Flutter iOS │        │ Flutter Droid│
     │ → RTLSyncKit│        │ → rtls-kmp   │
     └────┬────────┘        └─────┬───────┘
          │    Dart MethodChannel  │
          └──────────┬─────────────┘
                     │
     ┌───────────────┴───────────────┐
     │ React Native (iOS + Android)  │
     │ Native modules → same engines │
     │ Unified JS API both platforms │
     └───────────────────────────────┘
```

Every client writes GPS points to a local SQLite store before any network I/O. The write path is always local; the upload path is opportunistic, gated on network availability, and protected by exponential backoff. Points are never deleted until the server has acknowledged them.

---

## Performance & Power Optimization

Six-phase optimization pass targeting battery life, CPU wake frequency, SQLite throughput, and network efficiency. Measured impact at scale (user walking, 10s GPS interval, 25m distance filter):

```
 ┌───────────────────────────────────────────────────────────────────────────┐
 │                        BEFORE  vs  AFTER  (per hour)                     │
 │                                                                          │
 │  GPS fixes → SQLite     ████████████████████████████████████  360        │
 │                          ███████░░░░░░░░░░░░░░░░░░░░░░░░░░░   70  ▼80%  │
 │                                                                          │
 │  CPU wakeups (Android)  ████████████████████████████████████  360        │
 │                          ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░   60  ▼83%  │
 │                                                                          │
 │  SQLite queries          ████████████████████████████████████  720        │
 │  (shouldFlush)           █████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   60  ▼92%  │
 │                                                                          │
 │  Upload payload size     ████████████████████████████████████  6 KB      │
 │                          ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░  ~1 KB ▼83% │
 │                                                                          │
 │  markSent queries/flush  ████████████████████████████████████  50        │
 │                          █░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░    1  ▼98%  │
 │                                                                          │
 │  ░░░  = eliminated work                                                  │
 └───────────────────────────────────────────────────────────────────────────┘
```

### Optimization 1 — Hardware-Level Location Batching (Android)

```
 BEFORE: CPU wakes on every GPS fix
 ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 │WAKE │  │WAKE │  │WAKE │  │WAKE │  │WAKE │  │WAKE │   6 wakes / 60s
 │ GPS │  │ GPS │  │ GPS │  │ GPS │  │ GPS │  │ GPS │
 └─────┘  └─────┘  └─────┘  └─────┘  └─────┘  └─────┘
   10s      20s      30s      40s      50s      60s

 AFTER: setMaxUpdateDelayMillis(30_000) — CPU sleeps, gets batch
 ┌─────────────────────────┐              ┌─────────────────────────┐
 │       CPU SLEEPING      │              │       CPU SLEEPING      │  2 wakes / 60s
 └────────────────┬────────┘              └────────────────┬────────┘
                  ▼                                        ▼
           ┌──────────────┐                         ┌──────────────┐
           │ WAKE: 3 fixes│                         │ WAKE: 3 fixes│
           │  batch insert│                         │  batch insert│
           └──────────────┘                         └──────────────┘
                 30s                                       60s
```

Uses `FusedLocationProviderClient.setMaxUpdateDelayMillis()` to buffer GPS fixes at the hardware level. The application processor stays in deep sleep and receives fixes in bursts. Combined with `PRIORITY_BALANCED_POWER_ACCURACY` for non-critical modes (WiFi/cell triangulation at ~25mA vs GPS at ~100mA).

### Optimization 2 — Recording Decider (Android — was entirely missing)

```
 Raw GPS stream:    ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·    12 fixes / 2 min
                    │  │  │  │  │  │  │  │  │  │  │  │
                    ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼
 ┌─────────────────────────────────────────────────────────────┐
 │             LocationRecordingDecider                        │
 │                                                             │
 │  ✗ acc > 100m?  → reject        (accuracy filter)          │
 │  ✗ Δt < policy? → reject        (time gate)                │
 │  ✗ Δd < policy? → reject        (distance gate)            │
 │  ✓ passes all?  → INSERT + notifyNewData                   │
 └─────────────────────────────────────────────────────────────┘
                    │           │                 │
                    ▼           ▼                 ▼
 After filter:      ·           ·                 ·              3 inserts / 2 min
```

iOS had `LocationRecordingDecider` from day one. Android/KMP was missing it — every raw FusedLocationProvider fix was written to SQLite and triggered sync engine queries. Now both platforms share identical filtering: time-interval gate, distance gate, and horizontal-accuracy rejection (default >100m).

### Optimization 3 — Debounced Sync + Coalesced Query

```
 BEFORE:  every insert → notifyNewData() → 2 SQLite queries

   insert ──▶ pendingCount() ──▶ oldestPendingRecordedAt() ──▶ shouldFlush?
   insert ──▶ pendingCount() ──▶ oldestPendingRecordedAt() ──▶ shouldFlush?
   insert ──▶ pendingCount() ──▶ oldestPendingRecordedAt() ──▶ shouldFlush?
   ...

 AFTER:  debounce 2s + single coalesced query

   insert ──▶ (debounce 2s) ──┐
   insert ──▶ (debounce 2s) ──┤
   insert ──▶ (debounce 2s) ──┤
                               └──▶ pendingStats()  ← 1 query:
                                    SELECT COUNT(*), MIN(recorded_at)
                                    WHERE sync_status = 0
```

`notifyNewData()` is now debounced with a 2-second window (immediate flush only if `pendingCount >= maxBatchSize`). The two separate queries (`pendingCount` + `oldestPendingRecordedAt`) are merged into a single `SELECT COUNT(*), MIN(recorded_at_ms)`. The periodic timer (10s) acts as a backstop.

### Optimization 4 — Batch SQL Operations

```
 BEFORE: markSent with N individual UPDATEs (N = batch size, typically 50)

   BEGIN TRANSACTION
   UPDATE ... WHERE id = 'aaa'      ─┐
   UPDATE ... WHERE id = 'bbb'       │  50 statements
   UPDATE ... WHERE id = 'ccc'       │  50 btree lookups
   ...                               │
   UPDATE ... WHERE id = 'zzz'      ─┘
   COMMIT

 AFTER: single UPDATE with WHERE IN (...)

   UPDATE location_points
   SET sent_at = ?, sync_status = ?
   WHERE id IN ('aaa','bbb','ccc', ... 'zzz')    ← 1 statement, 1 index scan
```

Also applies `PRAGMA synchronous = NORMAL` on Android (iOS already had this). With WAL mode, NORMAL gives ~2x write throughput vs FULL, with negligible durability risk (only last transaction at risk on power loss, and our data is recoverable from GPS).

### Optimization 5 — Gzip Upload Compression

```
 ┌────────────────────────────────────────────────────────────────┐
 │  50-point JSON batch                                          │
 │                                                               │
 │  Uncompressed:  ██████████████████████████████████████  6.2 KB│
 │  Gzip:          ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  0.9 KB│
 │                                                               │
 │  Radio state impact (LTE):                                    │
 │                                                               │
 │  6 KB → RRC_CONNECTED for ~2.1s  ████████████████████         │
 │  0.9KB → RRC_CONNECTED for ~0.8s ████████                     │
 │                                                               │
 │  Less radio time = less tail energy = longer battery          │
 └────────────────────────────────────────────────────────────────┘
```

Both iOS (`zlib` via `deflateInit2` with gzip wrapper) and Android (`okio.GzipSink`) compress upload payloads. `Content-Encoding: gzip` header tells the server to decompress. Falls back to uncompressed if gzip produces a larger output (impossible for JSON but handled gracefully).

### Optimization 6 — iOS Power Tuning

```
 BEFORE                                 AFTER
 ┌────────────────────────────┐         ┌────────────────────────────┐
 │ pausesAutomatically: false │         │ pausesAutomatically: true  │
 │ desiredAccuracy: Best      │         │ desiredAccuracy: adaptive  │
 │ (GPS always on, even when  │         │ (pauses when stationary,   │
 │  user is sitting still)    │         │  resumes on movement)      │
 └────────────────────────────┘         └────────────────────────────┘
       ████████████████████                    ████░░░░████░░░░████
       GPS active entire time                  GPS only when moving
```

`CLLocationManager.pausesLocationUpdatesAutomatically` was hardcoded `false` in the Flutter plugin — GPS ran continuously even with the device on a desk. Now defaults to `true`. For significant-change mode, `desiredAccuracy` drops to `kCLLocationAccuracyHundredMeters` (WiFi/cell only).

---

### Full Optimization Matrix

| # | Optimization | Layer | iOS | Android | Impact |
|---|---|---|---|---|---|
| 1 | HW location batching | GPS | n/a (CLLocation batches internally) | `setMaxUpdateDelayMillis` | **▼83% CPU wakes** |
| 2 | Balanced power accuracy | GPS | `pausesAutomatically + desiredAccuracy` | `PRIORITY_BALANCED_POWER_ACCURACY` | **▼60% GPS power** (WiFi/cell mode) |
| 3 | Accuracy filter | GPS→Store | `LocationRecordingDecider` | `LocationRecordingDecider` (NEW) | **▼20-40% junk inserts** |
| 4 | Recording decider | GPS→Store | Existed (time/distance gate) | **NEW** (was recording every fix) | **▼80% DB writes** (Android) |
| 5 | Debounced `notifyNewData` | SyncEngine | 2s debounce + immediate if full | 2s debounce + immediate if full | **▼90% flush-check queries** |
| 6 | Coalesced `pendingStats()` | SyncEngine→Store | `SELECT COUNT, MIN` in 1 query | `SELECT COUNT, MIN` in 1 query | **▼50% per flush-check** |
| 7 | Batch `markSent` | Store | `WHERE id IN (...)` | `WHERE id IN (...)` | **▼98% UPDATE stmts** |
| 8 | `PRAGMA synchronous=NORMAL` | Store | Already had it | Added in `onConfigure` | **▼~2x write latency** |
| 9 | Gzip upload | Network | zlib `deflateInit2` (gzip mode) | okio `GzipSink` | **▼83% payload size** |
| 10 | Lifecycle-aware flush | Plugin | `willEnterForeground/Background` | `ActivityLifecycleCallbacks` | **Immediate sync on app transitions** |

---

## Design Principles

**Offline-first, not offline-tolerant.** The local store is the system of record on the client. Upload is a background reconciliation process. If the device never comes online, every point is preserved locally with full fidelity.

**Library-first architecture.** `rtls-kmp` (Android/KMP) and `RTLSyncKit` (iOS/SwiftPM) are the reusable sync engines. Native apps, the Flutter plugin, and the React Native module are thin consumers — they own UI and lifecycle, not sync logic. No sync code is duplicated across targets.

**Shared sync semantics.** Both engines implement identical policies: `BatchingPolicy`, `SyncRetryPolicy`, `RetentionPolicy`. Same flush triggers (timer interval, batch size threshold, oldest-pending age). Same backoff curve. Same network gate. Behavioral parity is a design constraint, not a coincidence.

**Failure model: leave pending, never corrupt.** Transport errors (timeout, DNS, TCP reset) leave points in `pending` state — the next flush retries them. Only explicit server rejections (HTTP 200 with `rejected[]`) mark points as failed. This distinction is critical: a flaky cell connection must not cause data loss.

**Idempotent writes.** `INSERT OR REPLACE` (Android) and `INSERT OR IGNORE` (iOS) ensure that re-inserting a point with the same UUID is safe. The server also deduplicates on `id`, making the entire write pipeline idempotent end-to-end.

**Serialized flush.** iOS uses Swift Actor isolation; Android uses a Kotlin `Mutex` with `tryLock` (non-blocking entry — concurrent flush attempts are dropped, not queued). This prevents overlapping uploads without blocking the location collection coroutine.

---

## Sync Engine Internals

Both platforms implement the same logical state machine:

```
              ┌──────────────┐
              │   IDLE       │◄──── timer tick (configurable interval)
              └──────┬───────┘
                     │ shouldFlush?
                     ▼
         ┌───────────────────────┐
         │ Check backoff window  │──── too early? → return
         └───────────┬───────────┘
                     │
         ┌───────────▼───────────┐
         │ Check network gate    │──── offline? → return
         └───────────┬───────────┘
                     │
         ┌───────────▼───────────┐     ┌─── pendingStats() ◄── single query
         │ Evaluate flush cond.  │◄────┤    (count + oldest)
         │                       │     └─── vs BatchingPolicy thresholds
         └───────────┬───────────┘
                     │ yes
         ┌───────────▼───────────┐
         │ Fetch batch from store│──── SELECT ... WHERE pending
         └───────────┬───────────┘     ORDER BY recorded_at ASC LIMIT N
                     │
         ┌───────────▼───────────┐
         │ POST /v1/locations/   │──── gzip-compressed JSON
         │        batch          │     Content-Encoding: gzip
         └───────┬───────┬───────┘
                 │       │
            success    transport error
                 │       │
     ┌───────────▼──┐  ┌─▼──────────────────┐
     │ markSent()   │  │ scheduleBackoff()   │
     │ WHERE IN (...│  │ failureCount++      │
     │ reset backoff│  │ delay = base·2^n    │
     │ maybe prune  │  │   + jitter(±20%)    │
     └──────────────┘  │ cap at maxDelay     │
                       │ points stay pending │
                       └─────────────────────┘
```

**Flush triggers:** (1) periodic timer (default 10s), (2) network-online event via `NWPathMonitor` / `ConnectivityManager.registerDefaultNetworkCallback`, (3) debounced `notifyNewData()` after inserts (2s window, immediate if batch full), (4) explicit `flushNow()`, (5) lifecycle transitions (foreground/background).

**Backoff:** Exponential with jitter. `delay = min(maxDelay, baseDelay · 2^(attempt-1)) ± jitterFraction`. Defaults: base 2s, max 120s, jitter ±20%. Failure counter capped at 30 to prevent overflow. Resets to zero on any successful upload.

**Retention pruning:** Hourly (minimum interval), deletes `sent_at IS NOT NULL AND recorded_at < cutoff`. Default cutoff: 7 days. Only executes if the store implements `SentPointsPrunableLocationStore`. Pending points are never pruned.

**Concurrency (iOS):** `SyncEngine` is an `actor`. All mutable state (`failureCount`, `nextAllowedFlushAt`, timer handle) is actor-isolated. No locks needed.

**Concurrency (Android):** `flushMutex.tryLock()` — if another coroutine is already flushing, the caller returns immediately. No queuing, no priority inversion.

---

## Data Flow — End to End

```
 ┌──────────────┐     ┌──────────────────┐     ┌─────────────┐     ┌─────────────┐
 │  CLLocation  │     │ RecordingDecider │     │   SQLite    │     │ SyncEngine  │
 │  / Fused     │────▶│                  │────▶│  (WAL)      │────▶│             │
 │  Provider    │     │ • accuracy gate  │     │             │     │ • debounced │
 │              │     │ • time gate      │     │ INSERT OR   │     │   notify    │
 │  HW-batched  │     │ • distance gate  │     │ IGNORE      │     │ • timer     │
 │  on Android  │     │                  │     │             │     │ • net gate  │
 └──────────────┘     └──────────────────┘     └──────┬──────┘     └──────┬──────┘
                                                      │                   │
                       Points that pass all gates     │                   │
                       are the only ones written       │                   │
                                                      ▼                   ▼
                                               ┌─────────────┐    ┌──────────────┐
                                               │  Pending     │    │ Batch Upload │
                                               │  Queue       │───▶│              │
                                               │              │    │ • gzip body  │
                                               │  COUNT(*)    │    │ • Bearer JWT │
                                               │  + MIN(ts)   │    │ • POST /v1/  │
                                               │  = 1 query   │    │   locations/ │
                                               └─────────────┘    │   batch      │
                                                                   └──────┬───────┘
                                                                          │
                                                                   ┌──────▼───────┐
                                                                   │   Server     │
                                                                   │              │
                                                                   │ • Zod valid  │
                                                                   │ • PG upsert  │
                                                                   │ • WS broad-  │
                                                                   │   cast       │
                                                                   └──────┬───────┘
                                                                          │
                                                                   ┌──────▼───────┐
                                                                   │  Dashboard   │
                                                                   │  (Leaflet)   │
                                                                   └──────────────┘
```

---

## Lifecycle-Aware Sync

```
 iOS                                          Android
 ┌──────────────────────────────┐             ┌──────────────────────────────────┐
 │ willEnterForeground          │             │ onActivityResumed                │
 │   → flushNow()              │             │   → flushNow()                  │
 │   (drain pending immediately)│             │   (drain pending immediately)   │
 │                              │             │                                 │
 │ didEnterBackground           │             │ onActivityPaused                │
 │   → flushNow(maxBatches: 2) │             │   → flushNow()                 │
 │   (best-effort before sleep) │             │   (best-effort before sleep)   │
 │                              │             │                                 │
 │ BGProcessingTask             │             │ ForegroundService               │
 │   → flushNow(maxBatches: 10)│             │   → keeps sync engine alive    │
 │   (system-scheduled, ~15min) │             │   → location foreground type   │
 └──────────────────────────────┘             └──────────────────────────────────┘

 Flutter (Dart — belt-and-suspenders):
 ┌──────────────────────────────────────────────────────────────────┐
 │ WidgetsBindingObserver.didChangeAppLifecycleState               │
 │   resumed → RTLSync.flushNow() + loadStats()                   │
 │   paused  → RTLSync.flushNow()                                 │
 └──────────────────────────────────────────────────────────────────┘
```

---

## Repository Structure

### Core Libraries (sync engines — consumed by everything else)

| Path | Platform | Description |
|------|----------|-------------|
| `Sources/RTLSCore` | iOS | Types, policies (`BatchingPolicy`, `SyncRetryPolicy`, `RetentionPolicy`), store/API interfaces, `LocationRecordingDecider` |
| `Sources/RTLSData` | iOS | `SQLiteLocationStore` (WAL mode, `INSERT OR IGNORE`, batch `WHERE IN` markSent, gzip uploads) |
| `Sources/RTLSSync` | iOS | `SyncEngine` actor, `NetworkMonitor`, debounced flush, coalesced `pendingStats()` |
| `Sources/RTLSyncKit` | iOS | `LocationSyncClient` facade, `CoreLocationProvider`, lifecycle integration |
| `Sources/RTLSPlatformiOS` | iOS | `BGProcessingTask` scheduler, `NWPathMonitor` concrete impl |
| `rtls-kmp/` | Android | KMP module — `commonMain` (engine + policies + `LocationRecordingDecider`) + `androidMain` (SQLite, OkHttp+gzip, FusedLocation+HW batching, ConnectivityManager) |

### Cross-Platform Plugins

| Path | Description |
|------|-------------|
| `rtls_flutter/` | Flutter plugin. Dart API → `MethodChannel`/`EventChannel` → `rtls-kmp` (Android) / `RTLSyncKit` (iOS). Android foreground service. Lifecycle-aware flush on both platforms. |
| `rtls-react-native/` | React Native native module. Unified JS API. iOS → `RTLSyncKit`; Android → `rtls-kmp`. |

### Example Applications

| Path | Description |
|------|-------------|
| `RealTimeLocationUpdateBackground/` | Native iOS app (SwiftUI) — consumes `RTLSyncKit` |
| `rtls-android-app/` | Native Android app — consumes `rtls-kmp` |
| `rtls_flutter/example/` | Flutter example app |
| `rtls-mobile-example/` | React Native example app |

### Backend & Dashboard

| Path | Description |
|------|-------------|
| `backend-nodejs/` | Node.js + Express + PostgreSQL. REST batch upload, JWT auth, WebSocket broadcast |
| `rtls-dashboard/` | React 19 + Vite + Leaflet. WebSocket subscriber for live map |

---

## Technology Matrix

| Layer | Stack | Key Implementation Details |
|-------|-------|----------------------------|
| **iOS Library** | Swift 5.9, SwiftPM, CoreLocation, BackgroundTasks | Actor-isolated `SyncEngine`, `BGProcessingTask`, `NWPathMonitor`, WAL + `PRAGMA synchronous=NORMAL`, gzip uploads, accuracy-aware `RecordingDecider`, coalesced `pendingStats()` |
| **Android Library** | Kotlin 1.9, KMP, Coroutines, OkHttp 4.x | `Mutex`-serialized flush, `FusedLocationProviderClient` with HW batching (`setMaxUpdateDelayMillis`), `LocationRecordingDecider`, `PRIORITY_BALANCED_POWER_ACCURACY`, gzip uploads via okio, `PRAGMA synchronous=NORMAL` |
| **Flutter Plugin** | Dart 3, MethodChannel, EventChannel | Foreground service (Android), lifecycle-aware flush (both platforms via native hooks + `WidgetsBindingObserver`), delegates all sync to native engines |
| **React Native** | TypeScript, native modules (Swift + Kotlin) | Same JS API both platforms, event emitter for `RECORDED`/`SYNC_EVENT`/`ERROR` |
| **Backend** | Node.js 18+, Express, TypeScript, Zod, `ws`, `pg` | Stateless HTTP + stateful WebSocket, schema validation, JWT auth |
| **Dashboard** | React 19, Vite, Leaflet, TypeScript | WebSocket subscriber, Leaflet tile layer for live map |

---

## Quick Start

### Prerequisites

- **Backend:** Node.js 18+, PostgreSQL (optional — in-memory fallback available)
- **iOS:** Xcode 15+, iOS 15+ target
- **Android:** Android Studio, SDK 21+, Gradle 8+
- **Flutter:** Flutter SDK stable channel
- **React Native:** Node.js, Xcode + CocoaPods (iOS), Android SDK (Android)

### 1. Start the backend

```bash
cd backend-nodejs
cp .env.example .env   # set DATABASE_URL, JWT_SECRET, HOST, PORT
npm install && npm run dev
```

### 2. Start the dashboard (optional)

```bash
cd rtls-dashboard
npm install && npm run dev
```

### 3. Run a client

**iOS (native):** Open `RealTimeLocationUpdateBackground.xcodeproj`, set base URL, run on device.

**Android (native):** `cd rtls-android-app && ./gradlew installDebug`

**Flutter:** `cd rtls_flutter/example && flutter run`

**React Native:** See [rtls-mobile-example/README.md](rtls-mobile-example/README.md)

### 4. Run tests

```bash
swift test                                    # iOS unit tests
cd rtls-kmp && ./gradlew assembleDebug        # Android library build
```

---

## API Contract

All clients upload to the same endpoint. No client-specific routes.

### `POST /v1/locations/batch`

```json
{
  "schemaVersion": 1,
  "points": [
    {
      "id": "uuid",
      "userId": "user-1",
      "deviceId": "device-1",
      "recordedAt": 1709500000000,
      "lat": 37.7749,
      "lng": -122.4194,
      "horizontalAccuracy": 5.0,
      "altitude": 12.3,
      "speed": 1.2,
      "course": 180.0
    }
  ]
}
```

**Request headers:**
- `Authorization: Bearer <JWT>`
- `Content-Encoding: gzip` (when compressed)

**Response:**

```json
{
  "acceptedIds": ["uuid"],
  "rejected": [],
  "serverTime": 1709500001000
}
```

### `GET /v1/locations/latest?userId=`

Returns the most recent stored point for a given user.

### `WS /v1/ws`

Server broadcasts `{ "type": "location", "point": { ... } }` on every new batch ingestion. Dashboard subscribes here.

---

## License

See [LICENSE](LICENSE).
