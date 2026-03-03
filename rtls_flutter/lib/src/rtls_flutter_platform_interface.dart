import 'dart:async';
import 'package:flutter/services.dart';

const _channel = MethodChannel('com.rtls.flutter/rtls');
const _eventChannel = EventChannel('com.rtls.flutter/rtls_events');

class RTLSyncConfig {
  final String baseUrl;
  final String userId;
  final String deviceId;
  final String accessToken;
  /// Time-based: update interval in seconds (e.g. 360 for 6 min). Applied on Android; iOS uses RTLSyncKit defaults.
  final double? locationIntervalSeconds;
  /// Distance-based: min distance in meters between updates. If set with interval, distance is used.
  final double? locationDistanceMeters;
  /// If true, use ~500 m / less frequent updates (significant-change style).
  final bool useSignificantLocationOnly;
  /// Max points per upload batch (Android KMP; iOS uses RTLSyncKit BatchingPolicy). Default 50.
  final int? batchMaxSize;
  /// Flush interval in seconds (Android KMP). Default 10.
  final double? flushIntervalSeconds;
  /// Flush when oldest pending point is older than this many seconds (Android KMP). Default 60.
  final double? maxBatchAgeSeconds;

  const RTLSyncConfig({
    required this.baseUrl,
    required this.userId,
    required this.deviceId,
    required this.accessToken,
    this.locationIntervalSeconds,
    this.locationDistanceMeters,
    this.useSignificantLocationOnly = false,
    this.batchMaxSize,
    this.flushIntervalSeconds,
    this.maxBatchAgeSeconds,
  });

  Map<String, dynamic> toMap() => {
        'baseUrl': baseUrl,
        'userId': userId,
        'deviceId': deviceId,
        'accessToken': accessToken,
        'locationIntervalSeconds': locationIntervalSeconds,
        'locationDistanceMeters': locationDistanceMeters,
        'useSignificantLocationOnly': useSignificantLocationOnly,
        'batchMaxSize': batchMaxSize,
        'flushIntervalSeconds': flushIntervalSeconds,
        'maxBatchAgeSeconds': maxBatchAgeSeconds,
      };
}

class RTLSStats {
  final int pendingCount;
  final int? oldestPendingRecordedAtMs;

  RTLSStats({required this.pendingCount, this.oldestPendingRecordedAtMs});

  factory RTLSStats.fromMap(Map<dynamic, dynamic> m) {
    return RTLSStats(
      pendingCount: (m['pendingCount'] as num?)?.toInt() ?? 0,
      oldestPendingRecordedAtMs: m['oldestPendingRecordedAtMs'] != null
          ? (m['oldestPendingRecordedAtMs'] as num).toInt()
          : null,
    );
  }
}

class RTLSync {
  static Stream<Map<dynamic, dynamic>>? _eventStream;

  static Future<void> configure(RTLSyncConfig config) async {
    await _channel.invokeMethod('configure', config.toMap());
  }

  static Future<void> startTracking() async {
    await _channel.invokeMethod('startTracking');
  }

  static Future<void> stopTracking() async {
    await _channel.invokeMethod('stopTracking');
  }

  static Future<void> requestAlwaysAuthorization() async {
    await _channel.invokeMethod('requestAlwaysAuthorization');
  }

  static Future<RTLSStats> getStats() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getStats');
    return RTLSStats.fromMap(result ?? {});
  }

  static Future<void> flushNow() async {
    await _channel.invokeMethod('flushNow');
  }

  static Stream<Map<dynamic, dynamic>> get events {
    _eventStream ??= _eventChannel.receiveBroadcastStream().map((e) => e as Map<dynamic, dynamic>);
    return _eventStream!;
  }
}
