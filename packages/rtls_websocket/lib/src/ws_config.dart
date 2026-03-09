/// Configuration for the real-time WebSocket connection.
class RTLSWebSocketConfig {
  final String baseUrl;
  final String accessToken;
  final bool autoReconnect;
  final Duration reconnectBaseDelay;
  final Duration reconnectMaxDelay;
  final Duration pingInterval;

  const RTLSWebSocketConfig({
    required this.baseUrl,
    this.accessToken = '',
    this.autoReconnect = true,
    this.reconnectBaseDelay = const Duration(seconds: 1),
    this.reconnectMaxDelay = const Duration(seconds: 30),
    this.pingInterval = const Duration(seconds: 30),
  }) : assert(
          baseUrl.startsWith('https://') || baseUrl.startsWith('wss://'),
          'RTLS requires HTTPS/WSS for security. baseUrl must start with "https://" or "wss://" (was: $baseUrl). '
          'In development, you may use a self-signed certificate, but never transmit '
          'location data or auth tokens over plaintext HTTP.',
        );
}
