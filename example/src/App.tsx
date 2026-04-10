import React, { useState, useCallback } from 'react';

import {
  StyleSheet,
  View,
  Text,
  TouchableOpacity,
  ScrollView,
} from 'react-native';
import TransparentVideo from 'react-native-transparent-video';

const video1 = require('../assets/videos/1.mp4');

export default () => {
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(false);
  const [volume, setVolume] = useState(1.0);
  const [logs, setLogs] = useState<string[]>([]);

  const addLog = useCallback((msg: string) => {
    setLogs((prev) => [`[${new Date().toLocaleTimeString()}] ${msg}`, ...prev].slice(0, 20));
  }, []);

  const onLoad = useCallback(() => addLog('onLoad'), [addLog]);
  const onEnd = useCallback(() => addLog('onEnd'), [addLog]);
  const onError = useCallback(
    (error: { message: string }) => addLog(`onError: ${error.message}`),
    [addLog]
  );

  return (
    <View style={styles.container}>
      <View style={styles.videoContainer}>
        <TransparentVideo
          source={video1}
          style={styles.video}
          loop
          autoplay
          paused={paused}
          muted={muted}
          volume={volume}
          onLoad={onLoad}
          onEnd={onEnd}
          onError={onError}
        />
      </View>

      <View style={styles.controls}>
        <TouchableOpacity
          style={styles.button}
          onPress={() => setPaused((p) => !p)}
        >
          <Text style={styles.buttonText}>{paused ? 'Play' : 'Pause'}</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.button}
          onPress={() => setMuted((m) => !m)}
        >
          <Text style={styles.buttonText}>
            {muted ? 'Unmute' : 'Mute'}
          </Text>
        </TouchableOpacity>

        <View style={styles.volumeRow}>
          <Text style={styles.label}>Vol: {volume.toFixed(1)}</Text>
          <TouchableOpacity
            style={styles.smallButton}
            onPress={() => setVolume((v) => Math.max(0, +(v - 0.1).toFixed(1)))}
          >
            <Text style={styles.buttonText}>-</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.smallButton}
            onPress={() => setVolume((v) => Math.min(1, +(v + 0.1).toFixed(1)))}
          >
            <Text style={styles.buttonText}>+</Text>
          </TouchableOpacity>
        </View>
      </View>

      <ScrollView style={styles.logContainer}>
        {logs.map((log, i) => (
          <Text key={i} style={styles.logText}>
            {log}
          </Text>
        ))}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'darkblue',
  },
  videoContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  video: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
    gap: 12,
  },
  button: {
    backgroundColor: 'rgba(255,255,255,0.2)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  smallButton: {
    backgroundColor: 'rgba(255,255,255,0.2)',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
  buttonText: {
    color: 'white',
    fontSize: 14,
    fontWeight: '600',
  },
  volumeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  label: {
    color: 'white',
    fontSize: 14,
  },
  logContainer: {
    maxHeight: 120,
    paddingHorizontal: 16,
    paddingBottom: 16,
  },
  logText: {
    color: 'rgba(255,255,255,0.7)',
    fontSize: 12,
    fontFamily: 'monospace',
  },
});
