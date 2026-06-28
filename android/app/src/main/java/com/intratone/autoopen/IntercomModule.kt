package com.intratone.autoopen;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.turbomodule.core.interfaces.TurboModule;

import java.util.HashMap;
import java.util.Map;

@ReactModule(name = IntercomModule.NAME)
public class IntercomModule extends NativeIntercomModuleSpec implements TurboModule {
  public static final String NAME = "IntercomModule";
  private static final String TAG = "IntercomModule";

  private String targetNumber = "";
  private String expectedCode = "";
  private String triggerKey = "#";
  private boolean watching = false;
  private DTMFDetector dtmfDetector;
  private StringBuilder codeBuffer = new StringBuilder();
  private Call activeCall = null;

  public IntercomModule(ReactApplicationContext context) {
    super(context);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  /**
   * Redact a sensitive string for safe logging.
   * Shows only the last 2 characters (if long enough), masks the rest with asterisks.
   * Example: "1234567890" -> "********90"
   */
  private static String redact(String value) {
    if (value == null || value.isEmpty()) return "<empty>";
    if (value.length() <= 2) return "**" + value.substring(value.length() - 1);
    int visible = 2;
    int maskLen = value.length() - visible;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < maskLen; i++) sb.append('*');
    sb.append(value.substring(maskLen));
    return sb.toString();
  }

  /**
   * Debug log — only emitted in debug builds (stripped from release via BuildConfig.DEBUG).
   * Never logs sensitive values in plaintext.
   */
  private static void debugLog(String tag, String message) {
    if (BuildConfig.DEBUG) {
      Log.d(tag, message);
    }
  }

  // --- Configuration ---

  @ReactMethod
  public void setTargetNumber(String phoneNumber) {
    this.targetNumber = phoneNumber.replaceAll("[^+0-9]", "");
    debugLog(TAG, "Target number set: " + redact(this.targetNumber));
  }

  @ReactMethod
  public void setExpectedCode(String code) {
    this.expectedCode = code;
    this.codeBuffer.setLength(0);
    debugLog(TAG, "Expected code set: " + redact(this.expectedCode) + " (length=" + code.length() + ")");
  }

  @ReactMethod
  public void setTriggerKey(String key) {
    this.triggerKey = key.isEmpty() ? "#" : key;
    debugLog(TAG, "Trigger key set: " + redact(this.triggerKey));
  }

  // --- Lifecycle ---

  @ReactMethod
  public void startWatching() {
    if (watching) return;
    watching = true;
    debugLog(TAG, "Started watching for calls from: " + redact(targetNumber));

    // Start foreground service to prevent OS from killing the process
    IntercomForegroundService.start(getReactApplicationContext());

    sendEvent("onWatchingStarted", null);
  }

  @ReactMethod
  public void stopWatching() {
    watching = false;
    stopDTMFDetector();
    debugLog(TAG, "Stopped watching");

    // Stop foreground service — app can be killed by OS again
    IntercomForegroundService.stop(getReactApplicationContext());

    sendEvent("onWatchingStopped", null);
  }

  @ReactMethod
  public void pressTriggerKey() {
    if (activeCall != null) {
      pressKeyOnCall(activeCall, triggerKey);
    } else {
      // Log.w is preserved for warnings — no sensitive data here
      Log.w(TAG, "No active call to press key on");
    }
  }

  @ReactMethod
  public void isWatching(Promise promise) {
    promise.resolve(watching);
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Required for RN event emitter
  }

  @ReactMethod
  public void removeListeners(double count) {
    // Required for RN event emitter
  }

  // --- Call handling (called from IntercomCallService) ---

  public void onCallAdded(Call call) {
    String incomingNumber = getCallNumber(call);
    debugLog(TAG, "Call added from: " + redact(incomingNumber));

    if (!watching) {
      debugLog(TAG, "Not watching, ignoring call");
      return;
    }

    if (isTargetNumber(incomingNumber)) {
      debugLog(TAG, "Target number matched! Auto-answering...");
      activeCall = call;
      codeBuffer.setLength(0);

      WritableMap params = Arguments.createMap();
      params.putString("number", incomingNumber);
      sendEvent("onTargetCallDetected", params);

      // Auto-answer
      call.answer(Call.Details.DIRECTION_INCOMING);
      sendEvent("onCallAnswered", null);
    }
  }

  public void onCallRemoved(Call call) {
    if (activeCall == call) {
      activeCall = null;
      stopDTMFDetector();
      sendEvent("onCallEnded", null);
    }
  }

  // --- DTMF Detection (called from IntercomCallService when call goes ACTIVE) ---

  public void startDTMFDetection() {
    if (dtmfDetector != null) {
      dtmfDetector.stop();
    }
    dtmfDetector = new DTMFDetector(digit -> {
      debugLog(TAG, "DTMF digit detected: " + redact(digit));
      handleDTMFDigit(digit);
    });
    dtmfDetector.start();
  }

  private void stopDTMFDetector() {
    if (dtmfDetector != null) {
      dtmfDetector.stop();
      dtmfDetector = null;
    }
  }

  private void handleDTMFDigit(String digit) {
    codeBuffer.append(digit);

    WritableMap params = Arguments.createMap();
    params.putString("digit", digit);
    params.putString("buffer", codeBuffer.toString());
    sendEvent("onDTMFReceived", params);

    // Check if buffer matches expected code
    String bufferStr = codeBuffer.toString();
    if (expectedCode.length() > 0 && bufferStr.length() >= expectedCode.length()) {
      // Keep only the last N digits (N = code length)
      if (bufferStr.length() > expectedCode.length()) {
        codeBuffer = new StringBuilder(
          bufferStr.substring(bufferStr.length() - expectedCode.length())
        );
        bufferStr = codeBuffer.toString();
      }

      if (bufferStr.equals(expectedCode)) {
        debugLog(TAG, "Code matched! Pressing trigger key: " + redact(triggerKey));
        sendEvent("onCodeMatched", null);

        if (activeCall != null) {
          pressKeyOnCall(activeCall, triggerKey);
          sendEvent("onDoorOpened", null);
        }

        codeBuffer.setLength(0);
      }
    }
  }

  // --- Key press ---

  private void pressKeyOnCall(Call call, String key) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        char dtmfChar = key.charAt(0);
        call.playDtmfTone(dtmfChar);

        // Stop tone after 200ms
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
          try {
            call.stopDtmfTone();
          } catch (Exception ignored) {}
        }, 200);

        debugLog(TAG, "Pressed DTMF: " + redact(String.valueOf(dtmfChar)));
      } catch (Exception e) {
        // Log.e preserved for errors — no sensitive data in exception message
        Log.e(TAG, "Failed to press DTMF key", e);
      }
    }
  }

  // --- Helpers ---

  private String getCallNumber(Call call) {
    Call.Details details = call.getDetails();
    if (details == null) return "";
    if (details.getHandle() == null) return "";
    String number = details.getHandle().getSchemeSpecificPart();
    return number != null ? number.replaceAll("[^+0-9]", "") : "";
  }

  private boolean isTargetNumber(String incoming) {
    if (targetNumber.isEmpty() || incoming.isEmpty()) return false;
    return incoming.endsWith(targetNumber) || targetNumber.endsWith(incoming);
  }

  private void sendEvent(String eventName, WritableMap params) {
    try {
      getReactApplicationContext()
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(eventName, params);
    } catch (Exception e) {
      // Log.e preserved for errors — no sensitive data
      Log.e(TAG, "Failed to send event: " + eventName, e);
    }
  }

  @Override
  public Map<String, Object> getTypedExportedConstants() {
    Map<String, Object> constants = new HashMap<>();
    constants.put("PLATFORM_SUPPORTS_AUTO", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
    return constants;
  }
}
