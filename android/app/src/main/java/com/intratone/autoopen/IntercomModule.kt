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

  // --- Configuration ---

  @ReactMethod
  public void setTargetNumber(String phoneNumber) {
    this.targetNumber = phoneNumber.replaceAll("[^+0-9]", "");
    Log.d(TAG, "Target number set: " + this.targetNumber);
  }

  @ReactMethod
  public void setExpectedCode(String code) {
    this.expectedCode = code;
    this.codeBuffer.setLength(0);
    Log.d(TAG, "Expected code set: " + this.expectedCode);
  }

  @ReactMethod
  public void setTriggerKey(String key) {
    this.triggerKey = key.isEmpty() ? "#" : key;
    Log.d(TAG, "Trigger key set: " + this.triggerKey);
  }

  // --- Lifecycle ---

  @ReactMethod
  public void startWatching() {
    if (watching) return;
    watching = true;
    Log.d(TAG, "Started watching for calls from: " + targetNumber);

    // Start foreground service to prevent OS from killing the process
    IntercomForegroundService.start(getReactApplicationContext());

    sendEvent("onWatchingStarted", null);
  }

  @ReactMethod
  public void stopWatching() {
    watching = false;
    stopDTMFDetector();
    Log.d(TAG, "Stopped watching");

    // Stop foreground service — app can be killed by OS again
    IntercomForegroundService.stop(getReactApplicationContext());

    sendEvent("onWatchingStopped", null);
  }

  @ReactMethod
  public void pressTriggerKey() {
    if (activeCall != null) {
      pressKeyOnCall(activeCall, triggerKey);
    } else {
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
    Log.d(TAG, "Call added from: " + incomingNumber);

    if (!watching) {
      Log.d(TAG, "Not watching, ignoring call");
      return;
    }

    if (isTargetNumber(incomingNumber)) {
      Log.d(TAG, "Target number matched! Auto-answering...");
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
      Log.d(TAG, "DTMF digit detected: " + digit);
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
        Log.d(TAG, "Code matched! Pressing trigger key: " + triggerKey);
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

        Log.d(TAG, "Pressed DTMF: " + dtmfChar);
      } catch (Exception e) {
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
