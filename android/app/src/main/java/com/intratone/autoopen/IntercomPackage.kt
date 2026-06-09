package com.intratone.autoopen;

import androidx.annotation.NonNull;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.module.model.ReactModuleInfo;
import com.facebook.react.module.model.ReactModuleInfoProvider;
import com.facebook.react.TurboReactPackage;

import java.util.HashMap;
import java.util.Map;

public class IntercomPackage extends TurboReactPackage {

  private static IntercomModule moduleInstance;

  @NonNull
  @Override
  public NativeModule getModule(String name, @NonNull ReactApplicationContext reactContext) {
    if (IntercomModule.NAME.equals(name)) {
      if (moduleInstance == null) {
        moduleInstance = new IntercomModule(reactContext);
      }
      return moduleInstance;
    }
    return null;
  }

  public static IntercomModule getModule() {
    return moduleInstance;
  }

  @Override
  public ReactModuleInfoProvider getReactModuleInfoProvider() {
    return () -> {
      Map<String, ReactModuleInfo> moduleInfos = new HashMap<>();
      moduleInfos.put(
        IntercomModule.NAME,
        new ReactModuleInfo(
          IntercomModule.NAME,
          IntercomModule.NAME,
          false,      // canOverrideExistingModule
          false,      // needsEagerInit
          true,       // isCxxModule
          true        // isTurboModule
        )
      );
      return moduleInfos;
    };
  }
}
