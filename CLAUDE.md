# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RTLS (Real-Time Location Sync) is a modular, offline-first location telemetry SDK spanning iOS, Android/KMP, Flutter, React Native, a Node.js backend, and a React dashboard. Each capability (GPS collection, offline sync, WebSocket) is an independent package depending only on `core`.

## Build & Run Commands

### iOS (Swift Package Manager)
```bash
swift build                    # Build all targets
swift test                     # Run unit tests (Tests/RTLSCoreTests/)
swift test --filter RTLSCoreTests.LocationRecordingDeciderTests  # Single test
```

### Android / KMP (Gradle)
```bash
cd rtls-kmp && ./gradlew assembleDebug    # Build all KMP modules
cd rtls-android-app && ./gradlew installDebug  # Build & install example app
```

### Backend (Node.js + Express + PostgreSQL)
```bash
cd backend-nodejs
cp .env.example .env          # Configure DATABASE_URL, JWT_SECRET, HOST, PORT
npm install && npm run dev    # Dev server with tsx watch
npm run build && npm start    # Production build
```

### Dashboard (React + Vite + Leaflet)
```bash
cd rtls-dashboard
npm install && npm run dev    # Vite dev server
npm run lint                  # ESLint
npm run build                 # Production build (tsc + vite)
```

### Flutter
```bash
cd rtls_flutter/example && flutter run          # Run example app
cd packages/rtls_core && dart pub get           # Get deps for individual package
```

### React Native Example
```bash
cd rtls-mobile-example
npm install
npx react-native run-ios      # iOS
npx react-native run-android  # Android
```

## Architecture

### Modular dependency graph
All feature packages depend **only** on `core`. The orchestrator (`client`/`RTLSyncKit`/`rtls_flutter_unified`) is optional and wires selected packages together.

```
core ← offline-sync (SQLite + HTTP batch + bidirectional pull/merge)
core ← websocket    (bidirectional push/subscribe, auto-reconnect)
core ← location     (GPS collection, LocationRecordingDecider)
core ← client       (optional orchestrator facade)
```

### Platform mapping (same architecture, 3 implementations)

| Layer | iOS (SwiftPM) | Android/KMP (Gradle) | Flutter (Dart packages) |
|-------|---------------|----------------------|------------------------|
| Core | `Sources/RTLSCore/` | `rtls-kmp/rtls-core/` | `packages/rtls_core/` |
| Offline Sync | `Sources/RTLSOfflineSync/` | `rtls-kmp/rtls-offline-sync/` | `packages/rtls_offline_sync/` |
| WebSocket | `Sources/RTLSWebSocket/` | `rtls-kmp/rtls-websocket/` | `packages/rtls_websocket/` |
| Location | `Sources/RTLSLocation/` | `rtls-kmp/rtls-location/` | `packages/rtls_location/` |
| Orchestrator | `Sources/RTLSyncKit/` | `rtls-kmp/rtls-client/` | `packages/rtls_flutter_unified/` |

React Native (`rtls-react-native/`) bridges to RTLSyncKit (iOS) and rtls-kmp (Android) via native modules.

### Backend structure (`backend-nodejs/src/`)
- `index.ts` — Express server entry, mounts routes and WebSocket
- `db.ts` — PostgreSQL (or in-memory fallback) storage
- `auth.ts` — JWT authentication middleware
- `validation.ts` — Zod schemas for request validation
- `wsHub.ts` — WebSocket v2 hub (auth, push, subscribe, sync.pull, ping/pong)
- `types.ts` / `env.ts` — Shared types and environment config

### Key design invariants
- **Offline-first**: Local SQLite is the system of record on clients. Upload is background reconciliation.
- **Idempotent writes**: UUID-based deduplication end-to-end (client `INSERT OR REPLACE`/`INSERT OR IGNORE`, server dedup on `id`).
- **Serialized flush**: iOS uses Swift Actor isolation; Android uses Kotlin `Mutex` with `tryLock`. Concurrent flushes are dropped, not queued.
- **Failure model**: Transport errors leave points as `pending` for retry. Only explicit server rejections mark `failed`. No data loss on flaky connections.
- **Shared policies across platforms**: `BatchingPolicy`, `SyncRetryPolicy`, `RetentionPolicy` have identical semantics on iOS, Android, and Flutter.

### API contract
- `POST /v1/locations/batch` — Batch upload (gzip supported)
- `GET /v1/locations/pull?userId=&cursor=&limit=` — Cursor-based bidirectional sync
- `GET /v1/locations/latest?userId=` — Most recent point
- `WS /v1/ws` — WebSocket v2 protocol (auth, location.push, location.batch, subscribe, sync.pull, ping/pong)

### KMP source sets
KMP modules use `commonMain` for shared logic and `androidMain` for platform implementations (SQLite via Android SDK, OkHttp, FusedLocationProviderClient, ConnectivityManager). Package namespace: `com.rtls.{core,sync,websocket,location,client}`.

### iOS concurrency
The iOS sync engine (`SyncEngine`) is a Swift Actor. `SQLiteLocationStore` uses WAL mode. Network monitoring uses `NWPathMonitor`. Background sync uses `BGProcessingTask`.
