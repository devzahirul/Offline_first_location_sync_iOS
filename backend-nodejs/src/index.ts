import http from "node:http";
import { createGunzip } from "node:zlib";
import os from "node:os";
import express from "express";
import type { Request, Response, NextFunction } from "express";
import rateLimit from "express-rate-limit";

function getLanIp(): string | null {
  const ifaces = os.networkInterfaces();
  for (const a of Object.values(ifaces)) {
    if (!a) continue;
    for (const i of a) {
      if (i.family === "IPv4" && !i.internal) return i.address;
    }
  }
  return null;
}
import WebSocket, { WebSocketServer } from "ws";
import { UploadBatchSchema, WsSubscribeSchema, WsClientMessageSchema, PullQuerySchema } from "./validation.js";
import type { LocationUploadResult, WsLocationEnvelope } from "./types.js";
import { createDB, insertPoints, latestPointForUser, pullPointsForUser } from "./db.js";
import { requireAuth } from "./auth.js";
import { WsHub } from "./wsHub.js";
import { loadDotEnvIfPresent } from "./env.js";

await loadDotEnvIfPresent();

const app = express();
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});
const jsonParser = express.json({ limit: "2mb" });
app.use((req: Request, res: Response, next: NextFunction) => {
  if (req.headers["content-encoding"] === "gzip") {
    const chunks: Buffer[] = [];
    const gunzip = createGunzip();
    req.pipe(gunzip);
    gunzip.on("data", (chunk: Buffer) => chunks.push(chunk));
    gunzip.on("end", () => {
      try {
        (req as any).body = JSON.parse(Buffer.concat(chunks).toString("utf-8"));
      } catch (e) {
        return res.status(400).json({ error: "Invalid gzip JSON body" });
      }
      next();
    });
    gunzip.on("error", () => res.status(400).json({ error: "gzip decompression failed" }));
  } else {
    jsonParser(req, res, next);
  }
});

const hub = new WsHub();
const latestByUser = new Map<string, any>();
const db = await createDB();

// Rate limiting for WebSocket auth attempts
const authAttempts = new Map<string, { count: number; resetAt: number }>();

function checkAuthRateLimit(ip: string): { allowed: boolean; shouldIncrement: boolean } {
  const now = Date.now();
  const attempt = authAttempts.get(ip) || { count: 0, resetAt: now + 60_000 };

  if (now > attempt.resetAt) {
    // Reset window expired
    authAttempts.set(ip, { count: 0, resetAt: now + 60_000 });
    return { allowed: true, shouldIncrement: true };
  }

  if (attempt.count >= 5) {
    // Rate limited
    return { allowed: false, shouldIncrement: false };
  }

  return { allowed: true, shouldIncrement: true };
}

function recordAuthAttempt(ip: string, success: boolean) {
  if (success) {
    authAttempts.delete(ip);
    return;
  }

  const attempt = authAttempts.get(ip) || { count: 0, resetAt: Date.now() + 60_000 };
  authAttempts.set(ip, { count: attempt.count + 1, resetAt: attempt.resetAt });
}

// Rate limiter for pull endpoint - prevents DoS via large limit queries
const pullLimiter = rateLimit({
  windowMs: 60_000, // 1 minute
  max: 30, // 30 pull requests per minute per IP
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many pull requests" },
});

app.get("/", (_req, res) =>
  res.json({
    name: "RTLS backend",
    health: "/health",
    api: "/v1/locations/latest, POST /v1/locations/batch",
    ws: "ws://<this-host>/v1/ws",
  })
);
app.get("/health", (_req, res) => res.json({ ok: true }));

app.get("/v1/locations/latest", async (req, res) => {
  const userId = String(req.query.userId ?? "");
  if (!userId) return res.status(400).json({ error: "userId is required" });

  try {
    requireAuth(req);
    const p = db ? await latestPointForUser(db, userId) : (latestByUser.get(userId) ?? null);
    res.json({ point: p });
  } catch (e: any) {
    res.status(401).json({ error: e?.message ?? "unauthorized" });
  }
});

app.post("/v1/locations/batch", async (req, res) => {
  try {
    requireAuth(req);
    const batch = UploadBatchSchema.parse(req.body);

    if (db) await insertPoints(db, batch.points);

    for (const p of batch.points) {
      latestByUser.set(p.userId, p);
      const env: WsLocationEnvelope = { type: "location", point: p };
      hub.broadcast(p.userId, JSON.stringify(env));
    }

    const out: LocationUploadResult = {
      acceptedIds: batch.points.map((p) => p.id),
      rejected: [],
      serverTime: Date.now()
    };
    res.json(out);
  } catch (e: any) {
    if (e?.name === "ZodError") {
      const issues = e.issues as Array<{ path: (string | number)[]; message: string; code: string; received?: unknown }>;
      const first = issues[0];
      const path = first?.path?.length ? first.path.join(".") : "body";
      const hint = first?.received === undefined ? " (missing)" : first?.received === null ? " (null)" : "";
      return res.status(400).json({
        error: `Validation failed: ${path} — ${first?.message ?? "invalid"}${hint}`,
        details: issues.map((i) => ({ path: i.path.join("."), message: i.message, received: i.received })),
      });
    }
    const msg = e?.message ?? "error";
    const code = msg.includes("Authorization") ? 401 : 500;
    res.status(code).json({ error: msg });
  }
});

app.get("/v1/locations/pull", pullLimiter, async (req, res) => {
  try {
    requireAuth(req);
    const { userId, cursor, limit } = PullQuerySchema.parse(req.query);

    if (!db) {
      return res.json({ points: [], serverTime: Date.now() });
    }

    const result = await pullPointsForUser(db, userId, cursor, limit);
    res.json({
      points: result.points,
      nextCursor: result.nextCursor,
      serverTime: Date.now(),
    });
  } catch (e: any) {
    if (e?.name === "ZodError") {
      return res.status(400).json({ error: "Invalid pull parameters", details: e.issues });
    }
    const msg = e?.message ?? "error";
    const code = msg.includes("Authorization") ? 401 : 500;
    res.status(code).json({ error: msg });
  }
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/v1/ws" });

// Cleanup on server close
process.on("SIGTERM", () => {
  hub.destroy();
  server.close();
});

process.on("SIGINT", () => {
  hub.destroy();
  server.close();
});

wss.on("connection", (socket, req) => {
  let authenticated = !process.env.JWT_SECRET;
  let socketUserId: string | null = null;

  const sendJson = (obj: any) => {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(obj));
  };

  socket.on("message", async (data) => {
    let raw: any;
    try {
      raw = JSON.parse(data.toString("utf8"));
    } catch {
      sendJson({ type: "error", message: "Invalid JSON" });
      return;
    }

    let msg: any;
    try {
      msg = WsClientMessageSchema.parse(raw);
    } catch {
      // Backward compat: try old subscribe format
      try {
        const old = WsSubscribeSchema.parse(raw);
        msg = { type: "subscribe", userId: old.userId };
      } catch {
        sendJson({ type: "error", message: "Unknown message format" });
        return;
      }
    }

    switch (msg.type) {
      case "auth": {
        // Check rate limit before attempting auth
        const ip = req.socket.remoteAddress || "unknown";
        const rateCheck = checkAuthRateLimit(ip);

        if (!rateCheck.allowed) {
          sendJson({ type: "error", message: "Too many auth attempts" });
          socket.close(4000, "rate limited");
          return;
        }

        if (process.env.JWT_SECRET) {
          try {
            const jwt = await import("jsonwebtoken");
            jwt.default.verify(msg.token, process.env.JWT_SECRET);
            authenticated = true;
            recordAuthAttempt(ip, true);
            sendJson({ type: "auth.ok" });
          } catch {
            recordAuthAttempt(ip, false);
            sendJson({ type: "error", message: "Authentication failed" });
            socket.close(1008, "auth failed");
          }
        } else {
          authenticated = true;
          sendJson({ type: "auth.ok" });
        }
        break;
      }

      case "location.push": {
        if (!authenticated) { sendJson({ type: "error", message: "Not authenticated" }); return; }
        const point = msg.point;
        try {
          if (db) await insertPoints(db, [point]);
          latestByUser.set(point.userId, point);
          hub.broadcast(point.userId, JSON.stringify({ type: "location.update", point }));
          sendJson({ type: "location.ack", reqId: msg.reqId, pointId: point.id, status: "accepted" });
        } catch (e: any) {
          sendJson({ type: "location.ack", reqId: msg.reqId, pointId: point.id, status: "rejected" });
        }
        break;
      }

      case "location.batch": {
        if (!authenticated) { sendJson({ type: "error", message: "Not authenticated" }); return; }
        try {
          if (db) await insertPoints(db, msg.points);
          const acceptedIds: string[] = [];
          for (const p of msg.points) {
            latestByUser.set(p.userId, p);
            hub.broadcast(p.userId, JSON.stringify({ type: "location.update", point: p }));
            acceptedIds.push(p.id);
          }
          sendJson({ type: "location.batch_ack", reqId: msg.reqId, acceptedIds, rejected: [] });
        } catch (e: any) {
          sendJson({ type: "error", message: e?.message ?? "Batch insert failed" });
        }
        break;
      }

      case "subscribe": {
        hub.addSubscriber(msg.userId, socket);
        sendJson({ type: "subscribed", userId: msg.userId });
        break;
      }

      case "unsubscribe": {
        hub.removeSubscriber(msg.userId, socket);
        sendJson({ type: "unsubscribed", userId: msg.userId });
        break;
      }

      case "sync.pull": {
        if (!authenticated) { sendJson({ type: "error", message: "Not authenticated" }); return; }
        if (!db) {
          sendJson({ type: "sync.result", reqId: msg.reqId, points: [], serverTime: Date.now() });
          break;
        }
        try {
          const result = await pullPointsForUser(db, socketUserId ?? "", msg.cursor, msg.limit ?? 100);
          sendJson({
            type: "sync.result",
            reqId: msg.reqId,
            points: result.points,
            cursor: result.nextCursor,
            serverTime: Date.now(),
          });
        } catch (e: any) {
          sendJson({ type: "error", message: e?.message ?? "Pull failed" });
        }
        break;
      }

      case "ping": {
        sendJson({ type: "pong" });
        break;
      }
    }
  });

  socket.on("close", () => {
    hub.removeAllSubscriptions(socket);
  });
});

const port = Number(process.env.PORT ?? 3000);
const host = String(process.env.HOST ?? "0.0.0.0");
server.listen(port, host, () => {
  console.log(`RTLS backend listening on http://${host}:${port}`);
  console.log(`WS endpoint: ws://${host}:${port}/v1/ws`);
  if (host === "0.0.0.0") {
    const lan = getLanIp();
    if (lan) console.log(`LAN URL: http://${lan}:${port}/ (use from phone/other devices on same Wi‑Fi)`);
    else console.log("Tip: use your Mac's LAN IP (e.g. http://192.168.x.x:3000) from your iPhone");
  }
  if (!process.env.JWT_SECRET) console.log("JWT_SECRET not set; auth is disabled");
  if (!process.env.DATABASE_URL) console.log("DATABASE_URL not set; using in-memory latest-point only");
});
