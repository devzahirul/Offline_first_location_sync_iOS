# rtls_flutter

**Flutter plugin** for offline-first real-time location sync. Exposes a single Dart API; on **Android** the implementation delegates to the shared [rtls-kmp](../rtls-kmp/README.md) module; on **iOS** it delegates to the Swift [RTLSyncKit](https://github.com/devzahirul/Offline_first_location_sync_iOS) package. Both sides use the same backend: `POST /v1/locations/batch`, optional `GET /v1/locations/latest`, and optional WebSocket `/v1/ws`.

---

## Overview

- **Contract:** Configure once (base URL, userId, deviceId, access token); then start/stop tracking, get pending stats, flush immediately, and subscribe to an event stream (recorded, sync, error, tracking started/stopped).
- **Android:** Plugin’s native code depends on the KMP library. The **host app** must include the `rtls-kmp` project in its Gradle settings and declare the dependency so the plugin can resolve it.
- **iOS:** Plugin’s native code imports RTLSyncKit. The **host app** must add the RTLSyncKit Swift package in Xcode and link it to the app target.
- **Channels:** MethodChannel for one-shot calls (configure, startTracking, stopTracking, getStats, flushNow, requestAlwaysAuthorization); EventChannel for the continuous event stream.

---

## Adding the plugin to your app

### 1. Dependency

In your Flutter app’s `pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  rtls_flutter:
    path: ../rtls_flutter   # adjust path to this repo
    # or git:
    #   url: https://github.com/your-org/your-repo.git
    #   path: rtls_flutter
```

Then run:

```bash
flutter pub get
```

---

## Android setup

### 1. Include the KMP module

The plugin’s Android implementation uses `implementation project(':rtls_kmp')` (or `:rtls-kmp` depending on how you name the project). The **host app** must include that project in its Gradle build.

In the host app’s **`android/settings.gradle`** or **`android/settings.gradle.kts`**, add (path relative to the host’s `android/` directory):

**Groovy (`settings.gradle`):**

```groovy
include ':app'
include ':rtls_kmp'
project(':rtls_kmp').projectDir = file('<path-to-rtls-kmp>')
```

**Kotlin DSL (`settings.gradle.kts`):**

```kotlin
include(":app")
include(":rtls_kmp")
project(":rtls_kmp").projectDir = file("<path-to-rtls-kmp>")
```

Example: repo layout `your_repo/rtls-kmp` and `your_repo/your_flutter_app/android/` → use `file("../../rtls-kmp")`.

### 2. Permissions

In **`android/app/src/main/AndroidManifest.xml`**:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <application ...>
```

Request location (and background location if needed) at runtime before calling `startTracking()`; use `permission_handler` or platform APIs as appropriate.

### 3. Build

```bash
flutter run
# or
flutter build apk
flutter build appbundle
```

---

## iOS setup

### 1. Link RTLSyncKit

The plugin’s iOS code imports RTLSyncKit. The app target must link the Swift package.

1. Open the app’s iOS project in Xcode:  
   `open ios/Runner.xcworkspace` (or your `.xcworkspace`).
2. **File → Add Package Dependencies…**
3. Add the package that contains `Package.swift` (this repo root, or the Git URL).
4. Add the **RTLSyncKit** library to the **Runner** (or main app) target:  
   Target → **General → Frameworks, Libraries, and Embedded Content** → **+** → **RTLSyncKit**.

### 2. Location usage strings

In **`ios/Runner/Info.plist`** (or the app’s Info.plist):

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app uses location to record and sync your position.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app uses location in the background to sync your position.</string>
```

Request authorization at runtime (e.g. `RTLSync.requestAlwaysAuthorization()` or CoreLocation) before starting tracking.

### 3. Build

```bash
flutter run
# or
flutter build ios
```

---

## Dart API

### Import

```dart
import 'package:rtls_flutter/rtls_flutter.dart';
```

### Configure (once)

```dart
await RTLSync.configure(RTLSyncConfig(
  baseUrl: 'https://your-api.example.com',  // no trailing slash
  userId: 'user-123',
  deviceId: 'device-456',
  accessToken: 'your-jwt-or-token',
));
```

### Tracking

```dart
await RTLSync.startTracking();
// ...
await RTLSync.stopTracking();
```

### Background location (iOS)

```dart
await RTLSync.requestAlwaysAuthorization();
// then startTracking() when authorized
```

### Stats and flush

```dart
final stats = await RTLSync.getStats();
// stats.pendingCount, stats.oldestPendingRecordedAtMs
await RTLSync.flushNow();
```

### Event stream

```dart
RTLSync.events.listen((Map<dynamic, dynamic> event) {
  final type = event['type'];  // e.g. 'recorded', 'syncEvent', 'error', 'trackingStarted', 'trackingStopped'
  // event may contain 'point', 'message', 'event', etc.
});
```

---

## Summary

| Platform | Requirements |
|----------|--------------|
| **Android** | 1) Add `rtls_flutter` dependency. 2) Include `rtls_kmp` in `android/settings.gradle` (or `.kts`) with correct path. 3) Declare location permissions and request at runtime. |
| **iOS** | 1) Add `rtls_flutter` dependency. 2) Add RTLSyncKit Swift package in Xcode and link to app target. 3) Add location usage strings and request authorization. |

Then use `RTLSync.configure()`, `startTracking()`, `stopTracking()`, `getStats()`, `flushNow()`, `requestAlwaysAuthorization()`, and `RTLSync.events` from Dart on both platforms.

---

## Example app

See [example/README.md](example/README.md) for a minimal Flutter app that uses the plugin and documents Android/iOS setup for the example project.

---

## License

See repository [LICENSE](../LICENSE).
