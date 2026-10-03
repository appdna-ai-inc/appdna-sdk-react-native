// The example host's OWN StoreKit calls, for the billing-ownership device rows.
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
  // Products whose host buy came back `deferred` (Ask to Buy) in THIS session: their promise is already
  // resolved, but the later purchased / failed update is still this host's and is logged as a host buy.
  NSMutableSet<NSString *> *_deferred;
  // Products with a deferred transaction this session did NOT start: rebuilt from the queue in `init`
  // or re-delivered by `updatedTransactions`. StoreKit 1 does not say who queued a payment, so such a
  // transaction may be an earlier session's host buy OR the SDK's own Ask-to-Buy purchase. It blocks a
  // second `hostBuy` of the product (conservative), but its outcome is logged with a neutral prefix —
  // never as a host buy — and is never finished: finishing an SDK-started transaction would take it
  // away from the SDK.
  NSMutableSet<NSString *> *_deferredInherited;
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
    _deferredInherited = [NSMutableSet new];
    [[SKPaymentQueue defaultQueue] addTransactionObserver:self];
    // A deferred (Ask to Buy) transaction outlives the process: it stays in the payment queue until it
    // is approved or declined. Rebuild the set from the queue so the "refused already deferred" guard in
    // `hostBuy` also holds after a relaunch, not only within one session. It goes into
    // `_deferredInherited`: a deferred SDK purchase of the same product looks identical (conservative for
    // the refusal; the device rows start no SDK Ask-to-Buy). `SKPaymentQueue.transactions` is only valid
    // while the queue has an observer, so this runs AFTER `addTransactionObserver:`; a deferred
    // transaction the queue re-delivers through `updatedTransactions` later is added there too.
    //
    // Known gap: a deferred buy APPROVED while the app was killed comes back as `purchased`, never as
    // `deferred`, so it is in neither set and is not logged as an outcome here. It stays unfinished and
    // `logUnfinished` still lists it.
    for (SKPaymentTransaction *t in [SKPaymentQueue defaultQueue].transactions) {
      if (t.transactionState == SKPaymentTransactionStateDeferred) {
        [_deferredInherited addObject:t.payment.productIdentifier];
      }
    }
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
    // A deferred (Ask to Buy) buy of this product is still awaiting approval in the payment queue:
    // refused the same way, so the product is never queued for payment a second time.
    if ([self->_deferred containsObject:productId] || [self->_deferredInherited containsObject:productId]) {
      NSLog(@"AppDNA-E2E hostBuy %@ refused already deferred", productId);
      resolve(@"refused: a hostBuy for this product is deferred, awaiting approval");
      return;
    }
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
      // A deferred (Ask to Buy) transaction with no pending buy — the queue re-delivering one from an
      // earlier session (or the SDK's own): remember it, so `hostBuy` refuses a second buy of the product
      // (same conservative rule as the rebuild in `init`).
      if (t.transactionState == SKPaymentTransactionStateDeferred) {
        if (![_deferred containsObject:pid]) [_deferredInherited addObject:pid];
        continue;
      }
      // The later outcome of a deferred transaction this session did not start: it may be the SDK's, so
      // it is logged neutrally, not attributed to the host, and never finished.
      if ([_deferredInherited containsObject:pid] && ![_deferred containsObject:pid]) {
        if (t.transactionState == SKPaymentTransactionStatePurchased) {
          [_deferredInherited removeObject:pid];
          NSLog(@"AppDNA-E2E deferred-outcome %@ purchased %@ (origin unknown)", pid, t.transactionIdentifier);
          [self logUnfinished];
        } else if (t.transactionState == SKPaymentTransactionStateFailed) {
          [_deferredInherited removeObject:pid];
          NSLog(@"AppDNA-E2E deferred-outcome %@ failed %@ (origin unknown)", pid, t.error.localizedDescription);
          // Deliberately NOT finished, even though it failed: its origin is unknown and it may be the
          // SDK's, and the host finishes only transactions it started (as for the purchased case above).
        }
        continue;
      }
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
