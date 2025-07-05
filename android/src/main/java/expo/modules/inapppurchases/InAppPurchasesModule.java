package expo.modules.inapppurchases;

import java.util.List;
import java.util.ArrayList;

import androidx.annotation.Nullable;

import android.content.Context;
import android.app.Activity;
import android.util.Log;

import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

@ReactModule(name = InAppPurchasesModule.NAME)
public class InAppPurchasesModule extends ReactContextBaseJavaModule {
  private static final String TAG = InAppPurchasesModule.class.getSimpleName();
  public static final String NAME = "ExpoInAppPurchases";

  private BillingManager mBillingManager;

  public InAppPurchasesModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void connectAsync(final Promise promise) {
    Activity activity = getCurrentActivity();
    if (activity == null) {
      promise.reject("E_ACTIVITY_UNAVAILABLE", "Activity is not available");
    }
    mBillingManager = new BillingManager(activity, this);
    mBillingManager.startConnection(promise);
  }

  @ReactMethod
  public void getProductsAsync(ReadableArray itemListArray, final Promise promise) {
    List<String> itemList = new ArrayList<>();
    for(int i = 0; i < itemListArray.size(); i++) {
      itemList.add(itemListArray.getString(i));
    }
    mBillingManager.queryPurchasableItems(itemList, promise);
  }

  @ReactMethod
  public void getPurchaseHistoryAsync(final ReadableMap options, final Promise promise) {
    mBillingManager.queryPurchases(promise);
  }

  @ReactMethod
  public void purchaseItemAsync(String skuId, ReadableMap details, final Promise promise) {
    mBillingManager.purchaseItemAsync(skuId, details, promise);
  }

  @ReactMethod
  public void getBillingResponseCodeAsync(final Promise promise) {
    promise.resolve(mBillingManager.getBillingClientResponseCode());
  }

  @ReactMethod
  public void finishTransactionAsync(String purchaseToken, Boolean consume, final Promise promise) {
    if (consume != null && consume) {
      mBillingManager.consumeAsync(purchaseToken, promise);
    } else {
      mBillingManager.acknowledgePurchaseAsync(purchaseToken, promise);
    }
  }

  @ReactMethod
  public void disconnectAsync(final Promise promise) {
    if (mBillingManager != null) {
      mBillingManager.destroy();
      mBillingManager = null;
    }
    promise.resolve(null);
  }

  public void emit(String eventName, @Nullable WritableMap params) {
    Log.d(TAG, "emit " + eventName + " " + params);
    getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
  }
}
