package expo.modules.inapppurchases;

import java.util.ArrayList;
import java.util.List;

import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.Purchase;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.WritableNativeArray;

/**
 * Handler to billing updates
 */
public class UpdateListener implements BillingManager.BillingUpdatesListener {
  private static final String TAG = "UpdateListener";
  private InAppPurchasesModule mInAppPurchasesModule;

  public UpdateListener(InAppPurchasesModule inAppPurchasesModule) {
    mInAppPurchasesModule = inAppPurchasesModule;
  }

  @Override
  public void onBillingClientSetupFinished() {
  }

  @Override
  public void onConsumeFinished(String token, BillingResult result) {
    WritableNativeMap response = new WritableNativeMap();
    response.putInt("responseCode", result.getResponseCode());
    response.putString("token", token);

    Promise promise = BillingManager.promises.get(BillingManager.ACKNOWLEDGING_PURCHASE);
    if (promise != null) {
      BillingManager.promises.put(BillingManager.ACKNOWLEDGING_PURCHASE, null);
      promise.resolve(response);
    }
  }

  @Override
  public void onPurchasesUpdated(List<Purchase> purchaseList) {
    WritableNativeMap response = new WritableNativeMap();
    WritableNativeArray results = new WritableNativeArray();
    for (Purchase purchase : purchaseList) {
      results.pushMap(BillingManager.purchaseToWritableMap(purchase));
    }
    response.putArray("results", results);
    response.putInt("responseCode", BillingResponseCode.OK);

    mInAppPurchasesModule.emit(BillingManager.PURCHASES_UPDATED_EVENT, response);
  }
}
