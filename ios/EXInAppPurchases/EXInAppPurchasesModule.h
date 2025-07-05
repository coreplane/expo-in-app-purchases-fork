//  Copyright © 2018 650 Industries. All rights reserved.

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <StoreKit/StoreKit.h>

@interface EXInAppPurchasesModule : RCTEventEmitter <RCTBridgeModule, SKProductsRequestDelegate, SKPaymentTransactionObserver>
@end
