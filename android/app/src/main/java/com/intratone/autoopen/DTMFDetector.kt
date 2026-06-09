package com.intratone.autoopen;

import java.util.Locale;

/**
 * Goertzel algorithm-based DTMF tone detector.
 *
 * DTMF uses pairs of frequencies:
 *   Low:  697, 770, 852, 941 Hz
 *   High: 1209, 1336, 1477, 1633 Hz
 *
 * Digit map:
 *         1209  1336  1477  1633
 *   697:   1     2     3     A
 *   770:   4     5     6     B
 *   852:   7     8     9     C
 *   941:   *     0     #     D
 */
public class DTMFDetector {

  private static final String TAG = "DTMFDetector";

  // DTMF frequency tables
  private static final double[] LOW_FREQS = {697.0, 770.0, 852.0, 941.0};
  private static final double[] HIGH_FREQS = {1209.0, 1336.0, 1477.0, 1633.0};

  private static final char[][] DIGIT_MAP = {
    {'1', '2', '3', 'A'},
    {'4', '5', '6', 'B'},
    {'7', '8', '9', 'C'},
    {'*', '0', '#', 'D'}
  };

  // Audio config
  private static final int SAMPLE_RATE = 8000; // Phone audio sample rate
  private static final int BLOCK_SIZE = 205;   // ~25ms at 8kHz — good for Goertzel
  private static final double THRESHOLD = 0.4; // Normalized magnitude threshold

  private final OnDTMFListener listener;
  private android.media.AudioRecord audioRecord;
  private boolean running = false;
  private Thread detectionThread;

  // Previous digit for debouncing
  private char lastDetected = 0;
  private long lastDetectedTime = 0;
  private static final long DEBOUNCE_MS = 80; // Minimum time between same digit

  public interface OnDTMFListener {
    void onDTMFDigit(String digit);
  }

  public DTMFDetector(OnDTMFListener listener) {
    this.listener = listener;
  }

  public void start() {
    if (running) return;

    running = true;
    detectionThread = new Thread(this::detectionLoop);
    detectionThread.setName("DTMF-Detector");
    detectionThread.setPriority(Thread.MAX_PRIORITY);
    detectionThread.start();
  }

  public void stop() {
    running = false;
    if (detectionThread != null) {
      detectionThread.interrupt();
      try {
        detectionThread.join(500);
      } catch (InterruptedException ignored) {}
      detectionThread = null;
    }
    if (audioRecord != null) {
      try {
        audioRecord.stop();
        audioRecord.release();
      } catch (Exception ignored) {}
      audioRecord = null;
    }
  }

  private void detectionLoop() {
    int bufferSize = android.media.AudioRecord.getMinBufferSize(
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    );

    if (bufferSize == AudioFormat.ERROR || bufferSize == AudioFormat.ERROR_BAD_VALUE) {
      bufferSize = SAMPLE_RATE * 2; // Fallback
    }

    // Ensure buffer is at least BLOCK_SIZE
    bufferSize = Math.max(bufferSize, BLOCK_SIZE * 2);

    try {
      audioRecord = new android.media.AudioRecord(
        MediaRecorder.AudioSource.VOICE_DOWNLINK, // Capture incoming audio
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
      );

      if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
        android.util.Log.e(TAG, "AudioRecord not initialized, trying VOICE_CALL");
        audioRecord.release();
        audioRecord = new android.media.AudioRecord(
          MediaRecorder.AudioSource.VOICE_CALL,
          SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bufferSize
        );
      }

      audioRecord.startRecording();

      short[] buffer = new short[BLOCK_SIZE];
      double[] samples = new double[BLOCK_SIZE];

      while (running && !Thread.currentThread().isInterrupted()) {
        int read = audioRecord.read(buffer, 0, BLOCK_SIZE);
        if (read <= 0) continue;

        // Convert to doubles
        double maxAmp = 0;
        for (int i = 0; i < read; i++) {
          samples[i] = buffer[i] / 32768.0;
          maxAmp = Math.max(maxAmp, Math.abs(samples[i]));
        }

        // Skip silence
        if (maxAmp < 0.01) continue;

        // Run Goertzel for each DTMF frequency
        double[] lowMags = new double[4];
        double[] highMags = new double[4];

        for (int i = 0; i < 4; i++) {
          lowMags[i] = goertzel(samples, read, LOW_FREQS[i]);
          highMags[i] = goertzel(samples, read, HIGH_FREQS[i]);
        }

        // Find peak low and high frequencies
        int lowIdx = findMaxIndex(lowMags);
        int highIdx = findMaxIndex(highMags);

        // Normalize
        double lowMax = lowMags[lowIdx];
        double highMax = highMags[highIdx];

        // Validate: both frequencies must be above threshold
        // and the peak must be significantly stronger than others
        if (lowMax > THRESHOLD && highMax > THRESHOLD) {
          // Check that peaks are dominant (at least 2x second strongest)
          if (isDominant(lowMags, lowIdx) && isDominant(highMags, highIdx)) {
            char digit = DIGIT_MAP[lowIdx][highIdx];

            // Debounce
            long now = System.currentTimeMillis();
            if (digit == lastDetected && (now - lastDetectedTime) < DEBOUNCE_MS) {
              continue;
            }
            lastDetected = digit;
            lastDetectedTime = now;

            listener.onDTMFDigit(String.valueOf(digit));
          }
        }
      }
    } catch (Exception e) {
      android.util.Log.e(TAG, "Detection loop error", e);
    } finally {
      if (audioRecord != null) {
        try {
          audioRecord.stop();
          audioRecord.release();
        } catch (Exception ignored) {}
        audioRecord = null;
      }
    }
  }

  /**
   * Goertzel algorithm — compute magnitude of a specific frequency in the signal.
   * More efficient than full FFT when you only need a few frequencies.
   */
  private double goertzel(double[] samples, int n, double targetFreq) {
    double k = (double) n * targetFreq / SAMPLE_RATE;
    double w = 2.0 * Math.PI * k / n;
    double coeff = 2.0 * Math.cos(w);
    double s0 = 0, s1 = 0, s2 = 0;

    for (int i = 0; i < n; i++) {
      s0 = samples[i] + coeff * s1 - s2;
      s2 = s1;
      s1 = s0;
    }

    double magnitude = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    // Normalize by block size
    return Math.sqrt(Math.abs(magnitude)) / n;
  }

  private int findMaxIndex(double[] arr) {
    int maxIdx = 0;
    double maxVal = arr[0];
    for (int i = 1; i < arr.length; i++) {
      if (arr[i] > maxVal) {
        maxVal = arr[i];
        maxIdx = i;
      }
    }
    return maxIdx;
  }

  private boolean isDominant(double[] mags, int peakIdx) {
    double peak = mags[peakIdx];
    for (int i = 0; i < mags.length; i++) {
      if (i != peakIdx && mags[i] > peak * 0.5) {
        return false;
      }
    }
    return true;
  }
}
