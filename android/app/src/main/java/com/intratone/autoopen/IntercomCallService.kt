package com.intratone.autoopen;

import android.telecom.Call;
import android.telecom.InCallService;
import android.os.Build;
import android.util.Log;

/**
 * InCallService that intercepts calls when the app is set as the default phone app.
 * Routes call events to IntercomModule for auto-answer and DTMF processing.
 */
public class IntercomCallService extends InCallService {

  private static final String TAG = "IntercomCallService";

  /**
   * Redact a phone number for safe logging — show only last 2 digits.
   */
  private static String redactNumber(String number) {
    if (number == null || number.isEmpty() || "unknown".equals(number)) return "<unknown>";
    if (number.length() <= 2) return "**" + number.substring(number.length() - 1);
    int visible = 2;
    int maskLen = number.length() - visible;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < maskLen; i++) sb.append('*');
    sb.append(number.substring(maskLen));
    return sb.toString();
  }

  @Override
  public void onCallAdded(Call call) {
    super.onCallAdded(call);
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "Call added: " + redactNumber(getCallNumber(call)));
    }

    // Forward to IntercomModule
    IntercomModule module = IntercomPackage.getModule();
    if (module != null) {
      module.onCallAdded(call);
    }

    // Register callback for call state changes
    call.registerCallback(new Call.Callback() {
      @Override
      public void onStateChanged(Call call, int newState) {
        super.onStateChanged(call, newState);
        if (BuildConfig.DEBUG) {
          Log.d(TAG, "Call state changed to: " + stateToString(newState));
        }

        if (newState == Call.STATE_ACTIVE) {
          // Call is active (answered) — start DTMF detection
          if (module != null) {
            module.startDTMFDetection();
          }
        }
      }
    });
  }

  @Override
  public void onCallRemoved(Call call) {
    super.onCallRemoved(call);
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "Call removed");
    }

    IntercomModule module = IntercomPackage.getModule();
    if (module != null) {
      module.onCallRemoved(call);
    }
  }

  private String getCallNumber(Call call) {
    Call.Details details = call.getDetails();
    if (details == null || details.getHandle() == null) return "unknown";
    String number = details.getHandle().getSchemeSpecificPart();
    return number != null ? number : "unknown";
  }

  private String stateToString(int state) {
    switch (state) {
      case Call.STATE_NEW: return "NEW";
      case Call.STATE_RINGING: return "RINGING";
      case Call.STATE_DIALING: return "DIALING";
      case Call.STATE_ACTIVE: return "ACTIVE";
      case Call.STATE_HOLDING: return "HOLDING";
      case Call.STATE_DISCONNECTED: return "DISCONNECTED";
      case Call.STATE_PULLING_CALL: return "PULLING";
      case Call.STATE_SELECT_PHONE_ACCOUNT: return "SELECT_ACCOUNT";
      default: return "UNKNOWN(" + state + ")";
    }
  }
}
