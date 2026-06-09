# Intratone Auto-Open

React Native app that automatically answers your Intratone intercom call, listens for DTMF digits, and presses `#` (or your chosen key) when the correct code is entered — unlocking the door hands-free.

## How it works

```
Visitor dials your apartment on the intercom
  → Intercom calls your phone
    → App auto-answers (Android only)
      → Visitor enters digicode on intercom keypad
        → App detects DTMF tones via Goertzel algorithm
          → If code matches → app presses # to open the door
```

## Platform support

| Feature | Android | iOS |
|---------|---------|-----|
| Detect incoming call | ✅ | ✅ (CallKit) |
| Auto-answer specific number | ✅ | ❌ |
| DTMF tone detection | ✅ (Goertzel) | ❌ |
| Auto-press trigger key | ✅ | ❌ |

**Android is the primary target.** iOS is stubbed with the same JS interface for future implementation via CallKit + local notifications.

## Setup

### Prerequisites

- Node.js 18+
- Android Studio with SDK 34+
- Android device (emulator won't simulate real calls)
- React Native dev environment: https://reactnative.dev/docs/environment-setup

### Install

```bash
cd IntratoneAutoOpen
npm install
```

### Run on Android

```bash
# Connect your Android device via USB (with USB debugging enabled)
npx react-native run-android
```

### Required Android setup (one-time)

After installing the app on your device:

1. **Grant all permissions** — the app will prompt on first start
2. **Set as default phone app** — Settings → Apps → Default Apps → Phone app → Intratone Auto-Open
3. This is required for `InCallService` to intercept calls

### Configuration

In the app:

- **Numéro de l'interphone** — the phone number that your Intratone intercom calls from (e.g. `+33123456789`)
- **Code d'ouverture** — the fixed code visitors must enter (e.g. `1234`)
- **Touche de déclenchement** — which key to press when code matches (default `#`)

## Architecture

```
src/NativeIntercomModule.ts      ← Turbo Module spec (JS interface)
App.tsx                          ← UI: settings + live status + log

android/.../IntercomModule.kt    ← Native module: config, DTMF handling, events
android/.../IntercomCallService.kt ← InCallService: call interception, auto-answer
android/.../DTMFDetector.kt      ← Goertzel algorithm for DTMF tone detection
android/.../IntercomPackage.kt   ← Turbo React package registration

ios/.../IntercomModule.m         ← iOS stub (CallKit detection only)
```

### DTMF Detection

Uses the [Goertzel algorithm](https://en.wikipedia.org/wiki/Goertzel_algorithm) to detect DTMF frequency pairs from the call audio stream. More efficient than FFT since it only checks 8 specific frequencies:

- Low: 697, 770, 852, 941 Hz
- High: 1209, 1336, 1477, 1633 Hz

Digit map:
```
        1209  1336  1477  1633
697:    1     2     3     A
770:    4     5     6     B
852:    7     8     9     C
941:    *     0     #     D
```

The detector runs on a background thread, reads from `VOICE_DOWNLINK` audio source (incoming call audio), processes 205-sample blocks (~25ms at 8kHz), and uses debouncing to avoid duplicate detections.

## Limitations

- Android only for full auto functionality
- Requires being set as default phone app
- `VOICE_DOWNLINK` audio source availability varies by device/Android version — falls back to `VOICE_CALL`
- Minimum Android 8.0 (API 26) for `InCallService.playDtmfTone()`
