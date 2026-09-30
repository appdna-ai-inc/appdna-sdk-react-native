// SPEC-497 §3.11 — the example host's OWN StoreKit calls, for the billing-ownership device rows.
//
// `hostBuy(productId)` buys through the host's own StoreKit payment queue and deliberately NEVER
// finishes the transaction — the shape of an app whose billing SDK (not AppDNA) owns transactions.
// `logTransactions()` writes `AppDNA-E2E unfinished=<productId:transactionId,…>` to the host log, from
// the payment queue's unfinished transactions; it also runs on every foreground. Under a non-owning
// `billingProvider` the device rows assert the host's purchase is still listed after a relaunch.
//
// StoreKit 1 on purpose: it is callable from Objective-C, needs no Swift in this target, and shares the
// transaction store with the SDK's StoreKit 2 calls (a StoreKit 2 `finish()` removes it from this
// queue too). Example-only code: nothing here ships in the SDK.

#import <React/RCTBridgeModule.h>
#import <StoreKit/StoreKit.h>
#import <UIKit/UIKit.h>

@interface AppdnaE2EHost : NSObject <RCTBridgeModule, SKPaymentTransactionObserver, SKProductsRequestDelegate>
@end

@implementation AppdnaE2EHost {
  NSMutableDictionary<NSString *, RCTPromiseResolveBlock> *_pending;
  NSMutableArray<SKProductsRequest *> *_requests;
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup { return YES; }

- (instancetype)init
{
  if ((self = [super init])) {
    _pending = [NSMutableDictionary new];
    _requests = [NSMutableArray new];
    [[SKPaymentQueue defaultQueue] addTransactionObserver:self];
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(logUnfinished)
                                                 name:UIApplicationDidBecomeActiveNotification
                                               object:nil];
  }
  return self;
}

- (void)dealloc
{
  [[SKPaymentQueue defaultQueue] removeTransactionObserver:self];
  [[NSNotificationCenter defaultCenter] removeObserver:self];
}

RCT_EXPORT_METHOD(hostBuy:(NSString *)productId
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    self->_pending[productId] = resolve;
    SKProductsRequest *request = [[SKProductsRequest alloc] initWithProductIdentifiers:[NSSet setWithObject:productId]];
    request.delegate = self;
    [self->_requests addObject:request];
    [request start];
  });
}

RCT_EXPORT_METHOD(logTransactions:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    [self logUnfinished];
    resolve([NSNull null]);
  });
}

- (void)productsRequest:(SKProductsRequest *)request didReceiveResponse:(SKProductsResponse *)response
{
  dispatch_async(dispatch_get_main_queue(), ^{
    [self->_requests removeObject:request];
    SKProduct *product = response.products.firstObject;
    if (product == nil) {
      for (NSString *pid in response.invalidProductIdentifiers) {
        RCTPromiseResolveBlock resolve = self->_pending[pid];
        [self->_pending removeObjectForKey:pid];
        if (resolve) resolve(@"no such product");
      }
      return;
    }
    [[SKPaymentQueue defaultQueue] addPayment:[SKPayment paymentWithProduct:product]];
  });
}

- (void)paymentQueue:(SKPaymentQueue *)queue updatedTransactions:(NSArray<SKPaymentTransaction *> *)transactions
{
  for (SKPaymentTransaction *t in transactions) {
    NSString *pid = t.payment.productIdentifier;
    RCTPromiseResolveBlock resolve = _pending[pid];
    if (t.transactionState == SKPaymentTransactionStatePurchased) {
      // Deliberately NOT finished.
      NSLog(@"AppDNA-E2E hostBuy %@ %@", pid, t.transactionIdentifier);
      if (resolve) { [_pending removeObjectForKey:pid]; resolve(t.transactionIdentifier ?: @""); }
      [self logUnfinished];
    } else if (t.transactionState == SKPaymentTransactionStateFailed) {
      NSLog(@"AppDNA-E2E hostBuy %@ failed %@", pid, t.error.localizedDescription);
      if (resolve) { [_pending removeObjectForKey:pid]; resolve([NSString stringWithFormat:@"failed: %@", t.error.localizedDescription]); }
      [queue finishTransaction:t]; // a failed transaction carries no purchase; clearing it keeps the list honest
    }
  }
}

- (void)logUnfinished
{
  NSMutableArray<NSString *> *ids = [NSMutableArray new];
  for (SKPaymentTransaction *t in [SKPaymentQueue defaultQueue].transactions) {
    if (t.transactionState == SKPaymentTransactionStatePurchased || t.transactionState == SKPaymentTransactionStateRestored) {
      [ids addObject:[NSString stringWithFormat:@"%@:%@", t.payment.productIdentifier, t.transactionIdentifier]];
    }
  }
  NSLog(@"AppDNA-E2E unfinished=%@", [ids componentsJoinedByString:@","]);
}

@end
