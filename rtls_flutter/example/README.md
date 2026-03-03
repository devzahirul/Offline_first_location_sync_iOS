# RTLS Flutter Example

Production-grade **Flutter demo app** for the [rtls_flutter](../README.md) plugin. Runs identically on Android and iOS from a single Dart codebase — designed as a feature-complete reference implementation and portfolio piece demonstrating offline-first location sync with real-time UI feedback.

---

## Design Goals

- **Feature parity with the native iOS RTLS Demo** — same sections, same controls, same behavioral semantics (including the 60-second no-location timeout).
- **Material 3 theming** with a clean, single-screen layout that exposes every knob the plugin offers without overwhelming the user.
- **Auto-resume on relaunch** — a `SharedPreferences` `wasTracking` flag restores tracking state across cold starts, matching iOS background-resume behavior.

---

## UI Sections

| Section | Purpose |
|---------|---------|
| **Status** | Pending upload count, oldest pending timestamp, recording badge |
| **Backend** | Base URL, User ID, Device ID, access token — one-time configure with validation |
| **Tracking Policy** | Distance filter (meters), time interval (seconds), significant-location-only toggle |
| **Batch Sync** | `batchMaxSize` stepper, flush interval, max batch age — mirrors `RTLSyncConfig` parameters |
| **Actions** | Request Permission, Start, Stop, Flush Now — state-aware button disabling during async operations |
| **Map** | Placeholder for map integration |
| **Subscriber** | WebSocket real-time watch of another user's location stream |
| **Logs** | Scrollable event log with typed chips; navigable to a dedicated Pending Locations screen |

---

## Behavioral Details

**Event handling** drives the entire UI refresh cycle. Every plugin event triggers a stats refresh and appends to the log:

- `recorded` — displays `horizontalAccuracy` and full point metadata.
- `syncEvent` — shows `accepted` / `rejected` counts on success; error message on failure.
- `trackingStarted` / `trackingStopped` — toggles status badge and button states.
- `error` — surfaces in both the log and a SnackBar.

**60-second no-location timeout**: if no `recorded` event arrives within 60 seconds of starting, tracking stops automatically and an error is surfaced — matching the native iOS app's behavior exactly.

**WebSocket subscriber**: connects to `/v1/ws` to display another user's live location updates in real time, independent of the local tracking session.

---

## Run

```bash
cd rtls_flutter/example
flutter pub get
flutter run
```

### Android setup

The example's `android/settings.gradle.kts` already includes:

```kotlin
include(":rtls_kmp")
project(":rtls_kmp").projectDir = file("../../../rtls-kmp")
```

Location permissions are declared in `AndroidManifest.xml`. Grant when prompted.

### iOS setup

1. Open `ios/Runner.xcworkspace` in Xcode.
2. **File → Add Package Dependencies…** → add the repo root containing `Package.swift`.
3. Link **RTLSyncKit** to the **Runner** target (Frameworks, Libraries, and Embedded Content).
4. `flutter run` or build from Xcode.

---

## Usage Flow

1. Enter backend URL, user ID, device ID, access token → **Configure**.
2. Adjust tracking policy (distance / time / significant) and batch sync parameters as desired.
3. **Request Permission** → grant location (and "Always" on iOS).
4. **Start** — status badge activates, events stream into the log, stats refresh on each event.
5. **Flush Now** to force-upload pending points; **Stop** to end the session.
6. Relaunch the app — tracking resumes automatically if it was active.

---

## Project Layout

| Path | Purpose |
|------|---------|
| `lib/main.dart` | Entry point, Material 3 theme, orientation lock |
| `lib/screens/home_screen.dart` | Single-screen UI: all sections, RTLSync wiring, event subscription |
| `lib/theme/app_theme.dart` | Color scheme, card styling, button/input theming |
| `lib/constants/app_constants.dart` | Default URLs, identifiers, max log size |
| `pubspec.yaml` | Depends on `rtls_flutter: path: ../` |
| `android/settings.gradle.kts` | Includes `:rtls_kmp` with correct relative path |

---

## License

See repository [LICENSE](../../LICENSE).
