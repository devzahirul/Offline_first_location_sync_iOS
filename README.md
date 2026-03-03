# RTLS — Offline-First Real-Time Location Sync

Production-grade location telemetry system: GPS → local SQLite → batched upload → optional WebSocket live stream. Five client targets, one backend contract, zero data loss on network failure.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                              Backend  (Node.js + Express)                             │
│                                                                                      │
│   POST /v1/locations/batch ──▶ Zod validation ──▶ PostgreSQL                         │
│   GET  /v1/locations/latest                                                          │
│   WS   /v1/ws ──────────────▶ broadcast to subscribers                               │
└──────────────────────────────────────┬───────────────────────────────────────────────┘
                                       │  HTTPS / WSS
          ┌────────────────────────────┼────────────────────────────────┐
          │                            │                                │
          ▼                            ▼                                ▼
┌───────────────────┐      ┌───────────────────┐            ┌───────────────────┐
│   iOS (Swift)     │      │ Android (Kotlin)   │            │   Web Dashboard   │
│                   │      │                    │            │   React + Vite    │
│ RTLSCore          │      │ commonMain         │            │   Leaflet map     │
│  └─ RTLSData      │      │  └─ SyncEngine     │            │   WebSocket sub   │
│      └─ RTLSSync  │      │      └─ Policies   │            └───────────────────┘
│          └─ Kit   │      │ androidMain        │
│  Actor isolation  │      │  └─ SQLite + OkHttp│
│  BGProcessingTask │      │  └─ FusedLocation  │
│  NWPathMonitor    │      │  Mutex serialization│
│  WAL journal mode │      │  ConnectivityMgr   │
└────────┬──────────┘      └────────┬───────────┘
         │                          │
         │  SwiftPM dependency      │  Gradle subproject
         │                          │
    ┌────┴────────┐           ┌─────┴───────┐
    │ Flutter iOS │           │ Flutter Droid│
    │ → RTLSyncKit│           │ → rtls-kmp   │
    └────┬────────┘           └─────┬───────┘
         │    Dart MethodChannel    │
         └──────────┬───────────────┘
                    │
    ┌───────────────┴───────────────┐
    │ React Native (iOS + Android)  │
    │ Native modules → same engines │
    │ Unified JS API both platforms │
    └───────────────────────────────┘
```

Every client writes GPS points to a local SQLite store before attempting any network I/O. The write path is always local; the upload path is opportunistic, gated on network availability, and protected by exponential backoff. Points are never deleted until the server has acknowledged them.

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
        ┌───────────▼───────────┐
        │ Evaluate flush cond.  │──── pendingCount >= maxBatchSize?
        │                       │──── oldestPending age >= maxBatchAge?
        │                       │──── force = true? (manual flush)
        └───────────┬───────────┘
                    │ yes
        ┌───────────▼───────────┐
        │ Fetch batch from store│──── SELECT ... WHERE sent_at IS NULL
        └───────────┬───────────┘     ORDER BY recorded_at ASC LIMIT N
                    │
        ┌───────────▼───────────┐
        │ POST /v1/locations/   │
        │        batch          │
        └───────┬───────┬───────┘
                │       │
           success    transport error
                │       │
    ┌───────────▼──┐  ┌─▼──────────────────┐
    │ markSent()   │  │ scheduleBackoff()   │
    │ reset backoff│  │ failureCount++      │
    │ maybe prune  │  │ delay = base·2^n    │
    └──────────────┘  │   + jitter(±20%)    │
                      │ cap at maxDelay     │
                      │ points stay pending │
                      └─────────────────────┘
```

**Flush triggers:** (1) periodic timer (default 10s), (2) network-online event via `NWPathMonitor` / `ConnectivityManager.registerDefaultNetworkCallback` → `callbackFlow`, (3) `notifyNewData()` after each insert (conditional — only flushes if threshold met), (4) explicit `flushNow()`.

**Backoff:** Exponential with jitter. `delay = min(maxDelay, baseDelay · 2^(attempt-1)) ± jitterFraction`. Defaults: base 2s, max 120s, jitter ±20%. Failure counter capped at 30 to prevent overflow. Resets to zero on any successful upload.

**Retention pruning:** Hourly (minimum interval), deletes `sent_at IS NOT NULL AND recorded_at < cutoff`. Default cutoff: 7 days. Only executes if the store implements `SentPointsPrunableLocationStore`. Pending points are never pruned.

**Concurrency (iOS):** `SyncEngine` is an `actor`. All mutable state (`failureCount`, `nextAllowedFlushAt`, timer handle) is actor-isolated. No locks needed.

**Concurrency (Android):** `flushMutex.tryLock()` — if another coroutine is already flushing, the caller returns immediately. No queuing, no priority inversion.

---

## Repository Structure

### Core Libraries (sync engines — consumed by everything else)

| Path | Platform | Description |
|------|----------|-------------|
| `Sources/RTLSCore` | iOS | Types, policies (`BatchingPolicy`, `SyncRetryPolicy`, `RetentionPolicy`), store/API interfaces |
| `Sources/RTLSData` | iOS | `SQLiteLocationStore` (WAL mode, `INSERT OR IGNORE`, prepared statements) |
| `Sources/RTLSSync` | iOS | `SyncEngine` actor, `NetworkMonitor` protocol, flush logic |
| `Sources/RTLSyncKit` | iOS | `LocationSyncClient` facade, `CoreLocationProvider`, lifecycle integration |
| `Sources/RTLSPlatformiOS` | iOS | `BGProcessingTask` scheduler, `NWPathMonitor` concrete impl |
| `rtls-kmp/` | Android | KMP module — `commonMain` (engine + policies) + `androidMain` (SQLite, OkHttp, FusedLocation, ConnectivityManager). [README](rtls-kmp/README.md) |

### Cross-Platform Plugins

| Path | Description |
|------|-------------|
| `rtls_flutter/` | Flutter plugin. Dart API → `MethodChannel`/`EventChannel` → `rtls-kmp` (Android) / `RTLSyncKit` (iOS). Android foreground service included. [README](rtls_flutter/README.md) |
| `rtls-react-native/` | React Native native module. Unified JS API. iOS → `RTLSyncKit`; Android → `rtls-kmp`. [README](rtls-react-native/README.md) |

### Example Applications

| Path | Description |
|------|-------------|
| `RealTimeLocationUpdateBackground/` | Native iOS app (SwiftUI) — consumes `RTLSyncKit` |
| `rtls-android-app/` | Native Android app — consumes `rtls-kmp`. [README](rtls-android-app/README.md) |
| `rtls_flutter/example/` | Flutter example app |
| `rtls-mobile-example/` | React Native example app. [README](rtls-mobile-example/README.md) |

### Backend & Dashboard

| Path | Description |
|------|-------------|
| `backend-nodejs/` | Node.js + Express + PostgreSQL. REST batch upload, JWT auth, WebSocket broadcast. [README](backend-nodejs/README.md) |
| `rtls-dashboard/` | React 19 + Vite + Leaflet. WebSocket subscriber for live map. [README](rtls-dashboard/README.md) |

---

## Technology Matrix

| Layer | Stack | Key Implementation Details |
|-------|-------|----------------------------|
| **iOS Library** | Swift 5.9, SwiftPM, CoreLocation, BackgroundTasks, Combine | Actor-isolated `SyncEngine`, `BGProcessingTask` for background flush, `NWPathMonitor` network gate, WAL journal mode SQLite |
| **Android Library** | Kotlin 1.9, KMP (android target), Coroutines, OkHttp 4.x | `Mutex`-serialized flush, `FusedLocationProviderClient` (API 29+) with `LocationManager` fallback (≤28), `ConnectivityManager` + `callbackFlow` |
| **Flutter Plugin** | Dart 3, MethodChannel, EventChannel | Foreground service on Android, delegates all sync to native engines |
| **React Native** | TypeScript, native modules (Swift + Kotlin) | Same JS API both platforms, event emitter for `RECORDED`/`SYNC_EVENT`/`ERROR` |
| **Backend** | Node.js 18+, Express, TypeScript, Zod, `ws`, `pg`, `jsonwebtoken` | Stateless HTTP + stateful WebSocket, schema validation, JWT auth |
| **Dashboard** | React 19, Vite, Leaflet, TypeScript | WebSocket subscriber, no SSR, Leaflet tile layer for map |

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

**Response:**

```json
{
  "acceptedIds": ["uuid"],
  "rejected": [],
  "serverTime": 1709500001000
}
```

**Auth:** `Authorization: Bearer <JWT>`

### `GET /v1/locations/latest?userId=`

Returns the most recent stored point for a given user.

### `WS /v1/ws`

Server broadcasts `{ "type": "location", "point": { ... } }` on every new batch ingestion. Dashboard subscribes here.

---

## License

See [LICENSE](LICENSE).
