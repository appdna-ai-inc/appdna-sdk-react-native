import {
  AppdnaModule as AppdnaBillingModule,
  addNativeListener,
  setDelegateListeners,
} from './nativeModule';
import type { AppDNABillingDelegate } from './generated/delegates';

/**
 * The wire shape is the **union** of the iOS and Android entitlement models, with the keys the
 * running platform has no concept of **omitted, never faked**. `isActive` is synthesised on Android
 * from `status`; dates cross as ISO-8601 strings.
 *
 * 🔴 This type used to declare `{productId, store, status, expiresAt, isTrial, offerType}` — all
 * REQUIRED — and it matched NEITHER mapper:
 *
 *   - iOS `AppdnaMappers.map(_ entitlement:)` emits `{identifier, productId, isActive, expiresAt?}`.
 *   - Android `AppdnaMappers.map(entitlement)` emits that plus `store`, `status`, `isTrial`,
 *     `offerType?`.
 *
 * `identifier` and `isActive` — the two keys BOTH platforms send, and the only ones a host can act on
 * cross-platform — were not on the type at all, so a host could not read them without a cast. And
 * `store` / `status` / `isTrial` / `offerType` were typed non-optional while being **absent** on iOS:
 * `if (e.status === 'active')` compiled and was ALWAYS FALSE there, and `e.expiresAt === null` never
 * fired on either platform, because a missing expiry is an OMITTED key (`undefined`), never `null`.
 *
 * The two mappers are not made to agree by faking: iOS's core `Entitlement` genuinely has no store /
 * status / trial / offer concept, and inventing `isTrial: false` for a user who IS in a trial is a
 * worse answer than "this platform does not know". So the TYPE now says what the wire says.
 *
 * **Ask `isActive`.** It is the cross-platform question, present on both, synthesised on Android from
 * the Play status vocabulary so a host never has to learn it.
 */
export type Entitlement = {
  /** Both platforms. Android aliases it to `productId` — it has no separate identifier. */
  identifier: string;
  /** Both platforms. */
  productId: string;
  /** Both platforms. Android synthesises it from `status`; iOS reports StoreKit's own flag. */
  isActive: boolean;
  /** ISO-8601. **Omitted when there is no expiry** — check `=== undefined`, never `=== null`. */
  expiresAt?: string;
  /** **Android only** — iOS's `Entitlement` carries no store field. */
  store?: string;
  /** **Android only** — the raw Play status (`active` | `trialing` | `grace_period` | …). */
  status?: string;
  /** **Android only.** */
  isTrial?: boolean;
  /** **Android only**, and omitted there too when the entitlement carries no offer. */
  offerType?: string;
};

/**
 * What a **successful** `purchase()` resolves with — the store transaction, exactly as both native
 * mappers emit it (`AppdnaMappers.map(_ tx:)` / `AppdnaMappers.map(tx:)`).
 *
 * It replaces a `PurchaseResult = {status, entitlement?}` union that no native has ever produced.
 * That type was fiction in both directions: `result.status === 'purchased'` was ALWAYS false after a
 * successful buy, `result.entitlement` was always `undefined`, and the other three statuses were
 * unreachable because a cancel / pending / failure **throws** natively and arrives here as a promise
 * REJECTION, never as a resolved value. Every `switch (result.status)` a host wrote fell through to
 * `default`.
 *
 * There is no `status` field because there is nothing for it to say: a resolved promise IS
 * "purchased", and anything else rejected.
 */
export type TransactionInfo = {
  /** App Store / Play transaction identifier. */
  transactionId: string;
  productId: string;
  /**
   * Epoch milliseconds as a decimal string (e.g. `"1721044800000"`), on BOTH platforms — Android's
   * Play `purchaseTime` verbatim, iOS's `Date` converted to match. Parse with
   * `new Date(Number(tx.purchaseDate))`. (It was ISO-8601 on iOS and epoch-millis on Android before —
   * `new Date(tx.purchaseDate)` gave `Invalid Date` on Android.)
   */
  purchaseDate: string;
  /** `production` | `sandbox` (iOS also reports `xcode`). */
  environment: string;
};

/**
 * A store product, as the two mappers emit it.
 *
 * ⚠ There is no `price: number`. Neither mapper has ever emitted one — both send **`priceMicros`**
 * (an integer: `9.99` → `9_990_000`), because iOS's native price is a `Decimal`, which is not
 * bridge-legal, and shipping it as a lossy `Double` would be worse than an integer both platforms
 * agree on. A host that wrote `product.price.toFixed(2)` against the old type crashed on
 * `undefined`; show `displayPrice` (already localized and currency-formatted) or divide
 * `priceMicros` by 1e6.
 *
 * The platform-specific keys are **omitted, never faked** (N11): an absent key means "this platform
 * has no concept of it".
 */
export type ProductInfo = {
  id: string;
  name: string;
  description: string;
  /** Localized, store-formatted price — e.g. "$9.99". The string to render. */
  displayPrice: string;
  /** Price × 1,000,000, as an integer. Both platforms. */
  priceMicros: number;
  /** ISO-4217. **Android only** — iOS's `ProductInfo` does not expose a currency code. */
  currencyCode?: string;
  /** **iOS only** — Play's `ProductDetails` does not surface this on the DTO. */
  isSubscription?: boolean;
  /** **Android only** — the base-plan offer token to pass back to `purchase()`. */
  offerToken?: string;
};

/**
 * The native `onEntitlementsChanged` payload. Events cross as objects — a bare array is not a legal
 * TurboModule event payload — so the facade unwraps once, here, rather than at four call sites that
 * each guessed differently.
 */
type EntitlementsChangedPayload = { entitlements: Entitlement[] };

/**
 * The core SDK only attaches its entitlement observer when the wrapper asks it to, so the first JS
 * listener has to start it. Idempotent on the native side; the flag just avoids the bridge hop.
 */
let entitlementObserverStarted = false;

/**
 * How many `onEntitlementsChanged` subscriptions are STILL LIVE.
 *
 * Not a debug counter — it is the whole fix for the shutdown hole below. Native `shutdown()` drops
 * every entitlement handler it holds (iOS `AppDNA.shutdown()`, Android's nulled billing manager), but
 * a JS subscriber's closure is untouched by that: it is still in the emitter, still expecting events,
 * and there is nobody left on the native side to send any.
 */
let liveEntitlementSubscribers = 0;

function ensureEntitlementObserver(): void {
  if (entitlementObserverStarted) return;
  entitlementObserverStarted = true;
  // Fire-and-forget: a rejection here means billing is unavailable, which the host learns from
  // `onBillingUnavailable`. Swallowing it would hide nothing that is not already reported.
  void AppdnaBillingModule.startEntitlementObserver();
}

/**
 * Forget that the observer was started. Called by `shutdown()`.
 *
 * The latch is what stops a re-subscribe storm — but it also meant that after
 * `shutdown()` → `configure()`, `startEntitlementObserver()` was never re-sent to native and
 * `onEntitlementsChanged` stayed dead for the rest of the process. The JS mirror of the Android
 * native defect where the same subscription was dropped.
 */
export function resetEntitlementObserver(): void {
  entitlementObserverStarted = false;
}

/**
 * 🔴 Re-arm native for the subscribers that never went away. Called by `configure()`.
 *
 * `resetEntitlementObserver()` only clears the latch — it re-opens the door for the NEXT subscriber
 * to start the observer. The normal integration has no next subscriber: a host subscribes ONCE at
 * startup and keeps that subscription for the life of the process. So across
 * `configure → shutdown → configure`, native had dropped its handlers, JS still held a live listener,
 * nothing re-sent `startEntitlementObserver()`, and `onEntitlementsChanged` never fired again — no
 * error, no log, just a renewal that silently stops unlocking the app.
 *
 * Idempotent: `ensureEntitlementObserver()` latches, and both natives' `startEntitlementObserver` is
 * remove-then-add, so a spurious call cannot stack a second handler.
 */
export function resumeEntitlementObserver(): void {
  if (liveEntitlementSubscribers > 0) ensureEntitlementObserver();
}

/** Test seam: forget the latch AND the live subscribers, so a suite starts from a clean process. */
export function __resetEntitlementObserverForTesting(): void {
  entitlementObserverStarted = false;
  liveEntitlementSubscribers = 0;
  purchaseDeliveryReady = false;
}

/**
 * Whether a JS `onPurchaseCompleted` is registered — the latch behind the internal native
 * `billingDelegateReady`.
 *
 * The native billing forwarder is registered NOT delivering: the SDK's late-purchase queue (a purchase
 * reported at app start, after `identify`, an interrupted or Ask-to-Buy purchase…) drains only into a
 * delegate that will really hand the purchase to the host, and a queued purchase emitted to JS with
 * no `onPurchaseCompleted` listening would be lost. So `setDelegate` tells native, on every call,
 * whether one is registered; a flip to true drains the queue. `configure()` re-sends the latest value
 * (native drops the forwarder on shutdown and builds a new one at configure), and `shutdown()` resets
 * it to false, as it drops the listeners.
 */
let purchaseDeliveryReady = false;

function sendBillingDelegateReady(ready: boolean): void {
  // A SYNC void native method: guard the get-trap's synchronous throw (module absent / torn down).
  // Before native configure the call is a no-op on both platforms; configure() re-sends.
  try {
    AppdnaBillingModule.billingDelegateReady(ready);
  } catch {
    // No native module — nothing can be delivered either way.
  }
}

/** Called by `configure()` once native is configured: re-send the latest answer. */
export function resendBillingDelegateReady(): void {
  sendBillingDelegateReady(purchaseDeliveryReady);
}

/** Called by `shutdown()`: the listeners are gone, so nothing is ready to receive a delivery. */
export function resetBillingDelegateReady(): void {
  purchaseDeliveryReady = false;
}

/**
 * Billing bridge for AppDNA in-app purchases.
 *
 * Provides purchase, restore, product info, and entitlement streaming
 * via native modules that delegate to iOS/Android SDKs.
 */
/**
 * The `code` on a `purchase()` rejection. **Identical on both platforms**, because it IS the SDK's own
 * `billingErrorType(_:)` discriminator verbatim — the same string the paywall delegate already gets as
 * `onPaywallPurchaseFailed(errorType:)` and the same one the `purchase_failed` event carries into the
 * warehouse. There is no translation table between native and JS, so there is nothing that can fork.
 *
 * 🔴 Every one of these used to arrive as `PURCHASE_ERROR` with a LOCALIZED message. A host that
 * wanted to do the one thing every store app does — say nothing when the user taps Cancel, show a
 * retry when the card is declined — had to string-match `err.message`, in whatever language the
 * device happened to be set to.
 */
export type AppDNAPurchaseErrorCode =
  /** The user dismissed the store sheet. Almost always: show nothing. */
  | 'userCancelled'
  /** Deferred / ask-to-buy. The purchase is NOT complete; entitlements arrive later, if approved. */
  | 'pending'
  /** The product id is not in the store catalog (typo, or not yet approved). A config bug. */
  | 'productNotFound'
  /** Receipt verification failed server-side — signature, state, or a refund. Do not grant. */
  | 'verificationFailed'
  | 'networkError'
  | 'serverError'
  /**
   * The SDK does not buy or restore under this `billingProvider`: `revenueCat`, Adapty (Android, and
   * iOS without Adapty linked) and `none` leave transactions to your own billing SDK. On a paywall tap,
   * start the purchase with your provider; for a restore, restore through it.
   */
  | 'providerNotAvailable'
  | 'unknown';

export class AppDNABilling {
  /**
   * Purchase a product by its store product ID.
   * On Android, pass offerToken for subscription offers (base plan tokens).
   *
   * Resolves with the {@link TransactionInfo} on success. A user cancellation, a pending
   * (deferred / ask-to-buy) purchase, and a store failure all **reject** — natively they throw.
   * The rejection's `code` is an {@link AppDNAPurchaseErrorCode}: branch on it, never on the message.
   */
  static async purchase(
    productId: string,
    offerToken?: string
  ): Promise<TransactionInfo> {
    return AppdnaBillingModule.purchase(productId, offerToken) as Promise<TransactionInfo>;
  }

  /**
   * Restore previously purchased products.
   * Syncs with the App Store / Google Play and returns the restored product IDs. Rejects with an
   * `AppDNAPurchaseErrorCode` — `providerNotAvailable` under `revenueCat` / Adapty / `none`, and on
   * Android `storeKit2` `networkError` / `serverError`.
   */
  static async restorePurchases(): Promise<string[]> {
    return AppdnaBillingModule.restorePurchases() as Promise<string[]>;
  }

  /**
   * Get localized product information from the store.
   * Pass a list of product IDs configured in App Store Connect / Google Play Console.
   */
  static async getProducts(productIds: string[]): Promise<ProductInfo[]> {
    return AppdnaBillingModule.getProducts(productIds) as Promise<ProductInfo[]>;
  }

  /**
   * Check if the user has an active subscription.
   */
  static async hasActiveSubscription(): Promise<boolean> {
    return AppdnaBillingModule.hasActiveSubscription();
  }

  /**
   * Listen for entitlement changes (purchases, renewals, revocations).
   * Returns an unsubscribe function.
   */
  static onEntitlementsChanged(
    callback: (entitlements: Entitlement[]) => void
  ): () => void {
    ensureEntitlementObserver();
    const sub = addNativeListener<EntitlementsChangedPayload>('onEntitlementsChanged', (data) =>
      callback(data.entitlements ?? []),
    );
    // Counted so `configure()` can re-arm native for a subscriber that outlived a `shutdown()`.
    liveEntitlementSubscribers += 1;
    let removed = false;
    return () => {
      // Guarded: an unsubscribe called twice must not decrement twice, or a still-live subscriber
      // goes uncounted and stays dead after the next shutdown → configure.
      if (removed) return;
      removed = true;
      liveEntitlementSubscribers -= 1;
      sub.remove();
    };
  }

  /**
   * Get all current entitlements for the user.
   */
  static async getEntitlements(): Promise<Entitlement[]> {
    return AppdnaBillingModule.getEntitlements() as Promise<Entitlement[]>;
  }

  /**
   * Set a delegate to receive billing lifecycle callbacks.
   */
  static setDelegate(delegate: Partial<AppDNABillingDelegate> | null): void {
    // Replaces the previous delegate's listeners. Without this, a remount stacked another set and one
    // `onPurchaseCompleted` invoked every delegate ever registered — N entitlement grants for one buy.
    //
    // A purchase may be delivered LATER through `onPurchaseCompleted` — at app start, after
    // `identify`, or right here, when a JS `onPurchaseCompleted` is registered. Grant idempotently by
    // `transaction.transactionId`: delivery is at least once.
    purchaseDeliveryReady = typeof delegate?.onPurchaseCompleted === 'function';
    setDelegateListeners('billing', () => {
      // `null` CLEARS the delegate: `setDelegateListeners` has already removed the previous slot's
      // subscriptions and host callbacks, so installing nothing is exactly "no delegate". An RN host
      // could not unset a delegate at all before — the signature was non-nullable, while iOS and
      // Android both take one that can be nil/null.
      if (!delegate) return [];
      const subs = [];
      if (delegate.onPurchaseCompleted) {
        subs.push(addNativeListener<{ productId: string; transaction: Record<string, unknown> }>(
          'onPurchaseCompleted',
          (data) => delegate.onPurchaseCompleted!(data.productId, data.transaction ?? {}),
        ));
      }
      if (delegate.onPurchaseFailed) {
        subs.push(addNativeListener<{ productId: string; error: string }>('onPurchaseFailed', (data) =>
          delegate.onPurchaseFailed!(data.productId, data.error),
        ));
      }
      if (delegate.onEntitlementsChanged) {
        ensureEntitlementObserver();
        subs.push(addNativeListener<EntitlementsChangedPayload>('onEntitlementsChanged', (data) =>
          delegate.onEntitlementsChanged!(data.entitlements ?? []),
        ));
      }
      if (delegate.onRestoreCompleted) {
        subs.push(addNativeListener<{ restoredProducts: string[] }>('onRestoreCompleted', (data) =>
          delegate.onRestoreCompleted!(data.restoredProducts ?? []),
        ));
      }
      if (delegate.onBillingUnavailable) {
        // Android-only. iOS's billing protocol has no such method, so this listener is registered
        // on both platforms and fires on one. Documented rather than faked.
        subs.push(addNativeListener('onBillingUnavailable', () => delegate.onBillingUnavailable!()));
      }
      return subs;
    });
    // After the listeners exist, so a delivery the flip drains finds its `onPurchaseCompleted`.
    sendBillingDelegateReady(purchaseDeliveryReady);
  }
}
