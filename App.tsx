import React, {useState, useEffect, useCallback} from 'react';
import {
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  Alert,
  NativeEventEmitter,
  NativeModules,
  Platform,
  PermissionsAndroid,
} from 'react-native';
import NativeIntercomModule from './NativeIntercomModule';

type CallStatus = 'idle' | 'watching' | 'ringing' | 'answered' | 'decoding' | 'matched' | 'opened';

const STATUS_CONFIG: Record<CallStatus, {label: string; color: string; icon: string}> = {
  idle:     {label: 'Inactif',           color: '#6b7280', icon: '⏸'},
  watching: {label: 'En écoute...',       color: '#3b82f6', icon: '👀'},
  ringing:  {label: 'Appel détecté !',    color: '#f59e0b', icon: '📞'},
  answered: {label: 'Appel décroché',     color: '#10b981', icon: '✅'},
  decoding: {label: 'Détection DTMF...',  color: '#8b5cf6', icon: '🎵'},
  matched:  {label: 'Code correct !',     color: '#22c55e', icon: '🔓'},
  opened:   {label: 'Porte ouverte !',    color: '#16a34a', icon: '🚪'},
};

const App = () => {
  const [targetNumber, setTargetNumber] = useState('');
  const [expectedCode, setExpectedCode] = useState('');
  const [triggerKey, setTriggerKey] = useState('#');
  const [status, setStatus] = useState<CallStatus>('idle');
  const [dtmfBuffer, setDtmfBuffer] = useState('');
  const [lastDigit, setLastDigit] = useState('');
  const [log, setLog] = useState<string[]>([]);

  const addLog = useCallback((msg: string) => {
    const time = new Date().toLocaleTimeString('fr-FR', {hour: '2-digit', minute: '2-digit', second: '2-digit'});
    setLog(prev => [`${time} ${msg}`, ...prev].slice(0, 50));
  }, []);

  // Request Android permissions
  const requestPermissions = async () => {
    if (Platform.OS !== 'android') return true;

    const perms = [
      PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
      PermissionsAndroid.PERMISSIONS.READ_CALL_LOG,
      PermissionsAndroid.PERMISSIONS.CALL_PHONE,
      PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS,
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
    ];

    const granted = await PermissionsAndroid.requestMultiple(perms);
    const allGranted = Object.values(granted).every(v => v === PermissionsAndroid.RESULTS.GRANTED);
    if (!allGranted) {
      Alert.alert('Permissions', 'Toutes les permissions sont requises pour le fonctionnement automatique.');
    }
    return allGranted;
  };

  // Listen to native events
  useEffect(() => {
    const emitter = new NativeEventEmitter(NativeModules.IntercomModule);

    const subscriptions = [
      emitter.addListener('onWatchingStarted', () => {
        setStatus('watching');
        addLog('Surveillance démarrée');
      }),
      emitter.addListener('onWatchingStopped', () => {
        setStatus('idle');
        setDtmfBuffer('');
        addLog('Surveillance arrêtée');
      }),
      emitter.addListener('onTargetCallDetected', (e: any) => {
        setStatus('ringing');
        addLog(`Appel de ${e.number}`);
      }),
      emitter.addListener('onCallAnswered', () => {
        setStatus('answered');
        addLog('Appel décroché automatiquement');
      }),
      emitter.addListener('onDTMFReceived', (e: any) => {
        setStatus('decoding');
        setLastDigit(e.digit);
        setDtmfBuffer(e.buffer);
        addLog(`DTMF: ${e.digit} (buffer: ${e.buffer})`);
      }),
      emitter.addListener('onCodeMatched', () => {
        setStatus('matched');
        addLog('Code correct détecté !');
      }),
      emitter.addListener('onDoorOpened', () => {
        setStatus('opened');
        addLog('Porte ouverte !');
        // Reset after 3s
        setTimeout(() => setStatus('watching'), 3000);
      }),
      emitter.addListener('onCallEnded', () => {
        setStatus('watching');
        setDtmfBuffer('');
        addLog('Appel terminé');
      }),
    ];

    return () => subscriptions.forEach(s => s.remove());
  }, [addLog]);

  const handleStart = async () => {
    if (!targetNumber.trim()) {
      Alert.alert('Configuration', 'Entrez le numéro de l\'interphone.');
      return;
    }
    if (!expectedCode.trim()) {
      Alert.alert('Configuration', 'Entrez le code d\'ouverture.');
      return;
    }

    const hasPerms = await requestPermissions();
    if (!hasPerms) return;

    NativeIntercomModule.setTargetNumber(targetNumber.trim());
    NativeIntercomModule.setExpectedCode(expectedCode.trim());
    NativeIntercomModule.setTriggerKey(triggerKey.trim() || '#');
    NativeIntercomModule.startWatching();
  };

  const handleStop = () => {
    NativeIntercomModule.stopWatching();
  };

  const statusConfig = STATUS_CONFIG[status];

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#0f172a" />

      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>Intratone Auto-Open</Text>
          <Text style={styles.subtitle}>Auto-réponse interphone</Text>
        </View>

        {/* Status Card */}
        <View style={[styles.statusCard, {borderColor: statusConfig.color}]}>
          <Text style={styles.statusIcon}>{statusConfig.icon}</Text>
          <Text style={[styles.statusLabel, {color: statusConfig.color}]}>
            {statusConfig.label}
          </Text>
          {dtmfBuffer ? (
            <Text style={styles.bufferText}>Buffer: {dtmfBuffer}</Text>
          ) : null}
          {lastDigit && status === 'decoding' ? (
            <Text style={styles.digitText}>Dernier: {lastDigit}</Text>
          ) : null}
        </View>

        {/* Settings */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Configuration</Text>

          <Text style={styles.label}>Numéro de l'interphone</Text>
          <TextInput
            style={styles.input}
            value={targetNumber}
            onChangeText={setTargetNumber}
            placeholder="+33612345678"
            placeholderTextColor="#4b5563"
            keyboardType="phone-pad"
            editable={status === 'idle'}
          />

          <Text style={styles.label}>Code d'ouverture</Text>
          <TextInput
            style={styles.input}
            value={expectedCode}
            onChangeText={setExpectedCode}
            placeholder="1234"
            placeholderTextColor="#4b5563"
            keyboardType="number-pad"
            maxLength={10}
            editable={status === 'idle'}
          />

          <Text style={styles.label}>Touche de déclenchement</Text>
          <View style={styles.keyRow}>
            {['#', '*', '0', '9'].map(key => (
              <TouchableOpacity
                key={key}
                style={[styles.keyButton, triggerKey === key && styles.keyButtonActive]}
                onPress={() => setTriggerKey(key)}
                disabled={status !== 'idle'}
              >
                <Text style={[styles.keyButtonText, triggerKey === key && styles.keyButtonTextActive]}>
                  {key}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        {/* Action Buttons */}
        <View style={styles.buttonRow}>
          {status === 'idle' ? (
            <TouchableOpacity style={[styles.button, styles.buttonStart]} onPress={handleStart}>
              <Text style={styles.buttonText}>Démarrer</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity style={[styles.button, styles.buttonStop]} onPress={handleStop}>
              <Text style={styles.buttonText}>Arrêter</Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Note about default phone app */}
        <View style={styles.note}>
          <Text style={styles.noteText}>
            ⚠️ L'app doit être définie comme application téléphone par défaut
            dans les paramètres Android pour intercepter les appels.
          </Text>
        </View>

        {/* Log */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Journal</Text>
          {log.map((entry, i) => (
            <Text key={i} style={styles.logEntry}>{entry}</Text>
          ))}
          {log.length === 0 && (
            <Text style={styles.logEmpty}>Aucun événement</Text>
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  scroll: {
    padding: 20,
    paddingBottom: 40,
  },
  header: {
    alignItems: 'center',
    marginBottom: 24,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: '#f8fafc',
    fontFamily: 'System',
  },
  subtitle: {
    fontSize: 14,
    color: '#94a3b8',
    marginTop: 4,
  },
  statusCard: {
    borderWidth: 2,
    borderRadius: 16,
    padding: 24,
    alignItems: 'center',
    backgroundColor: '#1e293b',
    marginBottom: 24,
  },
  statusIcon: {
    fontSize: 40,
    marginBottom: 8,
  },
  statusLabel: {
    fontSize: 20,
    fontWeight: '600',
  },
  bufferText: {
    color: '#94a3b8',
    fontSize: 14,
    marginTop: 8,
    fontFamily: 'Courier',
  },
  digitText: {
    color: '#c084fc',
    fontSize: 16,
    fontWeight: '700',
    marginTop: 4,
  },
  section: {
    backgroundColor: '#1e293b',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#e2e8f0',
    marginBottom: 12,
  },
  label: {
    fontSize: 13,
    color: '#94a3b8',
    marginBottom: 6,
    marginTop: 8,
  },
  input: {
    backgroundColor: '#0f172a',
    borderRadius: 8,
    padding: 12,
    color: '#f8fafc',
    fontSize: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  keyRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 4,
  },
  keyButton: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#0f172a',
    borderWidth: 1,
    borderColor: '#334155',
  },
  keyButtonActive: {
    backgroundColor: '#3b82f6',
    borderColor: '#60a5fa',
  },
  keyButtonText: {
    color: '#94a3b8',
    fontSize: 18,
    fontWeight: '700',
  },
  keyButtonTextActive: {
    color: '#ffffff',
  },
  buttonRow: {
    marginBottom: 16,
  },
  button: {
    borderRadius: 12,
    paddingVertical: 16,
    alignItems: 'center',
  },
  buttonStart: {
    backgroundColor: '#22c55e',
  },
  buttonStop: {
    backgroundColor: '#ef4444',
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 18,
    fontWeight: '700',
  },
  note: {
    backgroundColor: '#1e293b',
    borderRadius: 8,
    padding: 12,
    borderLeftWidth: 3,
    borderLeftColor: '#f59e0b',
    marginBottom: 16,
  },
  noteText: {
    color: '#fbbf24',
    fontSize: 12,
    lineHeight: 18,
  },
  logEntry: {
    color: '#64748b',
    fontSize: 12,
    fontFamily: 'Courier',
    lineHeight: 18,
  },
  logEmpty: {
    color: '#475569',
    fontSize: 12,
    fontStyle: 'italic',
  },
});

export default App;
