/**
 * RTLS Mobile Example — offline-first location sync via rtls-react-native (iOS).
 * Configure base URL, userId, deviceId, and token; start/stop tracking; see stats and events.
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import RTLSync, {
  RTLSyncEvents,
  addEventListener,
  type RTLSRecordedPoint,
  type RTLSyncEventPayload,
} from 'rtls-react-native';

const defaultBaseURL = 'http://localhost:3000';

function App(): React.JSX.Element {
  const [baseURL, setBaseURL] = useState(defaultBaseURL);
  const [userId, setUserId] = useState('rn-user-1');
  const [deviceId, setDeviceId] = useState('rn-device-1');
  const [accessToken, setAccessToken] = useState('demo-token');
  const [configured, setConfigured] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pendingCount, setPendingCount] = useState<number>(0);
  const [lastEvent, setLastEvent] = useState<string>('—');
  const [lastRecorded, setLastRecorded] = useState<RTLSRecordedPoint | null>(null);

  const doConfigure = useCallback(async () => {
    if (Platform.OS !== 'ios') {
      setError('RTLS native module is iOS only.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      await RTLSync.configure({
        baseURL: baseURL.trim(),
        userId: userId.trim(),
        deviceId: deviceId.trim(),
        accessToken: accessToken.trim(),
      });
      setConfigured(true);
      const stats = await RTLSync.getStats();
      setPendingCount(stats.pendingCount);
      setLastEvent('Configured');
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [baseURL, userId, deviceId, accessToken]);

  const refreshStats = useCallback(async () => {
    if (!configured) return;
    try {
      const stats = await RTLSync.getStats();
      setPendingCount(stats.pendingCount);
    } catch (_) {}
  }, [configured]);

  useEffect(() => {
    if (!configured || Platform.OS !== 'ios') return;
    const subs = [
      addEventListener('RECORDED', (point: RTLSRecordedPoint) => {
        setLastRecorded(point);
        setLastEvent(`Recorded: ${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`);
        refreshStats();
      }),
      addEventListener('SYNC_EVENT', (e: RTLSyncEventPayload) => {
        setLastEvent(
          e.type === 'uploadSucceeded'
            ? `Upload OK (accepted=${e.accepted ?? 0})`
            : `Upload failed: ${e.message ?? 'unknown'}`
        );
        refreshStats();
      }),
      addEventListener('ERROR', (payload: { message?: string }) => {
        setLastEvent(`Error: ${payload?.message ?? 'unknown'}`);
      }),
      addEventListener('TRACKING_STARTED', () => {
        setLastEvent('Tracking started');
      }),
      addEventListener('TRACKING_STOPPED', () => {
        setLastEvent('Tracking stopped');
        refreshStats();
      }),
    ];
    return () => {
      subs.forEach((s) => s.remove());
    };
  }, [configured, refreshStats]);

  const startTracking = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      await RTLSync.startTracking();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const stopTracking = useCallback(async () => {
    setLoading(true);
    try {
      await RTLSync.stopTracking();
    } finally {
      setLoading(false);
    }
  }, []);

  const requestAuth = useCallback(async () => {
    setLoading(true);
    try {
      await RTLSync.requestAlwaysAuthorization();
      setLastEvent('Requested always authorization');
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const flushNow = useCallback(async () => {
    setLoading(true);
    try {
      await RTLSync.flushNow();
      setLastEvent('Flush triggered');
      refreshStats();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [refreshStats]);

  if (Platform.OS !== 'ios') {
    return (
      <SafeAreaView style={styles.container}>
        <Text style={styles.unsupported}>
          This example uses the RTLSyncKit native module, which is implemented for iOS only. Run on an iOS device or simulator.
        </Text>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.title}>RTLS Mobile Example</Text>
        <Text style={styles.subtitle}>Offline-first location sync (rtls-react-native)</Text>

        <View style={styles.section}>
          <Text style={styles.label}>Base URL</Text>
          <TextInput
            style={styles.input}
            value={baseURL}
            onChangeText={setBaseURL}
            placeholder="http://localhost:3000"
            autoCapitalize="none"
            editable={!configured}
          />
          <Text style={styles.label}>User ID</Text>
          <TextInput
            style={styles.input}
            value={userId}
            onChangeText={setUserId}
            placeholder="user-1"
            editable={!configured}
          />
          <Text style={styles.label}>Device ID</Text>
          <TextInput
            style={styles.input}
            value={deviceId}
            onChangeText={setDeviceId}
            placeholder="device-1"
            editable={!configured}
          />
          <Text style={styles.label}>Access token</Text>
          <TextInput
            style={styles.input}
            value={accessToken}
            onChangeText={setAccessToken}
            placeholder="JWT or token"
            secureTextEntry
            editable={!configured}
          />
          <TouchableOpacity
            style={[styles.button, configured && styles.buttonDisabled]}
            onPress={doConfigure}
            disabled={loading || configured}
          >
            {loading ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Configure</Text>}
          </TouchableOpacity>
          {configured && (
            <Text style={styles.hint}>Configured. Restart the app to change config.</Text>
          )}
        </View>

        {error ? <Text style={styles.error}>{error}</Text> : null}

        {configured && (
          <>
            <View style={styles.section}>
              <Text style={styles.stats}>Pending: {pendingCount}</Text>
              <Text style={styles.lastEvent}>Last event: {lastEvent}</Text>
              {lastRecorded && (
                <Text style={styles.small}>
                  Last point: {lastRecorded.lat.toFixed(5)}, {lastRecorded.lng.toFixed(5)} @ {new Date(lastRecorded.recordedAt).toLocaleTimeString()}
                </Text>
              )}
            </View>
            <View style={styles.row}>
              <TouchableOpacity style={styles.buttonSmall} onPress={requestAuth} disabled={loading}>
                <Text style={styles.buttonText}>Request always auth</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.buttonSmall} onPress={flushNow} disabled={loading}>
                <Text style={styles.buttonText}>Flush now</Text>
              </TouchableOpacity>
            </View>
            <View style={styles.row}>
              <TouchableOpacity style={[styles.button, styles.buttonGreen]} onPress={startTracking} disabled={loading}>
                <Text style={styles.buttonText}>Start tracking</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[styles.button, styles.buttonRed]} onPress={stopTracking} disabled={loading}>
                <Text style={styles.buttonText}>Stop tracking</Text>
              </TouchableOpacity>
            </View>
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  scroll: {
    padding: 20,
    paddingBottom: 40,
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginBottom: 20,
  },
  section: {
    marginBottom: 16,
  },
  label: {
    fontSize: 12,
    color: '#555',
    marginTop: 8,
    marginBottom: 4,
  },
  input: {
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 14,
    borderRadius: 8,
    marginTop: 12,
    alignItems: 'center',
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonSmall: {
    backgroundColor: '#007AFF',
    padding: 10,
    borderRadius: 8,
    flex: 1,
    margin: 4,
    alignItems: 'center',
  },
  buttonGreen: {
    backgroundColor: '#34C759',
    flex: 1,
    margin: 4,
  },
  buttonRed: {
    backgroundColor: '#FF3B30',
    flex: 1,
    margin: 4,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  hint: {
    fontSize: 12,
    color: '#666',
    marginTop: 8,
  },
  error: {
    color: '#c00',
    marginBottom: 12,
  },
  stats: {
    fontSize: 16,
    fontWeight: '600',
  },
  lastEvent: {
    fontSize: 14,
    color: '#333',
    marginTop: 4,
  },
  small: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  row: {
    flexDirection: 'row',
    marginTop: 8,
  },
  unsupported: {
    padding: 20,
    textAlign: 'center',
    color: '#666',
  },
});

export default App;
