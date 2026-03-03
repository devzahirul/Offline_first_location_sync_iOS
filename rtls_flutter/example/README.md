# rtls_flutter_example

Minimal **Flutter application** that demonstrates integration with the [rtls_flutter](../README.md) plugin: configuration, start/stop tracking, flush, and live display of pending count and last event.

---

## Purpose

- **Reference implementation** for host app setup (dependency, Android KMP inclusion, iOS Swift package linking).
- **Single screen:** Base URL, User ID, Device ID, access token; Configure; Start / Stop; Flush now; pending count and last event text.
- **Backend:** Uses the same RTLS backend as other clients (`POST /v1/locations/batch`, optional WebSocket at `/v1/ws`). No backend code in this repo; point the app at your running API.

---

## Run

From this directory:

```bash
flutter pub get
flutter run
```

Select an Android or iOS device/emulator when prompted.

---

## Platform-specific setup

### Android

- The example’s **`android/settings.gradle.kts`** already includes the KMP project:  
  `include(":rtls_kmp")` and `project(":rtls_kmp").projectDir = file("../../../rtls-kmp")`  
  (path from `rtls_flutter/example/android/` to repo root’s `rtls-kmp`).
- **Permissions:** Location permissions are declared in `android/app/src/main/AndroidManifest.xml`. Grant them when the app prompts.
- No extra Gradle or native steps; build and run.

### iOS

- **Before building:** The example’s iOS target must link the **RTLSyncKit** Swift package.
  1. Open `ios/Runner.xcworkspace` in Xcode.
  2. **File → Add Package Dependencies…** → Add the repo root (directory containing `Package.swift`).
  3. Add the **RTLSyncKit** library to the **Runner** app target (Frameworks, Libraries, and Embedded Content).
- Then run `flutter run` or build from Xcode. If you see “Unable to find module 'RTLSyncKit'”, ensure the package is added and the Runner target links RTLSyncKit.

---

## Usage flow

1. Enter **Base URL** (e.g. `http://localhost:3000` or `http://<your-ip>:3000` for a device).
2. Enter **User ID**, **Device ID**, and **Access token** (must match backend auth if enabled).
3. Tap **Configure** (fields lock after configure).
4. Tap **Start** to begin tracking; **Pending** and **Last event** update from the plugin’s event stream.
5. Tap **Flush now** to force an immediate upload of pending points.
6. Tap **Stop** to stop collecting and syncing.

---

## Project layout

| Path | Purpose |
|------|----------|
| `lib/main.dart` | Single screen: config form, buttons, stats, event stream subscription |
| `pubspec.yaml` | Flutter app; dependency `rtls_flutter: path: ../` |
| `android/settings.gradle.kts` | Includes `:app` and `:rtls_kmp` (path to `../../../rtls-kmp`) |
| `ios/` | Standard Flutter iOS app; link RTLSyncKit as above |

---

## License

See repository [LICENSE](../../LICENSE).
