# Offline-First Real-Time Location Sync (iOS)

**A full-stack demo: iOS app, Swift packages, Node.js backend, and React dashboard** — built to show production-style offline-first design, background sync, and clean architecture.

---

## Why this project

Location tracking often fails in the real world: tunnels, weak signal, or battery-saving modes. This repo implements **offline-first** behavior: the app records location locally (SQLite), syncs when the network is available, and uses **background tasks** and lifecycle hooks to flush data without requiring the app to stay open. It’s the kind of system you’d want in fleet, delivery, or field-worker apps.

---

## Features

- **Offline-first** — Record to local SQLite; sync when online with configurable batching and retry
- **Background sync** — `BGProcessingTask` + app lifecycle hooks to upload pending points without keeping the app in foreground
- **Modular Swift** — Multi-target Swift Package (Core, Data, Sync, Platform, SyncKit) with testable core logic
- **Real-time dashboard** — React + TypeScript + Vite; Leaflet map; WebSocket stream of locations
- **REST + WebSocket API** — Node.js (Express, PostgreSQL, JWT, Zod); HTTP for uploads, WS for live subscription

---

## Tech stack

| Layer | Technologies |
|-------|--------------|
| **iOS** | Swift 5.9, SwiftPM, CoreLocation, BackgroundTasks, Combine |
| **Backend** | Node.js, Express, TypeScript, PostgreSQL, WebSocket (ws), JWT, Zod |
| **Dashboard** | React 19, TypeScript, Vite, Leaflet / react-leaflet |

---

## Architecture (high level)

```
[iOS App] → RTLSyncKit → RTLSSync (SyncEngine) → RTLSData (SQLite + HTTP/WS)
                ↑
         RTLSPlatformiOS (CoreLocation)
```

- **RTLSCore** — Types, policies (tracking, batching, retry, retention), store/API protocols  
- **RTLSData** — SQLite persistence, `URLSession` upload, WebSocket subscriber  
- **RTLSSync** — Sync engine, network monitoring  
- **RTLSyncKit** — Public API, `LocationSyncClient`, app lifecycle and background task scheduling  
- **RTLSPlatformiOS** — CoreLocation-backed location provider  

---

## Quick start

### Prerequisites

- **Xcode** (iOS 15+), **Node.js** 18+, **PostgreSQL** (for backend)

### 1. Backend

```bash
cd backend-nodejs
cp .env.example .env   # set DATABASE_URL, JWT_SECRET, etc.
npm install
npm run dev
```

### 2. Dashboard

```bash
cd rtls-dashboard
npm install
npm run dev
```

### 3. iOS app

1. Open `RealTimeLocationUpdateBackground/RealTimeLocationUpdateBackground.xcodeproj` in Xcode.
2. Set the app’s backend URL (e.g. in demo settings) to your backend (e.g. `http://localhost:3000` or your machine’s IP for a device).
3. Run on a device or simulator (device recommended for location).
4. Grant location permission and start tracking; watch the dashboard for live updates.

### 4. Run tests (Swift)

```bash
swift test
```

---

## Project structure

| Path | Description |
|------|-------------|
| `RealTimeLocationUpdateBackground/` | iOS app (SwiftUI, demo UI, map, settings) |
| `Sources/RTLSCore` | Core types, store/API protocols, policies |
| `Sources/RTLSData` | SQLite store, HTTP upload, WebSocket client |
| `Sources/RTLSSync` | Sync engine, network monitoring |
| `Sources/RTLSyncKit` | Public client API, lifecycle hooks, background sync |
| `Sources/RTLSPlatformiOS` | CoreLocation-based location provider |
| `Tests/RTLSCoreTests` | Unit tests for core logic |
| `backend-nodejs/` | Node.js API (REST + WebSocket, PostgreSQL) |
| `rtls-dashboard/` | React dashboard (Vite, TypeScript, Leaflet) |

---

## What I’d highlight in an interview

- **Offline-first**: Local-first storage, sync when online, and clear handling of pending/failed uploads.
- **Swift packaging**: Separated core, data, sync, and platform layers for testability and reuse.
- **Background behavior**: Use of `BGProcessingTask` and app lifecycle to sync without foreground usage.
- **Full stack**: One repo with iOS (Swift), backend (Node/TS), and dashboard (React) wired end-to-end.

---

## License

See [LICENSE](LICENSE) in this repository.
