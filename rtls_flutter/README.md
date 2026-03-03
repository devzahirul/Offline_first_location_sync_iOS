# rtls_flutter

Cross-platform Flutter plugin delivering **offline-first, battery-aware location sync** over a single Dart API. Android is backed by a shared Kotlin Multiplatform sync engine ([rtls-kmp](../rtls-kmp/README.md)); iOS delegates to the native Swift [RTLSyncKit](https://github.com/devzahirul/Offline_first_location_sync_iOS) package. Both converge on the same backend contract — `POST /v1/locations/batch`, `GET /v1/locations/latest`, WebSocket `/v1/ws` — so the host app never thinks about platform differences.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  Dart (Host App)                 │
│  RTLSync.configure ─ startTracking ─ stopTracking│
│  getStats ─ flushNow ─ requestAlwaysAuthorization│
│  RTLSync.events (Stream<Map>)                    │
├────────────┬────────────────────────┬────────────┤
│MethodChannel│       EventChannel    │            │
├────────────┴────────────────────────┴────────────┤
│  Android (Kotlin)            │  iOS (Swift)      │
│  → rtls-kmp SyncEngine       │  → RTLSyncKit     │
│  → ForegroundService         │  → CLLocationMgr  │
│    (FOREGROUND_SERVICE_TYPE  │  → CoreLocation    │
│     _LOCATION)               │    significant /   │
│  → ActivityCompat perms      │    standard visits │
└──────────────────────────────┴───────────────────┘
```

**MethodChannel** handles one-shot calls (`configure`, `startTracking`, `stopTracking`, `getStats`, `flushNow`, `requestAlwaysAuthorization`). **EventChannel** delivers a continuous event stream back to Dart. The plugin implements `ActivityAware` on Android so permission requests route through `ActivityCompat` with the correct `Activity` reference.

---

## Dart API

```dart
import 'package:rtls_flutter/rtls_flutter.dart';
```

### RTLSyncConfig

| Parameter | Type | Purpose |
|-----------|------|---------|
| `baseUrl` | `String` | Backend root (no trailing slash) |
| `userId` | `String` | Logical user identifier |
| `deviceId` | `String` | Per-device identifier |
| `accessToken` | `String` | JWT / bearer token for `Authorization` header |
| `locationIntervalSeconds` | `int?` | Time-based capture interval (Android) |
| `locationDistanceMeters` | `double?` | Distance-based capture threshold (Android) |
| `useSignificantLocationOnly` | `bool?` | ~500 m / battery-optimized mode |
| `batchMaxSize` | `int?` | Max points per upload batch |
| `flushIntervalSeconds` | `int?` | Automatic flush cadence |
| `maxBatchAgeSeconds` | `int?` | Force-flush threshold for stale batches |

### Core Methods

```dart
await RTLSync.configure(RTLSyncConfig(
  baseUrl: 'https://api.example.com',
  userId: 'user-123',
  deviceId: 'device-456',
  accessToken: 'jwt-token',
  locationIntervalSeconds: 360,
  batchMaxSize: 50,
  flushIntervalSeconds: 30,
  maxBatchAgeSeconds: 120,
));

await RTLSync.requestAlwaysAuthorization();
await RTLSync.startTracking();
await RTLSync.stopTracking();

final stats = await RTLSync.getStats();
// stats.pendingCount, stats.oldestPendingRecordedAtMs

await RTLSync.flushNow();
```

### Event Stream

```dart
RTLSync.events.listen((Map<dynamic, dynamic> event) {
  switch (event['type']) {
    case 'recorded':
      // Full point: lat, lng, accuracy, altitude, speed, course, recordedAt
    case 'syncEvent':
      // uploadSucceeded → accepted/rejected counts
      // uploadFailed   → error message
    case 'error':
      // Runtime error description
    case 'trackingStarted':
    case 'trackingStopped':
  }
});
```

Events mirror RTLSyncKit semantics: `recorded` carries the full location point (horizontal accuracy, altitude, speed, course); `syncEvent` distinguishes `uploadSucceeded` (with `accepted` / `rejected` counts) from `uploadFailed` (with a human-readable `message`).

---

## Android Integration

### 1. Include rtls-kmp in the host app's Gradle build

The plugin's Android layer resolves `rtls-kmp` as a project dependency. The host app must surface it.

**settings.gradle.kts**

```kotlin
include(":rtls_kmp")
project(":rtls_kmp").projectDir = file("../../rtls-kmp") // adjust relative path
```

### 2. Manifest permissions

The plugin's own `AndroidManifest.xml` declares:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

Runtime permission requests are handled by the plugin via `ActivityCompat`. The plugin is `ActivityAware`, binding to the host `Activity` lifecycle for correct permission callback routing.

### 3. Foreground service

Tracking launches an Android foreground service typed as `FOREGROUND_SERVICE_TYPE_LOCATION`, ensuring location updates continue when the app is backgrounded. `LocationRequestParams` (interval, distance, significant-only) are derived from the `RTLSyncConfig` passed at configure time.

---

## iOS Integration

### 1. Link RTLSyncKit

1. Open `ios/Runner.xcworkspace` in Xcode.
2. **File → Add Package Dependencies…** → add the repo root (or Git URL) containing `Package.swift`.
3. Link the **RTLSyncKit** library to the **Runner** target under General → Frameworks, Libraries, and Embedded Content.

### 2. Info.plist usage strings

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Records your location for real-time sync.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Continues location recording in the background.</string>
```

### 3. Build

```bash
flutter run          # or flutter build ios
```

---

## Platform Summary

| | Android | iOS |
|---|---------|-----|
| **Engine** | rtls-kmp (Kotlin Multiplatform) | RTLSyncKit (Swift) |
| **Background** | Foreground service (`FOREGROUND_SERVICE_TYPE_LOCATION`) | CLLocationManager always-authorization |
| **Host Setup** | Include `:rtls_kmp` in `settings.gradle`; permissions in manifest | Link RTLSyncKit Swift package in Xcode; Info.plist strings |
| **Permission API** | `ActivityCompat` via `ActivityAware` plugin | `requestAlwaysAuthorization()` bridged through MethodChannel |

---

## Example App

See [example/README.md](example/README.md) — a production-quality Material 3 demo with configurable tracking policies, batching controls, WebSocket subscriber, and full event log.

---

## License

See repository [LICENSE](../LICENSE).
