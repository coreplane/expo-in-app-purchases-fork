package expo.modules.inapppurchases;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.JavaScriptModule;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;

import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;

public class InAppPurchasesPackage implements ReactPackage {
  @Override
  public List<NativeModule> createNativeModules(ReactApplicationContext reactContext) {
      Log.d("InAppPurchasesPackage", "createNativeModules");
      return Arrays.asList(new NativeModule[]{
        new InAppPurchasesModule(reactContext),
    });
  }

  public List<Class<? extends JavaScriptModule>> createJSModules() {
    return Collections.emptyList();
  }

  @Override
  public List<ViewManager> createViewManagers(ReactApplicationContext reactContext) {
    return Collections.emptyList();
  }
}
