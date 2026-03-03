# rtls_flutter

Flutter plugin for **offline-first real-time location sync**. On **Android** it uses the shared KMP sync module; on **iOS** it uses the Swift **RTLSyncKit** package. Both talk to the same backend (`POST /v1/locations/batch`, optional WebSocket at `/v1/ws`).

---

## Adding the plugin to your Flutter app

### 1. Dependency

In your app’s `pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  rtls_flutter:
    path: ../rtls_flutter   # if the plugin lives in the same repo
    # or
    # git:
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

The plugin’s Android code depends on the **rtls-kmp** module. Your app must include it in the Gradle build.

In your Flutter app’s **`android/settings.gradle`** or **`android/settings.gradle.kts`**, add (adjust the path so it points to the `rtls-kmp` folder in your repo):

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

Example: if your repo layout is:

```
your-repo/
  rtls-kmp/
  your_flutter_app/
    android/
```

then from `your_flutter_app/android/` the path to rtls-kmp is `../../rtls-kmp`, so use `file("../../rtls-kmp")`.

### 2. Permissions

In **`android/app/src/main/AndroidManifest.xml`**, add:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <!-- rest of your manifest -->
</manifest>
```

Request location permission at runtime (e.g. with `permission_handler` or platform APIs) before calling `startTracking()`.

### 3. Build and run

```bash
flutter run
# or
flutter build apk
flutter build appbundle
```

---

## iOS setup

### 1. Add the RTLSyncKit Swift package

The plugin’s iOS implementation uses **RTLSyncKit**. Your app target must link that Swift package.

1. Open your app’s iOS project in Xcode:  
   `open ios/Runner.xcworkspace` (or your `.xcworkspace`).
2. **File → Add Package Dependencies…**
3. Choose **Add Local…** and select the **root folder of the repo** that contains `Package.swift` (and the `Sources/` with RTLSyncKit).  
   If you use a remote repo, add the package by URL instead.
4. Add the **RTLSyncKit** library to your **Runner** (or main app) target: select the target → **General → Frameworks, Libraries, and Embedded Content** → **+** → add **RTLSyncKit**.

### 2. Location usage and permissions

In **`ios/Runner/Info.plist`** (or your app’s Info.plist), add the usage descriptions:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app uses location to record and sync your position.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app uses location in the background to sync your position.</string>
```

Request authorization at runtime (e.g. call `RTLSync.requestAlwaysAuthorization()` or use CoreLocation in native code) before starting tracking.

### 3. Build and run

```bash
flutter run
# or
flutter build ios
```

Then run from Xcode or `flutter run` on a device/simulator.

---

## Using the plugin in Dart

### Import

```dart
import 'package:rtls_flutter/rtls_flutter.dart';
```

### Configure (once, e.g. at app startup)

```dart
await RTLSync.configure(RTLSyncConfig(
  baseUrl: 'https://your-api.example.com',  // no trailing slash
  userId: 'user-123',
  deviceId: 'device-456',
  accessToken: 'your-jwt-or-token',
));
```

### Start / stop tracking

```dart
// After user grants location permission:
await RTLSync.startTracking();

// Later:
await RTLSync.stopTracking();
```

### Request background location (iOS)

On iOS, to enable “Always” location (background), call:

```dart
await RTLSync.requestAlwaysAuthorization();
```

Handle the system prompt and then start tracking.

### Get pending stats

```dart
final stats = await RTLSync.getStats();
print('Pending points: ${stats.pendingCount}');
print('Oldest pending (ms): ${stats.oldestPendingRecordedAtMs}');
```

### Flush pending data now

```dart
await RTLSync.flushNow();
```

### Listen to events

Events include recorded points, sync success/failure, errors, and tracking started/stopped:

```dart
RTLSync.events.listen((Map<dynamic, dynamic> event) {
  final type = event['type']; // e.g. 'recorded', 'syncEvent', 'error', 'trackingStarted', 'trackingStopped'
  // event may contain 'point', 'message', 'event', etc.
});
```

---

## Summary

| Platform | What you need |
|----------|----------------|
| **Android** | 1) Add `rtls_flutter` dependency. 2) Include `rtls_kmp` in `android/settings.gradle` (or `.kts`) with correct path. 3) Add location permissions in AndroidManifest and request at runtime. |
| **iOS**     | 1) Add `rtls_flutter` dependency. 2) Add **RTLSyncKit** Swift package in Xcode and link it to the app target. 3) Add location usage strings in Info.plist and request authorization. |

Then use `RTLSync.configure()`, `startTracking()`, `stopTracking()`, `getStats()`, `flushNow()`, and `RTLSync.events` from your Dart code on both platforms.
