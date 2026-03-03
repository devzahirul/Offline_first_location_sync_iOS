/// Default values and app-wide constants for the example app.
class AppConstants {
  AppConstants._();

  static const String defaultBaseUrl = 'http://localhost:3000';
  static const String defaultUserId = 'flutter-user-1';
  static const String defaultDeviceId = 'flutter-device-1';
  static const String defaultToken = 'demo-token';

  static const int maxEventLogEntries = 100;

  // Tracking policy (iOS parity; plugin may use defaults)
  static const String policyTypeDistance = 'distance';
  static const String policyTypeTime = 'time';
  static const double defaultDistanceMeters = 25;
  static const double defaultTimeIntervalSeconds = 5;

  // Batch sync (iOS parity)
  static const int defaultBatchMaxSize = 50;
  static const double defaultFlushIntervalSeconds = 10;
  static const double defaultMaxBatchAgeSeconds = 60;
}
