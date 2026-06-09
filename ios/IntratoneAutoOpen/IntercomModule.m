#import "IntercomModule.h"

// iOS stub — CallKit can detect calls but cannot auto-answer,
// access in-call audio, or inject DTMF tones.
// This module provides the same JS interface but with limited functionality.

@implementation IntercomModule

RCT_EXPORT_MODULE(IntercomModule);

- (NSArray<NSString *> *)supportedEvents {
  return @[
    @"onWatchingStarted",
    @"onWatchingStopped",
    @"onTargetCallDetected",
    @"onCallAnswered",
    @"onDTMFReceived",
    @"onCodeMatched",
    @"onDoorOpened",
    @"onCallEnded"
  ];
}

RCT_REACT_METHOD(setTargetNumber, : (NSString *)phoneNumber) {
  // Store for CallKit matching
  NSLog(@"[IntercomModule] Target number set: %@", phoneNumber);
}

RCT_REACT_METHOD(setExpectedCode, : (NSString *)code) {
  NSLog(@"[IntercomModule] Expected code set: %@", code);
}

RCT_REACT_METHOD(setTriggerKey, : (NSString *)key) {
  NSLog(@"[IntercomModule] Trigger key set: %@", key);
}

RCT_REACT_METHOD(startWatching) {
  NSLog(@"[IntercomModule] Started watching (iOS limited mode)");
  [self sendEventWithName:@"onWatchingStarted" body:nil];
  // TODO: Implement CallKit CXCallObserver for call detection
  // Will send local notification when target call detected
}

RCT_REACT_METHOD(stopWatching) {
  NSLog(@"[IntercomModule] Stopped watching");
  [self sendEventWithName:@"onWatchingStopped" body:nil];
}

RCT_REACT_METHOD(pressTriggerKey) {
  // On iOS this could open the Phone app with the DTMF tone URL
  // tel://*# — but this won't work mid-call
  NSLog(@"[IntercomModule] pressTriggerKey — not supported on iOS");
}

RCT_REACT_METHOD(isWatching : (RCTPromiseResolveBlock)resolve
                  rejecter : (RCTPromiseRejectBlock)reject) {
  resolve(@(NO)); // TODO: track state
}

// Don't compile Codegen methods that don't exist in ObjC
// The Turbo Module spec is handled via the JS side

@end
