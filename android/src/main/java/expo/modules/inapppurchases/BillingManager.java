package expo.modules.inapppurchases;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.ProductType;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.UnfetchedProduct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.WritableNativeArray;

/**
 * Handles all the interactions with Play Store (via Billing library), maintains connection to
 * it through BillingClient and caches temporary states/data if needed
 */
public class BillingManager implements PurchasesUpdatedListener {
  private static final String TAG = "BillingManager";

  public static final int OK = 0;
  public static final int USER_CANCELED = 1;
  public static final int ERROR = 2;

  public static final int BILLING_MANAGER_NOT_INITIALIZED = -1;
  public static final String PURCHASES_UPDATED_EVENT = "Expo.purchasesUpdated";
  public static final String ACKNOWLEDGING_PURCHASE = "Acknowledging Item";
  public static final String INAPP_SUB_PERIOD = "P0D";
  private int mBillingClientResponseCode = BILLING_MANAGER_NOT_INITIALIZED;

  protected static final HashMap<String, Promise> promises = new HashMap<>();
  private final List<Purchase> mPurchases = new ArrayList<>();
  private final HashMap<String, ProductDetails> mProductDetailsMap = new HashMap<>();
  private final HashMap<String, String> mProductOfferTokensMap = new HashMap<>();

  private BillingClient mBillingClient;
  private boolean mIsServiceConnected;
  private final Activity mActivity;
  private BillingUpdatesListener mBillingUpdatesListener;
  private InAppPurchasesModule mInAppPurchasesModule;
  private Set<String> mTokensToBeConsumed;

  /**
   * Listener to the updates that happen when purchases list was updated or consumption of the
   * item was finished
   */
  public interface BillingUpdatesListener {
    void onBillingClientSetupFinished();

    void onConsumeFinished(String token, BillingResult result);

    void onPurchasesUpdated(List<Purchase> purchases);
  }

  public BillingManager(Activity activity, InAppPurchasesModule inAppPurchasesModule) {
    mActivity = activity;
    mInAppPurchasesModule = inAppPurchasesModule;
    mBillingUpdatesListener = new UpdateListener(inAppPurchasesModule);

    mBillingClient =
      BillingClient
        .newBuilder(activity)
        .enablePendingPurchases(
          PendingPurchasesParams.newBuilder()
          .enableOneTimeProducts()
          .enablePrepaidPlans()
          .build()
        )
        .enableAutoServiceReconnection()
        .setListener(this)
        .build();
  }

  public void startConnection(final Promise promise) {
    // Start setup. This is asynchronous and the specified listener will be called
    // once setup completes.
    // It also starts to report all the new purchases through onPurchasesUpdated() callback.
    startServiceConnection(new Runnable() {
      @Override
      public void run() {
        // Notifying the listener that billing client is ready
        mBillingUpdatesListener.onBillingClientSetupFinished();
        promise.resolve(null);
      }
    });
  }

  public void startServiceConnection(final Runnable executeOnSuccess) {
    mBillingClient.startConnection(new BillingClientStateListener() {
      @Override
      public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        final int responseCode = billingResult.getResponseCode();
        if (responseCode == BillingResponseCode.OK) {
          mIsServiceConnected = true;
          if (executeOnSuccess != null) {
            executeOnSuccess.run();
          }
        }
        mBillingClientResponseCode = responseCode;
      }

      @Override
      public void onBillingServiceDisconnected() {
        mIsServiceConnected = false;
      }
    });
  }

  /**
   * Start a purchase or subscription replace flow
   */
  public void purchaseItemAsync(final String productId, @Nullable final ReadableMap details, final Promise promise) {
    String oldPurchaseToken = details != null ? details.getString("oldPurchaseToken") : null;
    ReadableMap accountIdentifiers = details != null ? details.getMap("accountIdentifiers") : null;

    Runnable purchaseFlowRequest = new Runnable() {
      @Override
      public void run() {
        ProductDetails productDetails = mProductDetailsMap.get(productId);
        if (productDetails == null) {
          promise.reject("E_ITEM_NOT_QUERIED", "Must query item from store before calling purchase");
          return;
        }

        BillingFlowParams.ProductDetailsParams.Builder productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails);
        if(mProductOfferTokensMap.containsKey(productId)) {
          productDetailsParams.setOfferToken(mProductOfferTokensMap.get(productId));
        }

        java.util.List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
        productDetailsParamsList.add(productDetailsParams.build());

        BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParamsList);

        if (oldPurchaseToken != null) {
          purchaseParams.setSubscriptionUpdateParams(
            BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(oldPurchaseToken).build()
          );
        }

        /*
         * For Android billing to work without a 'Something went wrong on our end. Please try again.'
         * error, we must provide BOTH obfuscatedAccountId and obfuscatedProfileId.
         */
        if (accountIdentifiers != null) {
          String obfuscatedAccountId = accountIdentifiers.getString("obfuscatedAccountId");
          String obfuscatedProfileId = accountIdentifiers.getString("obfuscatedProfileId");
          if (obfuscatedAccountId != null && obfuscatedProfileId != null) {
            purchaseParams.setObfuscatedAccountId(obfuscatedAccountId);
            purchaseParams.setObfuscatedProfileId(obfuscatedProfileId);
          }
        }

        mBillingClient.launchBillingFlow(mActivity, purchaseParams.build());
      }
    };

    executeServiceRequest(purchaseFlowRequest);
  }

  public Context getContext() {
    return mActivity;
  }

  /**
   * Handle a callback that purchases were updated from the Billing library
   */
  @Override
  public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
    if (result.getResponseCode() == BillingResponseCode.OK && purchases != null) {
      for (Purchase purchase : purchases) {
        handlePurchase(purchase);
      }
      mBillingUpdatesListener.onPurchasesUpdated(mPurchases);
    } else {
      mInAppPurchasesModule.emit(PURCHASES_UPDATED_EVENT, formatResponseToWritableMap(result, null));
    }
  }

  public void acknowledgePurchaseAsync(String purchaseToken, final Promise promise) {
    AcknowledgePurchaseResponseListener acknowledgePurchaseResponseListener = new AcknowledgePurchaseResponseListener() {
      @Override
      public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
        promise.resolve(formatResponseToWritableMap(billingResult, null));
      }
    };

    AcknowledgePurchaseParams acknowledgePurchaseParams =
      AcknowledgePurchaseParams.newBuilder()
        .setPurchaseToken(purchaseToken)
        .build();
    mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, acknowledgePurchaseResponseListener);
  }

  public void consumeAsync(final String purchaseToken, final Promise promise) {
    // If we've already scheduled to consume this token - no action is needed (this could happen
    // if you received the token when querying purchases inside onReceive() and later from
    // onActivityResult()
    if (mTokensToBeConsumed == null) {
      mTokensToBeConsumed = new HashSet<>();
    } else if (mTokensToBeConsumed.contains(purchaseToken)) {
      WritableMap response = new WritableNativeMap();
      response.putInt("responseCode", BillingClient.BillingResponseCode.OK);
      promise.resolve(response);
      return;
    }

    if (promises.get(ACKNOWLEDGING_PURCHASE) != null) {
      promise.reject("E_UNFINISHED_PROMISE", "Must wait for promise to resolve before recalling function.");
      return;
    }
    promises.put(ACKNOWLEDGING_PURCHASE, promise);
    mTokensToBeConsumed.add(purchaseToken);

    // Generating Consume Response listener
    final ConsumeResponseListener onConsumeListener = new ConsumeResponseListener() {
      @Override
      public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String purchaseToken) {
        // If billing service was disconnected, we try to reconnect 1 time
        mBillingUpdatesListener.onConsumeFinished(purchaseToken, billingResult);
      }
    };

    // Creating a runnable from the request to use it inside our connection retry policy below
    Runnable consumeRequest = new Runnable() {
      @Override
      public void run() {
        ConsumeParams consumeParams =
          ConsumeParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build();
        // Consume the purchase async
        mBillingClient.consumeAsync(consumeParams, onConsumeListener);
      }
    };

    executeServiceRequest(consumeRequest);
  }

  /**
   * Handles the purchase
   *
   * @param purchase Purchase to be handled
   */
  private void handlePurchase(Purchase purchase) {
    mPurchases.add(purchase);
  }

  /**
   * Returns the value Billing client response code or BILLING_MANAGER_NOT_INITIALIZED if the
   * client connection response was not received yet.
   */
  public int getBillingClientResponseCode() {
    return mBillingClientResponseCode;
  }

  /**
   * Query both in app purchases and subscriptions and deliver the result in a formalized way
   * through a listener
   */
  public void queryPurchases(final Promise promise) {
    Runnable queryToExecute = new Runnable() {
      @Override
      public void run() {
        List<Purchase> purchases = new ArrayList<>();

        mBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build(), (billingResult, inappResponse) -> {
          if (billingResult.getResponseCode() == BillingResponseCode.OK) {
            purchases.addAll(inappResponse);
          }

          mBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build(), (billingResult1, subsResponse) -> {
            if (billingResult1.getResponseCode() == BillingResponseCode.OK) {
              purchases.addAll(subsResponse);
            }

            onQueryPurchasesFinished(billingResult1, purchases, promise);
          });
        });
      }
    };

    executeServiceRequest(queryToExecute);
  }

  public static WritableMap formatResponseToWritableMap(BillingResult billingResult, ArrayList<WritableMap> results) {
    WritableMap response = new WritableNativeMap();
    int responseCode = billingResult.getResponseCode();
    if (responseCode == BillingResponseCode.OK) {
      response.putInt("responseCode", OK);
      WritableArray resultsArray = new WritableNativeArray();
      if(results != null) {
        for(WritableMap result : results) {
          resultsArray.pushMap(result);
        }
      }
      response.putArray("results", resultsArray);
    } else if (responseCode == BillingResponseCode.USER_CANCELED) {
      response.putInt("responseCode", USER_CANCELED);
    } else {
      response.putInt("responseCode", ERROR);
      response.putInt("errorCode", errorCodeNativeToJS(responseCode));
    }
    return response;
  }


  /**
   * Convert native error code to match corresponding TS enum
   */
  private static int errorCodeNativeToJS(int responseCode) {
    switch (responseCode) {
      case BillingResponseCode.ERROR:
        return 0; // UNKNOWN
      case BillingResponseCode.FEATURE_NOT_SUPPORTED:
        return 1; // PAYMENT_INVALID
      case BillingResponseCode.SERVICE_DISCONNECTED:
        return 2; // SERVICE_DISCONNECTED
      case BillingResponseCode.SERVICE_UNAVAILABLE:
        return 3; // SERVICE_UNAVAILABLE
      case BillingResponseCode.BILLING_UNAVAILABLE:
        return 5; // BILLING_UNAVAILABLE
      case BillingResponseCode.ITEM_UNAVAILABLE:
        return 6; // ITEM_UNAVAILABLE
      case BillingResponseCode.DEVELOPER_ERROR:
        return 7; // DEVELOPER_ERROR
      case BillingResponseCode.ITEM_ALREADY_OWNED:
        return 8; // ITEM_ALREADY_OWNED
      case BillingResponseCode.ITEM_NOT_OWNED:
        return 9; // ITEM_NOT_OWNED
      case BillingResponseCode.NETWORK_ERROR:
        return 10; // CLOUD_SERVICE
      case BillingResponseCode.USER_CANCELED:
        return 15; // USER_CANCELED
    }
    return 0;
  }

  /**
   * Convert native purchase state to match corresponding TS enum
   */
  private static int purchaseStateNativeToJS(int purchaseState) {
    switch (purchaseState) {
      case Purchase.PurchaseState.PENDING:
        return 0;
      case Purchase.PurchaseState.PURCHASED:
        return 1;
      case Purchase.PurchaseState.UNSPECIFIED_STATE:
        return 2;
    }
    return 0;

  }

  private static List<WritableMap> productToWritableMaps(ProductDetails productDetails) {
    int type = productDetails.getProductType().equals(ProductType.INAPP) ? 0 : 1;

    List<WritableMap> ret = new ArrayList<>();

    WritableMap baseMap = new WritableNativeMap();
    baseMap.putString("description", productDetails.getDescription());
    baseMap.putString("title", productDetails.getTitle());
    baseMap.putInt("type", type);

    ProductDetails.OneTimePurchaseOfferDetails ofd = productDetails.getOneTimePurchaseOfferDetails();
    List<ProductDetails.SubscriptionOfferDetails> offerList = productDetails.getSubscriptionOfferDetails();

    if(productDetails.getProductType().equals(ProductType.INAPP) && ofd != null) {
      // one-time purchase - single productId
      WritableMap map = new WritableNativeMap();
      map.merge(baseMap);
      map.putString("productId", productDetails.getProductId());
      map.putString("subscriptionPeriod", INAPP_SUB_PERIOD);
      map.putString("price", ofd.getFormattedPrice());
      map.putDouble("priceAmountMicros", ofd.getPriceAmountMicros());
      map.putString("priceCurrencyCode", ofd.getPriceCurrencyCode());
      ret.add(map);

    } else if(productDetails.getProductType().equals(ProductType.SUBS) && offerList != null) {
      // subscription - single ProductId, multiple offerTokens / offerIds
      for (ProductDetails.SubscriptionOfferDetails offer : offerList) {
        WritableMap map = new WritableNativeMap();
        map.merge(baseMap);

        map.putString("productId", productDetails.getProductId());
        map.putString("offerToken", offer.getOfferToken());
        map.putString("offerId", offer.getOfferId());

        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        ProductDetails.PricingPhase phase = phases.get(0);

        map.putString("subscriptionPeriod", phase.getBillingPeriod());
        map.putString("price", phase.getFormattedPrice());
        map.putDouble("priceAmountMicros", phase.getPriceAmountMicros());
        map.putString("priceCurrencyCode", phase.getPriceCurrencyCode());
        map.putInt("recurrenceMode", phase.getRecurrenceMode());

        ret.add(map);
      }
    }

    return ret;
  }

  public static WritableMap purchaseToWritableMap(Purchase purchase) {
    WritableMap writableMap = new WritableNativeMap();

    writableMap.putBoolean("acknowledged", purchase.isAcknowledged());
    writableMap.putString("orderId", purchase.getOrderId());
    writableMap.putString("productId", purchase.getProducts().get(0));
    writableMap.putInt("purchaseState", purchaseStateNativeToJS(purchase.getPurchaseState()));
    writableMap.putDouble("purchaseTime", purchase.getPurchaseTime());
    writableMap.putString("packageName", purchase.getPackageName());
    writableMap.putString("purchaseToken", purchase.getPurchaseToken());

    return writableMap;
  }

  /**
   * Handle a result from querying of purchases and report an updated list to the listener
   */
  private void onQueryPurchasesFinished(@NonNull BillingResult billingResult, List<Purchase> purchasesList, final Promise promise) {
    // Have we been disposed of in the meantime? If so, or bad result code, then quit
    if (mBillingClient == null || billingResult.getResponseCode() != BillingResponseCode.OK) {
      promise.reject("E_QUERY_FAILED", "Billing client was null or query was unsuccessful");
      return;
    }

    ArrayList<WritableMap> results = new ArrayList<>();
    for (Purchase purchase : purchasesList) {
      results.add(purchaseToWritableMap(purchase));
    }

    // Update purchases inventory with new list of purchases
    mPurchases.clear();
    onPurchasesUpdated(billingResult, purchasesList);

    Log.d(TAG, "onQueryPurchasesFinished RESOLVE: " + results.size());
    promise.resolve(formatResponseToWritableMap(billingResult, results));
  }

  @NonNull private BillingResult aggregateBillingResults(@NonNull Set<BillingResult> billingResults) {
    for (BillingResult result: billingResults) {
      if (result.getResponseCode() != BillingResponseCode.OK) {
        return result;
        }
      }
    return billingResults.iterator().next();
  }

  public void queryProductDetailsAsync(final List<String> skuList,
                                       final ProductDetailsResponseListener listener) {
    // Creating a runnable from the request to use it inside our connection retry policy below
    Runnable queryRequest = new Runnable() {
      @Override
      public void run() {
        // get the set of unique product IDs referenced in skuList

        // note: queryProductDetailsAsync() requires all products to be of the same type
        // so we make two calls, one for INAPP items and one for SUBS items
        // assuming we can't tell which is which just based on the skuList, we crudely attempt to query both types for each SKU

        Set<String> inappProductIds = new HashSet<>();
        Set<String> subsProductIds = new HashSet<>();

        for(String skuId : skuList) {
          if(true) {
            subsProductIds.add(skuId);
          }
          if(true) {
            inappProductIds.add(skuId);
          }
        }

        List<QueryProductDetailsParams.Product> inappProductList = new ArrayList<>();
        List<QueryProductDetailsParams.Product> subsProductList = new ArrayList<>();

        for(String productId : inappProductIds) {
          inappProductList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(ProductType.INAPP).build());
        }
        for(String productId : subsProductIds) {
          subsProductList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(ProductType.SUBS).build());
        }

        Set<BillingResult> results = new HashSet<>();
        List<ProductDetails> allProducts = new ArrayList<>();
        List<UnfetchedProduct> allUnfetchedProducts = new ArrayList<>();

        mBillingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(inappProductList).build(),
                (inappResult, inappProductDetails) -> {

                if(inappResult.getResponseCode() == BillingResponseCode.OK) {
                  allProducts.addAll(inappProductDetails.getProductDetailsList());
                  allUnfetchedProducts.addAll(inappProductDetails.getUnfetchedProductList());
                }
                results.add(inappResult);

                mBillingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(subsProductList).build(),
                          (subsResult, subsProductDetails) -> {
                            if (subsResult.getResponseCode() == BillingResponseCode.OK) {
                              allProducts.addAll(subsProductDetails.getProductDetailsList());
                              allUnfetchedProducts.addAll(subsProductDetails.getUnfetchedProductList());
                            }
                            results.add(subsResult);

                            listener.onProductDetailsResponse(
                              aggregateBillingResults(results),
                              QueryProductDetailsResult.create(allProducts, allUnfetchedProducts)
                            );
                });
        });
      }
    };

    executeServiceRequest(queryRequest);
  }

  public void queryPurchasableItems(List<String> itemList, final Promise promise) {
    queryProductDetailsAsync(itemList,
      new ProductDetailsResponseListener() {
        @Override
        public void onProductDetailsResponse(@NonNull BillingResult billingResult, @NonNull QueryProductDetailsResult productDetailsResult) {
          ArrayList<WritableMap> results = new ArrayList<>();
          for (ProductDetails productDetails : productDetailsResult.getProductDetailsList()) {
            mProductDetailsMap.put(productDetails.getProductId(), productDetails);
            if(productDetails.getSubscriptionOfferDetails() != null) {
              // add the first offerToken to the map
              List<ProductDetails.SubscriptionOfferDetails> details = productDetails.getSubscriptionOfferDetails();
              for(ProductDetails.SubscriptionOfferDetails offer : details) {
                mProductOfferTokensMap.put(productDetails.getProductId(), offer.getOfferToken());
                break;
              }
            }
            results.addAll(productToWritableMaps(productDetails));
          }
          Log.d(TAG, "queryPurchasableItems " + itemList.size() + " RESOLVE: " + results.size());
          promise.resolve(formatResponseToWritableMap(billingResult, results));
        }
      }
    );
  }

  private void executeServiceRequest(Runnable runnable) {
    if (mIsServiceConnected) {
      runnable.run();
    } else {
      // If billing service was disconnected, we try to reconnect 1 time.
      startServiceConnection(runnable);
    }
  }

  /**
   * Clear the resources
   */
  public void destroy() {
    if (mBillingClient != null && mBillingClient.isReady()) {
      mBillingClient.endConnection();
      mBillingClient = null;
    }
  }

}
