# rtls-react-native

React Native native module providing **offline-first location sync** with a unified JavaScript API across **iOS and Android**. iOS delegates to the [RTLSyncKit](https://github.com/devzahirul/Offline_first_location_sync_iOS) Swift engine; Android wraps the shared [rtls-kmp](../rtls-kmp/README.md) Kotlin Multiplatform sync engine — the same core that powers the native Android app and the Flutter plugin's Android layer.

---

## Architecture

```
┌──────────────────────────────────────────────┐
│              JS / TypeScript                  │
│  RTLSync.configure ─ startTracking ─ stop    │
│  getStats ─ flushNow ─ requestAlwaysAuth     │
│  NativeEventEmitter → event subscriptions    │
├──────────────┬───────────────────────────────┤
│  iOS (Swift) │        Android (Kotlin)       │
│  RTLSyncKit  │        rtls-kmp SyncEngine    │
│  CLLocation  │        FusedLocation +        │
│  Manager     │        ForegroundService      │
└──────────────┴───────────────────────────────┘
```

Both platforms converge on the same backend contract: `POST /v1/locations/batch`, `GET /v1/locations/latest`, WebSocket `/v1/ws`.

---

## TypeScript API

### Types

```ts
interface RTLSConfigureConfig {
  baseURL: string;
  userId: string;
  deviceId: string;
  accessToken: string;
  batchMaxSize?: number;
  flushIntervalSeconds?: number;
  maxBatchAgeSeconds?: number;
  locationIntervalSeconds?: number;
  locationDistanceMeters?: number;
  useSignificantLocationOnly?: boolean;
}

interface RTLSStats {
  pendingCount: number;
  oldestPendingRecordedAt: number | null;
}

interface RTLSRecordedPoint {
  id: string;
  userId: string;
  deviceId: string;
  recordedAt: number;
  lat: number;
  lng: number;
  horizontalAccuracy?: number;
  altitude?: number;
  speed?: number;
  course?: number;
}

interface RTLSyncEventPayload {
  type: 'uploadSucceeded' | 'uploadFailed';
  accepted?: number;
  rejected?: number;
  message?: string;
}

type RTLSAuthorizationStatus =
  | 'notDetermined'
  | 'restricted'
  | 'denied'
  | 'authorizedWhenInUse'
  | 'authorizedAlways';
```

### Core Methods

```ts
import RTLSync from 'rtls-react-native';

await RTLSync.configure({
  baseURL: 'https://api.example.com',
  userId: 'user-1',
  deviceId: 'device-1',
  accessToken: 'jwt-token',
  batchMaxSize: 50,
  flushIntervalSeconds: 30,
  maxBatchAgeSeconds: 120,
  locationIntervalSeconds: 360,
  locationDistanceMeters: 100,
  useSignificantLocationOnly: false,
});

await RTLSync.requestAlwaysAuthorization();
await RTLSync.startTracking();
await RTLSync.stopTracking();

const stats = await RTLSync.getStats();
await RTLSync.flushNow();
```

### Events via NativeEventEmitter

```ts
import RTLSync from 'rtls-react-native';

const sub = RTLSync.addEventListener('rtls_recorded', (point: RTLSRecordedPoint) => {
  // Full location point with accuracy, altitude, speed, course
});

const syncSub = RTLSync.addEventListener('rtls_syncEvent', (e: RTLSyncEventPayload) => {
  // uploadSucceeded → e.accepted, e.rejected
  // uploadFailed   → e.message
});

// Cleanup
sub.remove();
syncSub.remove();
```

**Event names:** `rtls_recorded`, `rtls_syncEvent`, `rtls_error`, `rtls_authorizationChanged`, `rtls_trackingStarted`, `rtls_trackingStopped`.

All events are emitted through React Native's `NativeEventEmitter`, ensuring thread-safe delivery on the JS thread regardless of which native thread originated the event.

---

## Android Integration

### 1. Include rtls-kmp

The module's Android layer depends on `rtls-kmp` as a Gradle project dependency. The host app must include it.

**android/settings.gradle.kts:**

```kotlin
include(":rtls_kmp")
project(":rtls_kmp").projectDir = file("../../rtls-kmp") // adjust path
```

### 2. Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

`requestAlwaysAuthorization()` triggers the actual Android runtime permission flow. `startTracking()` verifies permission state before activating the location provider. `LocationRequestParams` and `BatchingPolicy` are derived from the config passed at `configure()` time.

### 3. Build

```bash
npx react-native run-android
```

---

## iOS Integration

### 1. CocoaPods

```bash
cd ios && pod install
```

### 2. Link RTLSyncKit Swift package

1. Open `ios/YourApp.xcworkspace` in Xcode.
2. **File → Add Package Dependencies…** → add the repo root (or Git URL) containing `Package.swift`.
3. Link **RTLSyncKit** to the app target (General → Frameworks, Libraries, and Embedded Content).

Without this step the build fails with `Unable to find module 'RTLSyncKit'`.

**Runtime optimization:** RTLSyncKit is linked at launch but performs zero work (no SQLite, no CoreLocation, no networking) until `configure()` and `startTracking()` are called. The sync engine initializes lazily.

### Optional: RTLS_LITE build

For app variants that never use location sync, omit the Swift package and set the `RTLS_LITE` compilation condition:

```ruby
post_install do |installer|
  installer.pods_project.targets.each do |t|
    if t.name == 'rtls-react-native'
      t.build_configurations.each do |config|
        config.build_settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] ||= ['$(inherited)']
        config.build_settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] << 'RTLS_LITE'
      end
    end
  end
end
```

All methods reject with `"RTLSyncKit not linked"` — the module compiles and loads without the Swift dependency.

### 3. Build

```bash
npx react-native run-ios
```

---

## Platform Summary

| | iOS | Android |
|---|-----|---------|
| **Engine** | RTLSyncKit (Swift) | rtls-kmp (Kotlin Multiplatform) |
| **Background** | CLLocationManager always-authorization | Foreground service + FusedLocationProvider |
| **Host Setup** | Link RTLSyncKit Swift package; CocoaPods | Include `:rtls_kmp` in Gradle; manifest permissions |
| **Permission API** | Maps to CLLocationManager | Maps to ActivityCompat runtime permission |

---

## Example App

See [rtls-mobile-example/README.md](../rtls-mobile-example/README.md) — a cross-platform React Native demo covering install order, Swift package linking, Gradle subproject inclusion, and full API usage.

---

## License

See repository [LICENSE](../LICENSE).
