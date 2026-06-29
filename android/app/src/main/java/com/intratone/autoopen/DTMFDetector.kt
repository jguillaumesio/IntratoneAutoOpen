package com.intratone.autoopen;

/**
 * Goertzel algorithm-based DTMF tone detector.
 *
 * <p>All pure DSP lives in {@link DTMFDetectorCore}; this class owns the
 * Android-specific audio capture, the detection thread, and the debounce /
 * listener plumbing. Keeping the two in sync means unit tests on the core cover
 * the real production logic.
 *
 * <p>DTMF uses pairs of frequencies:
 * <pre>
 *   Low:  697, 770, 852, 941 Hz
 *   High: 1209, 1336, 1477, 1633 Hz
 *
 *   Digit map:
 *         1209  1336  1477  1633
 *   697:   1     2     3     A
 *   770:   4     5     6     B
 *   852:   7     8     9     C
 *   941:   *     0     #     D
 * </pre>
 */
public class DTMFDetector {

  private static final String TAG = "DTMFDetector";

  private final OnDTMFListener listener;
  private android.media.AudioRecord audioRecord;
  private boolean running = false;
  private Thread detectionThread;

  // Previous digit for debouncing
  private char lastDetected = 0;
  private long lastDetectedTime = 0;

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
      DTMFDetectorCore.SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    );

    if (bufferSize == AudioFormat.ERROR || bufferSize == AudioFormat.ERROR_BAD_VALUE) {
      bufferSize = DTMFDetectorCore.SAMPLE_RATE * 2; // Fallback
    }

    // Ensure buffer is at least BLOCK_SIZE
    bufferSize = Math.max(bufferSize, DTMFDetectorCore.BLOCK_SIZE * 2);

    try {
      audioRecord = new android.media.AudioRecord(
        MediaRecorder.AudioSource.VOICE_DOWNLINK, // Capture incoming audio
        DTMFDetectorCore.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
      );

      if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
        android.util.Log.e(TAG, "AudioRecord not initialized, trying VOICE_CALL");
        audioRecord.release();
        audioRecord = new android.media.AudioRecord(
          MediaRecorder.AudioSource.VOICE_CALL,
          DTMFDetectorCore.SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bufferSize
        );
      }

      audioRecord.startRecording();

      short[] buffer = new short[DTMFDetectorCore.BLOCK_SIZE];
      double[] samples = new double[DTMFDetectorCore.BLOCK_SIZE];

      while (running && !Thread.currentThread().isInterrupted()) {
        int read = audioRecord.read(buffer, 0, DTMFDetectorCore.BLOCK_SIZE);
        if (read <= 0) continue;

        // Convert to doubles
        for (int i = 0; i < read; i++) {
          samples[i] = buffer[i] / 32768.0;
        }

        // Delegate pure DSP to the shared core.
        Character digit = DTMFDetectorCore.detect(samples, read);
        if (digit == null) continue;
        char d = digit.charValue();

        // Debounce
        long now = System.currentTimeMillis();
        if (!DTMFDetectorCore.shouldReport(d, lastDetected, lastDetectedTime, now)) {
          continue;
        }
        lastDetected = d;
        lastDetectedTime = now;

        listener.onDTMFDigit(String.valueOf(d));
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
}
