# RTLS Mobile Example (React Native)

Cross-platform **React Native** reference app demonstrating the [rtls-react-native](../rtls-react-native/README.md) native module on **both iOS and Android**. Covers the full lifecycle: configure backend credentials, request location authorization, start/stop tracking, flush pending data, and observe live events — all from a single JavaScript codebase.

---

## Platform Support

| Platform | Engine | Setup |
|----------|--------|-------|
| **iOS** | RTLSyncKit via Swift package | Link Swift package in Xcode; `pod install` |
| **Android** | rtls-kmp via Gradle subproject | Include `:rtls_kmp` in `settings.gradle`; grant location permissions |

The same JS API (`RTLSync.configure`, `startTracking`, `stopTracking`, `getStats`, `flushNow`, `requestAlwaysAuthorization`, event listeners) works identically on both platforms.

---

## Prerequisites

- **Node.js** 18+
- **Xcode** (iOS 15+) with CocoaPods
- **Android Studio** with SDK 26+
- **Backend** running (see [backend-nodejs](../backend-nodejs/README.md): `npm run dev`)

---

## Setup

### 1. Install JS dependencies

```bash
cd rtls-mobile-example
npm install
```

### 2. iOS

```bash
cd ios
bundle install
bundle exec pod install
cd ..
```

Link **RTLSyncKit**:

1. Open `ios/RTLSMobileExample.xcworkspace` in Xcode.
2. **File → Add Package Dependencies…** → add the repo root containing `Package.swift`.
3. Link the **RTLSyncKit** library to the app target (General → Frameworks, Libraries, and Embedded Content).

Or run the automation script after every `pod install`:

```bash
ruby scripts/add_rtls_swift_package.rb
```

### 3. Android

The example's `android/settings.gradle` already includes the rtls-kmp subproject:

```groovy
include ':rtls_kmp'
project(':rtls_kmp').projectDir = file('../../rtls-kmp')
```

Location permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`) are declared in the manifest. Grant them at runtime when prompted.

---

## Run

```bash
# iOS
npx react-native run-ios

# Android
npx react-native run-android
```

Or open the respective workspace/project in Xcode / Android Studio and build directly.

---

## Usage

1. Set **Base URL** (e.g. `http://localhost:3000` for simulator; use machine IP for physical devices).
2. Enter **User ID**, **Device ID**, **Access Token**.
3. Tap **Configure**.
4. Tap **Request Permission** — grant location access (and "Always" on iOS for background tracking).
5. Tap **Start Tracking** — pending count and events update in real time.
6. Tap **Flush** to force-upload; **Stop** to end the session.

---

## UI

Single-screen layout exposing the full module API:

- **Configuration fields** — base URL, user ID, device ID, access token
- **Action buttons** — Configure, Request Permission, Start, Stop, Flush
- **Live status** — pending location count, most recent event payload

---

## Project Layout

| Path | Purpose |
|------|---------|
| `package.json` | App dependencies; `rtls-react-native` via `file:../rtls-react-native` |
| `App.tsx` | UI: config fields, action buttons, pending count, event display |
| `ios/` | iOS project; Podfile includes rtls-react-native; RTLSyncKit linked via Swift package |
| `android/` | Android project; `settings.gradle` includes `:rtls_kmp` subproject |
| `scripts/add_rtls_swift_package.rb` | Automates RTLSyncKit Swift package reference in Pods project |

---

## License

See repository [LICENSE](../LICENSE).
