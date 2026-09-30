// SPEC-497 §3.11 — the example host's OWN StoreKit calls, for the billing-ownership device rows.
//
// `hostBuy(productId)` buys through the host's own StoreKit payment queue and deliberately NEVER
// finishes the transaction — the shape of an app whose billing SDK (not AppDNA) owns transactions.
// `logTransactions()` writes `AppDNA-E2E unfinished=<productId:transactionId,…>` and
// `AppDNA-E2E all=<…>` to the host log from StoreKit 2's `Transaction.unfinished` / `Transaction.all`
// (AppdnaE2EStoreKit.swift, the same lines the Flutter example writes); it also runs on every foreground. Under a non-owning
// `billingProvider` the device rows assert the host's purchase is still listed after a relaunch.
//
// The purchase itself uses StoreKit 1 (callable from Objective-C); it shares the transaction store with
// the SDK's StoreKit 2 calls. Only transactions for products THIS host asked to buy are logged as a
// host buy — the payment-queue observer also sees the SDK's own purchases. Example-only code: nothing
// here ships in the SDK.

#import <React/RCTBridgeModule.h>
#import <StoreKit/StoreKit.h>
#import <UIKit/UIKit.h>
#import "AppdnaExample-Swift.h"

@interface AppdnaE2EHost : NSObject <RCTBridgeModule, SKPaymentTransactionObserver, SKProductsRequestDelegate>
@end

@implementation AppdnaE2EHost {
  NSMutableDictionary<NSString *, RCTPromiseResolveBlock> *_pending;
  NSMutableArray<SKProductsRequest *> *_requests;
  NSMutableDictionary<NSValue *, NSString *> *_requestProducts;
  // Products whose host buy came back `deferred` (Ask to Buy): their promise is already resolved, but
  // the later purchased / failed update is still THIS host's and is logged.
  NSMutableSet<NSString *> *_deferred;
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup { return YES; }

- (instancetype)init
{
  if ((self = [super init])) {
    _pending = [NSMutableDictionary new];
    _requests = [NSMutableArray new];
    _requestProducts = [NSMutableDictionary new];
    _deferred = [NSMutableSet new];
    [[SKPaymentQueue defaultQueue] addTransactionObserver:self];
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(logUnfinished)
                                                 name:UIApplicationDidBecomeActiveNotification
                                               object:nil];
    // Once at creation too: the module can be created after the first didBecomeActive has fired (a
    // cold launch), so the relaunch rows would otherwise see no `unfinished=` line until the next
    // foreground.
    [self logUnfinished];
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
    // One host buy per product at a time: a second call while the first is in flight is REFUSED (its own
    // promise resolves at once), so the first promise is never overwritten and stranded, and the
    // product is never queued for payment twice.
    if (self->_pending[productId] != nil) {
      NSLog(@"AppDNA-E2E hostBuy %@ refused already in flight", productId);
      resolve(@"refused: a hostBuy for this product is already in flight");
      return;
    }
    [self->_deferred removeObject:productId];
    self->_pending[productId] = resolve;
    SKProductsRequest *request = [[SKProductsRequest alloc] initWithProductIdentifiers:[NSSet setWithObject:productId]];
    request.delegate = self;
    [self->_requests addObject:request];
    self->_requestProducts[[NSValue valueWithNonretainedObject:request]] = productId;
    [request start];
  });
}

RCT_EXPORT_METHOD(logTransactions:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  [AppdnaE2EStoreKit logTransactions:^{ resolve([NSNull null]); }];
}

- (void)request:(SKRequest *)request didFailWithError:(NSError *)error
{
  dispatch_async(dispatch_get_main_queue(), ^{
    NSValue *key = [NSValue valueWithNonretainedObject:request];
    NSString *pid = self->_requestProducts[key];
    [self->_requestProducts removeObjectForKey:key];
    [self->_requests removeObject:(SKProductsRequest *)request];
    if (pid == nil) return;
    NSLog(@"AppDNA-E2E hostBuy %@ failed %@", pid, error.localizedDescription);
    RCTPromiseResolveBlock resolve = self->_pending[pid];
    [self->_pending removeObjectForKey:pid];
    if (resolve) resolve([NSString stringWithFormat:@"failed: %@", error.localizedDescription]);
  });
}

- (void)productsRequest:(SKProductsRequest *)request didReceiveResponse:(SKProductsResponse *)response
{
  dispatch_async(dispatch_get_main_queue(), ^{
    NSValue *key = [NSValue valueWithNonretainedObject:request];
    NSString *requested = self->_requestProducts[key];
    [self->_requestProducts removeObjectForKey:key];
    [self->_requests removeObject:request];
    SKProduct *product = response.products.firstObject;
    if (product == nil) {
      // The invalid ids AND the id this request asked for: a response with no products and no invalid
      // ids used to leave the promise pending forever.
      NSMutableSet<NSString *> *unresolved = [NSMutableSet setWithArray:response.invalidProductIdentifiers];
      if (requested != nil) [unresolved addObject:requested];
      for (NSString *pid in unresolved) {
        RCTPromiseResolveBlock resolve = self->_pending[pid];
        [self->_pending removeObjectForKey:pid];
        if (resolve) {
          NSLog(@"AppDNA-E2E hostBuy %@ failed no such product", pid);
          resolve(@"no such product");
        }
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
    if (resolve == nil) {
      // The later outcome of a deferred (Ask to Buy) host buy: logged, never finished when purchased.
      if ([_deferred containsObject:pid]) {
        if (t.transactionState == SKPaymentTransactionStatePurchased) {
          [_deferred removeObject:pid];
          NSLog(@"AppDNA-E2E hostBuy %@ %@ (after deferred)", pid, t.transactionIdentifier);
          [self logUnfinished];
        } else if (t.transactionState == SKPaymentTransactionStateFailed) {
          [_deferred removeObject:pid];
          NSLog(@"AppDNA-E2E hostBuy %@ failed %@ (after deferred)", pid, t.error.localizedDescription);
          [queue finishTransaction:t];
        }
      }
      // Otherwise not a purchase THIS host started (the observer also sees the SDK's own purchases).
      continue;
    }
    switch (t.transactionState) {
      case SKPaymentTransactionStatePurchased:
        // Deliberately NOT finished.
        NSLog(@"AppDNA-E2E hostBuy %@ %@", pid, t.transactionIdentifier);
        [_pending removeObjectForKey:pid];
        resolve(t.transactionIdentifier ?: @"");
        [self logUnfinished];
        break;
      case SKPaymentTransactionStateFailed:
        NSLog(@"AppDNA-E2E hostBuy %@ failed %@", pid, t.error.localizedDescription);
        [_pending removeObjectForKey:pid];
        resolve([NSString stringWithFormat:@"failed: %@", t.error.localizedDescription]);
        [queue finishTransaction:t]; // a failed transaction carries no purchase; clearing it keeps the list honest
        break;
      case SKPaymentTransactionStateDeferred:
        // Ask to Buy: waiting on a parent's approval, possibly for days. Resolve now rather than leave
        // the promise pending; the eventual purchased / failed update is logged above.
        NSLog(@"AppDNA-E2E hostBuy %@ deferred", pid);
        [_pending removeObjectForKey:pid];
        [_deferred addObject:pid];
        resolve(@"deferred");
        break;
      case SKPaymentTransactionStateRestored:
        // Only `restoreCompletedTransactions` produces this, and this host never calls it (the SDK
        // restores through StoreKit 2): it is not the outcome of this buy. Logged, left unfinished (the
        // host never finishes a purchase), and the pending buy keeps waiting for its own update.
        NSLog(@"AppDNA-E2E hostBuy %@ restored %@ (ignored, buy still pending)", pid,
              t.originalTransaction.transactionIdentifier ?: t.transactionIdentifier);
        break;
      case SKPaymentTransactionStatePurchasing:
        break;
    }
  }
}

- (void)logUnfinished
{
  [AppdnaE2EStoreKit logTransactions:nil];
}

@end
