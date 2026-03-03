/**
 * rtls-react-native: React Native bridge to RTLSyncKit (iOS).
 * Requires the host app to link the Swift package in Xcode.
 */

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { RTLSyncModule } = NativeModules;

export interface RTLSConfigureConfig {
  baseURL: string;
  userId: string;
  deviceId: string;
  accessToken: string;
}

export interface RTLSStats {
  pendingCount: number;
  oldestPendingRecordedAt: number | null;
}

export interface RTLSRecordedPoint {
  id: string;
  userId: string;
  deviceId: string;
  recordedAt: number;
  lat: number;
  lng: number;
  horizontalAccuracy?: number;
  verticalAccuracy?: number;
  altitude?: number;
  speed?: number;
  course?: number;
}

export type RTLSyncEventType = 'uploadSucceeded' | 'uploadFailed';

export interface RTLSyncEventPayload {
  type: RTLSyncEventType;
  accepted?: number;
  rejected?: number;
  message?: string;
}

export type RTLSAuthorizationStatus =
  | 'notDetermined'
  | 'restricted'
  | 'denied'
  | 'authorizedWhenInUse'
  | 'authorizedAlways';

export const RTLSyncEvents = {
  RECORDED: 'rtls_recorded',
  SYNC_EVENT: 'rtls_syncEvent',
  ERROR: 'rtls_error',
  AUTHORIZATION_CHANGED: 'rtls_authorizationChanged',
  TRACKING_STARTED: 'rtls_trackingStarted',
  TRACKING_STOPPED: 'rtls_trackingStopped',
} as const;

const eventEmitter =
  Platform.OS === 'ios' && RTLSyncModule
    ? new NativeEventEmitter(RTLSyncModule)
    : null;

function requireNativeModule(): typeof RTLSyncModule {
  if (!RTLSyncModule) {
    throw new Error(
      'rtls-react-native: Native module RTLSyncModule is not available. On iOS, link the Swift package (RTLSyncKit) in Xcode.'
    );
  }
  return RTLSyncModule;
}

/**
 * Configure the sync client. Must be called before startTracking.
 * Replaces any existing client.
 */
export function configure(config: RTLSConfigureConfig): Promise<void> {
  return requireNativeModule().configure(config);
}

/**
 * Request "always" location authorization (required for background updates).
 */
export function requestAlwaysAuthorization(): Promise<void> {
  return requireNativeModule().requestAlwaysAuthorization();
}

/**
 * Start recording and syncing location.
 */
export function startTracking(): Promise<void> {
  return requireNativeModule().startTracking();
}

/**
 * Stop recording and syncing.
 */
export function stopTracking(): Promise<void> {
  return requireNativeModule().stopTracking();
}

/**
 * Get current pending count and oldest pending timestamp (ms since epoch, or null).
 */
export function getStats(): Promise<RTLSStats> {
  return requireNativeModule().getStats().then((raw: { pendingCount: number; oldestPendingRecordedAt: number | null }) => ({
    pendingCount: raw.pendingCount,
    oldestPendingRecordedAt: raw.oldestPendingRecordedAt ?? null,
  }));
}

/**
 * Trigger an immediate flush of pending points to the server.
 */
export function flushNow(): Promise<void> {
  return requireNativeModule().flushNow();
}

/**
 * Subscribe to sync events. Returns a subscription with .remove().
 * Only available on iOS when the native module is linked.
 */
export function addEventListener(
  event: keyof typeof RTLSyncEvents,
  listener: (payload: unknown) => void
): { remove: () => void } {
  const name = RTLSyncEvents[event];
  if (!eventEmitter || !name) {
    return { remove: () => {} };
  }
  const sub = eventEmitter.addListener(name, listener);
  return { remove: () => sub.remove() };
}

export default {
  configure,
  requestAlwaysAuthorization,
  startTracking,
  stopTracking,
  getStats,
  flushNow,
  addEventListener,
  RTLSyncEvents,
};
