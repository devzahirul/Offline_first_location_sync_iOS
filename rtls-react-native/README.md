# rtls-react-native

**React Native native module** for offline-first location sync on **iOS**. Exposes the existing [RTLSyncKit](https://github.com/devzahirul/Offline_first_location_sync_iOS) Swift engine to JavaScript so RN apps can record location locally and sync when online, with the same backend contract as the native iOS and Android clients.

---

## Overview

- **Platform:** iOS only. The native module is implemented in Swift and wraps RTLSyncKit. Android is not implemented (planned: Kotlin or JS fallback using the same backend API).
- **Backend:** Same API as the rest of the system: `POST /v1/locations/batch`, `GET /v1/locations/latest?userId=`, WebSocket `/v1/ws`. JWT in `Authorization` header where required.
- **API surface:** Configure (base URL, userId, deviceId, access token), requestAlwaysAuthorization, startTracking, stopTracking, getStats, flushNow, and event listeners (RECORDED, SYNC_EVENT, ERROR, AUTHORIZATION_CHANGED, TRACKING_STARTED, TRACKING_STOPPED).

---

## Installation

From the repo root (or the directory that contains the Swift package and this package):

```bash
npm install file:../rtls-react-native
# or
yarn add file:../rtls-react-native
```

Or in `package.json`:

```json
"dependencies": {
  "rtls-react-native": "file:../rtls-react-native"
}
```

Then run `npm install` (or `yarn`) in the app root.

---

## iOS setup

### 1. Native module and CocoaPods

If your app uses CocoaPods, run from the app’s `ios/` directory:

```bash
cd ios
pod install
```

The package ships with a podspec so the native code is linked.

### 2. Link the RTLSyncKit Swift package

The module **imports RTLSyncKit**; the app target must include the Swift package.

1. Open your app’s **`.xcworkspace`** in Xcode (e.g. `ios/YourApp.xcworkspace`).
2. **File → Add Package Dependencies…**
3. Add the package that contains **Package.swift** (this repo root, or the Git URL).
4. Add the **RTLSyncKit** library to your **app target** (General → Frameworks, Libraries, and Embedded Content → + → RTLSyncKit).

Without this step, the build will fail with “Unable to find module 'RTLSyncKit'”.

### 3. Rebuild

```bash
npx react-native run-ios
```

Or build from Xcode.

---

## JavaScript / TypeScript API

### Configure (required before tracking)

```ts
import RTLSync from 'rtls-react-native';

await RTLSync.configure({
  baseURL: 'https://your-backend.com',
  userId: 'user-1',
  deviceId: 'device-1',
  accessToken: 'your-jwt-or-token',
});
```

### Permissions and tracking

```ts
await RTLSync.requestAlwaysAuthorization();  // for background updates (iOS)
await RTLSync.startTracking();
// ...
await RTLSync.stopTracking();
```

### Stats and flush

```ts
const stats = await RTLSync.getStats();
// { pendingCount: number, oldestPendingRecordedAt: number | null }
await RTLSync.flushNow();
```

### Events

Subscribe to events (event names and payloads aligned with RTLSyncKit):

```ts
import RTLSync, { RTLSyncEvents } from 'rtls-react-native';

const sub1 = RTLSync.addEventListener('RECORDED', (point) => {
  console.log('Recorded', point);
  // { id, userId, deviceId, recordedAt, lat, lng, ... }
});

const sub2 = RTLSync.addEventListener('SYNC_EVENT', (e) => {
  console.log('Sync', e);
  // { type: 'uploadSucceeded' | 'uploadFailed', accepted?, rejected?, message? }
});

// cleanup
sub1.remove();
sub2.remove();
```

**Event names:** `RECORDED`, `SYNC_EVENT`, `ERROR`, `AUTHORIZATION_CHANGED`, `TRACKING_STARTED`, `TRACKING_STOPPED`.

---

## Backend contract (reference)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/v1/locations/batch` | Upload batches; JWT in `Authorization: Bearer <token>` |
| `GET` | `/v1/locations/latest?userId=…` | Latest point for user |
| WebSocket | `/v1/ws` | Subscribe; server broadcasts location updates |

See the repo’s [backend-nodejs/README.md](../backend-nodejs/README.md) and root [README.md](../README.md) for full API and run instructions.

---

## Example app

The repository includes a minimal React Native app that uses this module and links RTLSyncKit: [rtls-mobile-example/README.md](../rtls-mobile-example/README.md). Use it as a reference for install order, `pod install`, and the script that adds the Swift package to the Pods project.

---

## Android

Not implemented. Planned: Kotlin (reusing rtls-kmp) or a JS-side implementation that calls the same REST/WebSocket API.

---

## License

See repository [LICENSE](../LICENSE).
