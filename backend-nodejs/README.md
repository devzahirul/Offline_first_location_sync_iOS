# RTLS Backend (Node.js)

REST and WebSocket API for **Real-Time Location Sync**. Single service: batch upload of location points, latest-point query, and live stream over WebSocket. Used by all clients (iOS, Android, Flutter, React Native) under a single contract.

---

## Overview

- **Stack:** Node.js, Express, TypeScript. Optional PostgreSQL for persistence; in-memory fallback when `DATABASE_URL` is unset. Authentication via JWT when `JWT_SECRET` is set.
- **API surface:** Three entry points — `POST /v1/locations/batch`, `GET /v1/locations/latest`, WebSocket `/v1/ws`. CORS enabled for dashboard and mobile clients.
- **Validation:** Request bodies and WebSocket messages validated with [Zod](https://github.com/colinhacks/zod); invalid payloads return 400 with error details.

---

## API specification

### Health and discovery

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Service info: name, health path, API and WebSocket endpoints |
| `GET` | `/health` | Health check: `{ "ok": true }` |

### REST (JSON)

#### `POST /v1/locations/batch`

Upload a batch of location points. Requires `Authorization: Bearer <token>` when `JWT_SECRET` is set.

**Request body (Zod schema):**

```ts
{
  schemaVersion: number;  // integer >= 1
  points: Array<{
    id: string;           // UUID
    userId: string;       // non-empty
    deviceId: string;    // non-empty
    recordedAt: number;  // integer, ms since epoch, >= 0
    lat: number;
    lng: number;
    horizontalAccuracy?: number | null;
    verticalAccuracy?: number | null;
    altitude?: number | null;
    speed?: number | null;
    course?: number | null;
  }>;
}
```

**Response:** `200 OK`

```ts
{
  acceptedIds: string[];   // ids of accepted points
  rejected: Array<{ id: string; reason: string }>;
  serverTime?: number;      // server timestamp (ms)
}
```

- If PostgreSQL is configured, points are inserted (table created from `sql/001_init.sql` if needed). In-memory mode updates an internal map and broadcasts to WebSocket subscribers.
- Each point is broadcast as `{ type: "location", point }` to clients subscribed to that `userId`.

**Errors:** `400` (validation), `401` (missing or invalid JWT when auth is enabled).

---

#### `GET /v1/locations/latest?userId=<userId>`

Return the most recently stored point for a user. Requires `Authorization: Bearer <token>` when `JWT_SECRET` is set.

**Query:**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `userId` | Yes | User identifier |

**Response:** `200 OK`

```ts
{ "point": LocationPoint | null }
```

**Errors:** `400` (missing `userId`), `401` (auth).

---

### WebSocket: `/v1/ws`

- **Protocol:** JSON messages over raw WebSocket (no STOMP/SockJS).
- **Authentication:** Optional; if `JWT_SECRET` is set, clients may need to send a first message carrying token (implementation may vary; see server code for current behavior).
- **Client → Server:** `{ "type": "subscribe", "userId": "<userId>" }` — subscribe to location updates for that user.
- **Server → Client:** `{ "type": "location", "point": LocationPoint }` — broadcast when a new point for that user is received via `POST /v1/locations/batch`.

Used by the web dashboard for live map updates.

---

## Environment configuration

| Variable | Required | Description |
|----------|----------|-------------|
| `HOST` | No | Bind address (default from Express). Use `0.0.0.0` for LAN access (e.g. physical device). |
| `PORT` | No | Port (e.g. `3000`). |
| `DATABASE_URL` | No | PostgreSQL connection string. If unset, storage is in-memory (no persistence across restarts). |
| `JWT_SECRET` | No | Secret for JWT verification. If unset or empty, all authenticated endpoints accept any request (no auth). **Set in production.** |

Example `.env` (copy from `.env.example`):

```bash
HOST=0.0.0.0
PORT=3000
DATABASE_URL=postgres://postgres:postgres@localhost:5432/rtls
JWT_SECRET=your-secret-here
```

---

## Run

```bash
cd backend-nodejs
npm install
cp .env.example .env   # edit as needed
npm run dev            # tsx watch; use npm run build && npm start for production
```

- **Without `.env`:** Server starts with auth disabled and in-memory storage; suitable for local/demo.
- **With `DATABASE_URL`:** Table is auto-created from `sql/001_init.sql` if it does not exist.

### Physical device on LAN

1. Ensure Mac and device are on the same network.
2. Set `HOST=0.0.0.0` and `PORT=3000` in `.env`.
3. Get host IP, e.g. `ipconfig getifaddr en0`.
4. In the mobile app, set base URL to `http://<HOST_IP>:3000` (not `localhost`).
5. Android emulator: use `http://10.0.2.2:3000` to reach host loopback.

---

## Project layout

| Path | Purpose |
|------|----------|
| `src/index.ts` | Express app, routes, WebSocket server, CORS |
| `src/auth.ts` | JWT extraction and verification (`requireAuth`) |
| `src/validation.ts` | Zod schemas for batch and WebSocket |
| `src/types.ts` | TypeScript types for points, batch, result, WS envelopes |
| `src/db.ts` | PostgreSQL client, table init, insert, latest-by-user |
| `src/wsHub.ts` | WebSocket hub (subscribe by userId, broadcast) |
| `src/env.ts` | Minimal `.env` loader (no dotenv dependency) |
| `sql/001_init.sql` | Table DDL for location points |

---

## Security notes

- **Production:** Set `JWT_SECRET` and issue short-lived tokens; do not disable auth in production.
- **CORS:** Currently permissive (`*`); restrict origins for production.
- **Input:** All batch and WS payloads are validated; invalid input is rejected with 400.

---

## License

See repository [LICENSE](../LICENSE).
