import WebSocket from "ws";

/**
 * WebSocket hub for managing subscriptions and broadcasting.
 * Includes periodic cleanup to prevent memory leaks from stale connections.
 */
export class WsHub {
  private subs = new Map<string, Set<WebSocket>>();
  private socketUserIds = new Map<WebSocket, Set<string>>();
  private cleanupInterval: NodeJS.Timeout;

  constructor() {
    // Run cleanup every 60 seconds to remove stale sockets
    this.cleanupInterval = setInterval(() => this.cleanup(), 60_000);
  }

  /**
   * Cleanup stale WebSocket connections that didn't fire close events.
   * Prevents memory leaks from disconnected clients.
   */
  private cleanup() {
    const toRemoveSockets = new Set<WebSocket>();

    for (const [userId, sockets] of this.subs.entries()) {
      for (const ws of sockets) {
        if (ws.readyState !== WebSocket.OPEN) {
          toRemoveSockets.add(ws);
        }
      }
    }

    for (const ws of toRemoveSockets) {
      this.removeAllSubscriptions(ws);
    }
  }

  addSubscriber(userId: string, socket: WebSocket) {
    const set = this.subs.get(userId) ?? new Set<WebSocket>();
    set.add(socket);
    this.subs.set(userId, set);

    const userIds = this.socketUserIds.get(socket) ?? new Set<string>();
    userIds.add(userId);
    this.socketUserIds.set(socket, userIds);
  }

  removeSubscriber(userId: string, socket: WebSocket) {
    const set = this.subs.get(userId);
    if (!set) return;
    set.delete(socket);
    if (set.size === 0) this.subs.delete(userId);

    const userIds = this.socketUserIds.get(socket);
    if (userIds) {
      userIds.delete(userId);
      if (userIds.size === 0) this.socketUserIds.delete(socket);
    }
  }

  removeAllSubscriptions(socket: WebSocket) {
    const userIds = this.socketUserIds.get(socket);
    if (!userIds) return;
    for (const uid of userIds) {
      const set = this.subs.get(uid);
      if (set) {
        set.delete(socket);
        if (set.size === 0) this.subs.delete(uid);
      }
    }
    this.socketUserIds.delete(socket);
  }

  broadcast(userId: string, data: string) {
    const set = this.subs.get(userId);
    if (!set) return;
    for (const ws of set) {
      if (ws.readyState === WebSocket.OPEN) ws.send(data);
    }
  }

  /**
   * Cleanup resources when shutting down.
   */
  destroy() {
    clearInterval(this.cleanupInterval);
  }
}
