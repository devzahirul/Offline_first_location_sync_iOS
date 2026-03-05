import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:rtls_flutter/rtls_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../constants/app_constants.dart';
import 'live_map_placeholder_screen.dart';
import 'pending_locations_screen.dart';

/// Semantic keys for testing and accessibility.
abstract class HomeScreenSemantics {
  static const String applySettingsButton = 'home_apply_settings';
  static const String startStopTrackingTile = 'home_start_stop_tracking';
  static const String flushNowTile = 'home_flush_now';
  static const String refreshStatsTile = 'home_refresh_stats';
  static const String trackingStatus = 'home_tracking_status';
  static const String authorizationStatus = 'home_authorization_status';
  static const String pendingQueueValue = 'home_pending_queue';
  static const String eventLogSection = 'home_event_log';
}

/// Single screen matching iOS RTLS Demo: Status, Backend, Actions, Logs.
/// Works on both Android and iOS.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final _baseUrlController = TextEditingController(text: AppConstants.defaultBaseUrl);
  final _userIdController = TextEditingController(text: AppConstants.defaultUserId);
  final _deviceIdController = TextEditingController(text: AppConstants.defaultDeviceId);
  final _tokenController = TextEditingController(text: AppConstants.defaultToken);
  final _subscriberUserIdController = TextEditingController();
  late final TextEditingController _distanceMetersController;
  late final TextEditingController _timeIntervalController;
  late final TextEditingController _flushIntervalController;
  late final TextEditingController _maxBatchAgeController;

  bool _configured = false;
  bool _isTracking = false;
  bool _isLoading = false;
  int _pendingCount = 0;
  String? _oldestPendingAt;
  String _lastSyncMessage = '—';
  String? _lastRecordedText;
  String? _errorMessage;
  final List<String> _logs = [];
  StreamSubscription<Map<dynamic, dynamic>>? _eventSub;

  // Authorization status from plugin events (iOS sends it; Android may not)
  String _authorizationStatus = '—';

  // Tracking policy (stored locally; plugin uses its own defaults)
  bool _useSignificantLocationOnly = false;
  String _policyType = AppConstants.policyTypeDistance;
  double _distanceMeters = AppConstants.defaultDistanceMeters;
  double _timeIntervalSeconds = AppConstants.defaultTimeIntervalSeconds;

  // Batch sync (stored locally)
  int _batchMaxSize = AppConstants.defaultBatchMaxSize;
  double _flushIntervalSeconds = AppConstants.defaultFlushIntervalSeconds;
  double _maxBatchAgeSeconds = AppConstants.defaultMaxBatchAgeSeconds;

  // No-location timeout (iOS parity: 60s without update → stop tracking)
  bool _receivedLocationSinceStart = false;
  Timer? _noLocationTimer;

  // Subscriber (Realtime Watch) – Dart WebSocket to backend
  bool _isSubscriberRunning = false;
  String? _lastSubscribedPoint;
  WebSocketChannel? _wsChannel;
  StreamSubscription? _wsSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _distanceMetersController = TextEditingController(text: _distanceMeters.toString());
    _timeIntervalController = TextEditingController(text: _timeIntervalSeconds.toString());
    _flushIntervalController = TextEditingController(text: _flushIntervalSeconds.toString());
    _maxBatchAgeController = TextEditingController(text: _maxBatchAgeSeconds.toString());
    _eventSub = RTLSync.events.listen(_onSyncEvent);
    _loadStats();
    _tryAutoResume();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _noLocationTimer?.cancel();
    _eventSub?.cancel();
    _wsSub?.cancel();
    _wsChannel?.sink.close();
    _baseUrlController.dispose();
    _userIdController.dispose();
    _deviceIdController.dispose();
    _tokenController.dispose();
    _subscriberUserIdController.dispose();
    _distanceMetersController.dispose();
    _timeIntervalController.dispose();
    _flushIntervalController.dispose();
    _maxBatchAgeController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!_configured) return;
    if (state == AppLifecycleState.resumed) {
      _log('Foreground: flushing pending points');
      RTLSync.flushNow().then((_) => _loadStats()).catchError((_) {});
    } else if (state == AppLifecycleState.paused) {
      _log('Background: flushing pending points');
      RTLSync.flushNow().catchError((_) {});
    }
  }

  void _subscriberStart() {
    final baseUrl = _baseUrlController.text.trim();
    final userId = _subscriberUserIdController.text.trim();
    if (baseUrl.isEmpty || userId.isEmpty) {
      _showSnack('Enter Base URL and Watch userId');
      return;
    }
    String wsUrl = baseUrl.replaceFirst(RegExp(r'^http'), 'ws');
    if (!wsUrl.endsWith('/')) wsUrl += '/';
    wsUrl += 'v1/ws';
    try {
      final uri = Uri.parse(wsUrl);
      _wsChannel = WebSocketChannel.connect(uri);
      _wsSub = _wsChannel!.stream.listen(
        (data) {
          if (!mounted) return;
          try {
            final map = jsonDecode(data is String ? data : utf8.decode(data as List<int>)) as Map<String, dynamic>;
            final type = map['type'] as String?;
            if (type == 'location') {
              final point = map['point'] as Map<String, dynamic>?;
              if (point != null) {
                final lat = point['lat'];
                final lng = point['lng'];
                if (lat != null && lng != null) {
                  setState(() => _lastSubscribedPoint = '${lat.toStringAsFixed(5)}, ${lng.toStringAsFixed(5)}');
                }
              }
            }
          } catch (_) {}
        },
        onError: (e) {
          if (mounted) {
            setState(() => _lastSubscribedPoint = 'Error: $e');
          }
        },
        onDone: () {
          if (mounted) {
            setState(() {
              _isSubscriberRunning = false;
              _wsChannel = null;
              _wsSub = null;
            });
          }
        },
        cancelOnError: false,
      );
      _wsChannel!.sink.add(jsonEncode({'type': 'subscribe', 'userId': userId}));
      setState(() {
        _isSubscriberRunning = true;
        _lastSubscribedPoint = null;
      });
      _log('Subscriber started for $userId');
    } catch (e) {
      _showSnack('WebSocket failed: $e');
    }
  }

  void _subscriberStop() {
    _wsSub?.cancel();
    _wsChannel?.sink.close();
    _wsChannel = null;
    _wsSub = null;
    setState(() {
      _isSubscriberRunning = false;
    });
    _log('Subscriber stopped');
  }

  void _log(String msg) {
    if (!mounted) return;
    setState(() {
      _logs.insert(0, '${DateTime.now().toIso8601String().substring(11, 19)} $msg');
      if (_logs.length > AppConstants.maxEventLogEntries) _logs.removeLast();
    });
  }

  void _onSyncEvent(Map<dynamic, dynamic> e) {
    if (!mounted) return;
    final type = e['type'] as String? ?? 'unknown';
    setState(() {
      _errorMessage = null;
      if (type == 'trackingStarted') {
        _isTracking = true;
        _receivedLocationSinceStart = false;
        _lastSyncMessage = '—';
        _log('Tracking started');
        _scheduleNoLocationCheck();
      } else if (type == 'trackingStopped') {
        _isTracking = false;
        _receivedLocationSinceStart = false;
        _noLocationTimer?.cancel();
        _noLocationTimer = null;
        _log('Tracking stopped');
      } else if (type == 'recorded') {
        _receivedLocationSinceStart = true;
        _noLocationTimer?.cancel();
        _noLocationTimer = null;
        final point = e['point'];
        if (point is Map && point['lat'] != null && point['lng'] != null) {
          final lat = (point['lat'] is num) ? (point['lat'] as num).toStringAsFixed(5) : point['lat'].toString();
          final lng = (point['lng'] is num) ? (point['lng'] as num).toStringAsFixed(5) : point['lng'].toString();
          final acc = point['horizontalAccuracy'];
          final accStr = acc is num ? ' acc=${acc.toStringAsFixed(0)}m' : '';
          final ms = point['recordedAtMs'] ?? point['recordedAt'];
          final time = ms != null ? _formatMs(ms is num ? ms.toInt() : 0) : '—';
          _lastRecordedText = '$lat, $lng$accStr · $time';
        }
        _log('Recorded ${_lastRecordedText ?? ""}');
      } else if (type == 'syncEvent') {
        final ev = e['event'];
        if (ev == 'uploadSucceeded') {
          final accepted = e['accepted'] ?? 0;
          final rejected = e['rejected'] ?? 0;
          _lastSyncMessage = 'uploaded accepted=$accepted rejected=$rejected';
          _log('Sync: $_lastSyncMessage');
        } else if (ev == 'uploadFailed') {
          _lastSyncMessage = 'upload failed: ${e['message'] ?? ""}';
          _log('Sync: $_lastSyncMessage');
        }
      } else if (type == 'error') {
        _errorMessage = e['message']?.toString() ?? 'Error';
        _log('Error: $_errorMessage');
      } else if (type == 'authorizationChanged') {
        final auth = e['authorization']?.toString();
        if (auth != null && auth.isNotEmpty) {
          _authorizationStatus = auth;
          _log('Auth: $_authorizationStatus');
        }
      }
    });
    _loadStats();
  }

  String _formatMs(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}:${d.second.toString().padLeft(2, '0')}';
  }

  /// iOS parity: if no location received within 60s of starting, stop tracking and show error.
  void _scheduleNoLocationCheck() {
    if (_useSignificantLocationOnly) return;
    _noLocationTimer?.cancel();
    _noLocationTimer = Timer(const Duration(seconds: 60), () {
      if (!mounted || !_isTracking || _receivedLocationSinceStart) return;
      _stopTracking();
      setState(() {
        _errorMessage = 'No location received. Check Always permission and try again.';
      });
      _log('Stopped: no location updates in 60s');
    });
  }

  /// iOS parity: persist wasTracking flag and auto-resume on relaunch.
  Future<void> _persistWasTracking(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('rtls_was_tracking', value);
  }

  Future<void> _tryAutoResume() async {
    final prefs = await SharedPreferences.getInstance();
    if (prefs.getBool('rtls_was_tracking') != true) return;
    if (!mounted) return;
    _log('Resuming tracking (was on before app closed)');
    await _configure();
    if (_configured) {
      await _requestPermission();
      await _startTracking();
    }
  }

  Future<void> _loadStats() async {
    if (!mounted) return;
    try {
      final stats = await RTLSync.getStats();
      if (!mounted) return;
      setState(() {
        _pendingCount = stats.pendingCount;
        _oldestPendingAt = stats.oldestPendingRecordedAtMs != null
            ? _formatMs(stats.oldestPendingRecordedAtMs!)
            : null;
      });
    } catch (_) {}
  }

  Future<void> _configure() async {
    final baseUrl = _baseUrlController.text.trim();
    final userId = _userIdController.text.trim();
    final deviceId = _deviceIdController.text.trim();
    final token = _tokenController.text.trim();
    if (baseUrl.isEmpty || userId.isEmpty || deviceId.isEmpty || token.isEmpty) {
      _showSnack('Please fill Base URL, User ID, Device ID, and Token');
      return;
    }
    if (_isTracking) {
      await _stopTracking();
      if (!mounted) return;
    }
    // Use current field values for batch params (in case user edited without onChanged)
    final flushSec = double.tryParse(_flushIntervalController.text.trim()) ?? _flushIntervalSeconds;
    final maxAgeSec = double.tryParse(_maxBatchAgeController.text.trim()) ?? _maxBatchAgeSeconds;
    setState(() => _isLoading = true);
    _setError(null);
    try {
      await RTLSync.configure(RTLSyncConfig(
        baseUrl: baseUrl,
        userId: userId,
        deviceId: deviceId,
        accessToken: token,
        locationIntervalSeconds: _policyType == AppConstants.policyTypeTime ? _timeIntervalSeconds : null,
        locationDistanceMeters: _policyType == AppConstants.policyTypeDistance ? _distanceMeters : null,
        useSignificantLocationOnly: _useSignificantLocationOnly,
        batchMaxSize: _batchMaxSize,
        flushIntervalSeconds: flushSec,
        maxBatchAgeSeconds: maxAgeSec,
      ));
      if (!mounted) return;
      setState(() {
        _configured = true;
        _isLoading = false;
      });
      _log('Configured: $baseUrl');
      await _loadStats();
      _showSnack('Settings applied');
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _setError(e.toString());
      _showSnack('Configure failed: $e');
    }
  }

  void _setError(String? msg) {
    setState(() => _errorMessage = msg?.isNotEmpty == true ? msg : null);
  }

  Future<void> _requestPermission() async {
    setState(() => _isLoading = true);
    _setError(null);
    try {
      await RTLSync.requestAlwaysAuthorization();
      if (!mounted) return;
      setState(() => _isLoading = false);
      _log('Requested always authorization');
      _showSnack('Permission requested');
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _setError(e.toString());
      _showSnack('Permission: $e');
    }
  }

  Future<void> _startTracking() async {
    setState(() => _isLoading = true);
    _setError(null);
    try {
      await RTLSync.startTracking();
      _persistWasTracking(true);
      if (!mounted) return;
      setState(() => _isLoading = false);
      _showSnack('Tracking started');
      await _loadStats();
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _setError(e.toString());
      final msg = e.toString().contains('LOCATION_PERMISSION_DENIED')
          ? 'Grant location first: tap "Always" above, then Start Tracking.'
          : 'Failed: $e';
      _showSnack(msg);
    }
  }

  Future<void> _stopTracking() async {
    setState(() => _isLoading = true);
    try {
      await RTLSync.stopTracking();
      _persistWasTracking(false);
      if (!mounted) return;
      setState(() => _isLoading = false);
      _showSnack('Tracking stopped');
      await _loadStats();
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _showSnack('Stop failed: $e');
    }
  }

  Future<void> _toggleTracking() async {
    if (_isTracking) {
      await _stopTracking();
    } else {
      await _requestPermission();
      await _startTracking();
    }
  }

  Future<void> _flushNow() async {
    setState(() => _isLoading = true);
    try {
      await RTLSync.flushNow();
      if (!mounted) return;
      setState(() => _isLoading = false);
      await _loadStats();
      _showSnack('Flush triggered');
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _showSnack('Flush failed: $e');
    }
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('RTLS Demo'),
        elevation: 0,
        scrolledUnderElevation: 2,
        actions: [
          if (_isLoading)
            const Center(
              child: Padding(
                padding: EdgeInsets.only(right: 16),
                child: SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _loadStats,
        child: ListView(
          padding: const EdgeInsets.only(bottom: 32),
          children: [
            _sectionHeader(theme, 'Status'),
            _sectionCard(
              theme,
              [
                Semantics(
                  label: 'Authorization status',
                  value: _authorizationStatus,
                  container: true,
                  child: _keyValueRow(theme, 'Authorization', _authorizationStatus),
                ),
                _divider(theme),
                Semantics(
                  key: const Key(HomeScreenSemantics.trackingStatus),
                  label: 'Tracking status',
                  value: _isTracking ? 'Running' : 'Stopped',
                  container: true,
                  child: _keyValueRow(theme, 'Tracking', _isTracking ? 'Running' : 'Stopped'),
                ),
                if (_isTracking && _useSignificantLocationOnly)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                    child: Text(
                      'Updates only when you move ~500 m',
                      style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ),
                _divider(theme),
                _keyValueRow(theme, 'Last Sync', _lastSyncMessage),
                _divider(theme),
                Semantics(
                  key: const Key(HomeScreenSemantics.pendingQueueValue),
                  label: 'Pending queue count',
                  value: _configured ? '$_pendingCount' : 'Not configured',
                  container: true,
                  child: _keyValueRow(theme, 'Pending Queue', _configured ? '$_pendingCount' : '…'),
                ),
                if (_oldestPendingAt != null) ...[_divider(theme), _keyValueRow(theme, 'Oldest Pending', _oldestPendingAt!)],
                if (_lastRecordedText != null) ...[_divider(theme), _keyValueRow(theme, 'Last Recorded', _lastRecordedText!)],
                if (_errorMessage != null && _errorMessage!.isNotEmpty) ...[
                  _divider(theme),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                    child: Text(_errorMessage!, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
                  ),
                ],
              ],
            ),
            _sectionHeader(theme, 'Backend'),
            _sectionCard(
              theme,
              [
                Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: TextField(
                controller: _baseUrlController,
                decoration: const InputDecoration(
                  labelText: 'Base URL',
                  hintText: 'http://192.168.0.102:3000',
                ),
                keyboardType: TextInputType.url,
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: TextField(
                controller: _tokenController,
                decoration: const InputDecoration(labelText: 'Access Token (optional)'),
                obscureText: true,
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: TextField(
                controller: _userIdController,
                decoration: const InputDecoration(labelText: 'User ID'),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: TextField(
                controller: _deviceIdController,
                decoration: const InputDecoration(labelText: 'Device ID'),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: Semantics(
                key: const Key(HomeScreenSemantics.applySettingsButton),
                label: 'Apply settings and rebuild sync client',
                button: true,
                child: FilledButton(
                  onPressed: _isLoading ? null : _configure,
                  child: const Text('Apply Settings'),
                ),
              ),
            ),
            ],
            ),
            _sectionHeader(theme, 'Tracking Policy'),
            _sectionCard(
              theme,
              [
                SwitchListTile(
                  title: const Text('Significant location only'),
                  subtitle: Text('~500 m updates when moving', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  value: _useSignificantLocationOnly,
                  onChanged: (v) => setState(() => _useSignificantLocationOnly = v),
                ),
                _divider(theme),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(
                    children: [
                      Text('Mode', style: theme.textTheme.bodyMedium),
                      const SizedBox(width: 16),
                      SegmentedButton<String>(
                        segments: const [
                          ButtonSegment(value: AppConstants.policyTypeDistance, label: Text('Distance')),
                          ButtonSegment(value: AppConstants.policyTypeTime, label: Text('Time')),
                        ],
                        selected: {_policyType},
                        onSelectionChanged: (s) => setState(() => _policyType = s.first),
                      ),
                    ],
                  ),
                ),
                if (_policyType == AppConstants.policyTypeDistance)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: TextField(
                      controller: _distanceMetersController,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: 'Meters'),
                      onChanged: (v) => setState(() => _distanceMeters = double.tryParse(v) ?? _distanceMeters),
                    ),
                  )
                else
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: TextField(
                      controller: _timeIntervalController,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: 'Interval (s)'),
                      onChanged: (v) => setState(() => _timeIntervalSeconds = double.tryParse(v) ?? _timeIntervalSeconds),
                    ),
                  ),
              ],
            ),
            _sectionHeader(theme, 'Batch Sync'),
            _sectionCard(
              theme,
              [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                  child: Text(
                    'Upload runs when queue reaches max batch, or oldest point age, or every flush interval. Flush Now sends immediately.',
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                ),
                _divider(theme),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(
                    children: [
                      Text('Max batch', style: theme.textTheme.bodyMedium),
                      const Spacer(),
                      IconButton.filledTonal(icon: const Icon(Icons.remove), onPressed: () => setState(() => _batchMaxSize = (_batchMaxSize - 1).clamp(1, 500))),
                      Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Text('$_batchMaxSize', style: theme.textTheme.titleSmall)),
                      IconButton.filledTonal(icon: const Icon(Icons.add), onPressed: () => setState(() => _batchMaxSize = (_batchMaxSize + 1).clamp(1, 500))),
                    ],
                  ),
                ),
                _divider(theme),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: TextField(
                    controller: _flushIntervalController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Flush interval (s)'),
                    onChanged: (v) => setState(() => _flushIntervalSeconds = double.tryParse(v) ?? _flushIntervalSeconds),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: TextField(
                    controller: _maxBatchAgeController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Max batch age (s)'),
                    onChanged: (v) => setState(() => _maxBatchAgeSeconds = double.tryParse(v) ?? _maxBatchAgeSeconds),
                  ),
                ),
              ],
            ),
            _sectionHeader(theme, 'Actions'),
            _sectionCard(
              theme,
              [
            ListTile(
              title: const Text('When In Use'),
              subtitle: Text('Request when-in-use (plugin uses Always)', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              onTap: _isLoading ? null : () => _showSnack('Use Always for background'),
            ),
            ListTile(
              title: const Text('Always'),
              subtitle: Text('Request background location', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              onTap: _isLoading ? null : _requestPermission,
            ),
            Semantics(
              key: const Key(HomeScreenSemantics.startStopTrackingTile),
              label: _isTracking ? 'Stop tracking' : 'Start tracking',
              button: true,
              enabled: _configured && !_isLoading,
              child: ListTile(
                title: Text(_isTracking ? 'Stop Tracking' : 'Start Tracking'),
                trailing: _isTracking ? Icon(Icons.stop_circle, color: theme.colorScheme.error) : Icon(Icons.play_circle, color: theme.colorScheme.primary),
                onTap: _configured && !_isLoading ? _toggleTracking : null,
              ),
            ),
            Semantics(
              key: const Key(HomeScreenSemantics.flushNowTile),
              label: 'Flush now. Upload pending points immediately.',
              button: true,
              enabled: _configured && !_isLoading,
              child: ListTile(
                title: const Text('Flush Now'),
                subtitle: Text('Upload pending points immediately', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                onTap: _configured && !_isLoading ? _flushNow : null,
              ),
            ),
            Semantics(
              key: const Key(HomeScreenSemantics.refreshStatsTile),
              label: 'Refresh stats',
              button: true,
              child: ListTile(
                title: const Text('Refresh Stats'),
                onTap: _loadStats,
              ),
            ),
            ],
            ),
            _sectionHeader(theme, 'Map'),
            _sectionCard(
              theme,
              [
                ListTile(
                  title: const Text('Live Map'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const LiveMapPlaceholderScreen())),
                ),
                _divider(theme),
                ListTile(
                  title: Text('Pending locations ($_pendingCount)'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(
                    builder: (_) => PendingLocationsScreen(pendingCount: _pendingCount, onRefresh: _loadStats),
                  )),
                ),
              ],
            ),
            _sectionHeader(theme, 'Subscriber (Realtime Watch)'),
            _sectionCard(
              theme,
              [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: TextField(
                    controller: _subscriberUserIdController,
                    decoration: const InputDecoration(labelText: 'Watch userId'),
                    enabled: !_isSubscriberRunning,
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                  child: Row(
                    children: [
                      FilledButton(
                        onPressed: _isSubscriberRunning ? null : _subscriberStart,
                        child: const Text('Start'),
                      ),
                      const SizedBox(width: 8),
                      OutlinedButton(
                        onPressed: _isSubscriberRunning ? _subscriberStop : null,
                        child: const Text('Stop'),
                      ),
                    ],
                  ),
                ),
                _keyValueRow(theme, 'Last point', _lastSubscribedPoint ?? '—'),
              ],
            ),
            _sectionHeader(theme, 'Logs'),
            Semantics(
              key: const Key(HomeScreenSemantics.eventLogSection),
              label: 'Event log',
              container: true,
              child: _sectionCard(
                theme,
                [
                  if (_logs.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text('No logs yet.', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                    )
                  else
                    ..._logs.take(50).map((line) => SelectableText(
                      line,
                      style: theme.textTheme.bodySmall,
                      maxLines: 3,
                    ).padding(const EdgeInsets.symmetric(horizontal: 16, vertical: 4))),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionCard(ThemeData theme, List<Widget> children) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: theme.colorScheme.outlineVariant.withValues(alpha: 0.5))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: children),
    );
  }

  Widget _divider(ThemeData theme) {
    return Divider(height: 1, indent: 16, endIndent: 16, color: theme.colorScheme.outlineVariant.withValues(alpha: 0.5));
  }

  Widget _sectionHeader(ThemeData theme, String title) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
      child: Text(
        title.toUpperCase(),
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.primary,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.5,
        ),
      ),
    );
  }

  Widget _keyValueRow(ThemeData theme, String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: theme.textTheme.bodyMedium),
          const Spacer(),
          Flexible(
            child: Text(
              value,
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              textAlign: TextAlign.right,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

extension _Pad on Widget {
  Widget padding(EdgeInsetsGeometry e) => Padding(padding: e, child: this);
}
