export interface WebEntitlement {
  isActive: boolean;
  planName?: string;
  priceId?: string;
  interval?: string;
  status: 'active' | 'trialing' | 'past_due' | 'canceled';
  currentPeriodEnd?: number;
  trialEnd?: number;
}

export interface DeferredDeepLink {
  screen: string;
  params: Record<string, string>;
  visitorId: string;
}

export interface PaywallContext {
  placement?: string;
  /**
   * 🔴 `experiment` and `variant` were MISSING from this type while BOTH natives parsed them
   * (ios/AppdnaModuleImpl.swift:766-767, android/.../AppdnaModule.kt:847-848). TypeScript rejects an
   * excess property, so a JS host could not pass them at all: the native side read fields the wrapper
   * made it impossible to send. Dead surface, wrapper-side — which is the harder direction to notice,
   * because nothing crashes and nothing logs. The paywall simply is not attributed to the experiment
   * that served it.
   */
  experiment?: string;
  variant?: string;
  customData?: Record<string, unknown>;
}

/**
 * The native enum is `production | sandbox` on BOTH platforms
 * (`Configuration.swift:4`, `Configuration.kt:12`). `'staging'` named a case that has never existed;
 * the iOS shim's `env == "staging" ? .staging : .production` could not compile, and a host passing
 * `'staging'` silently got production. Which environment you are in is decided by the API-key prefix
 * (`adn_test_` vs `adn_live_`) — this only selects the SDK's own environment tag.
 */
export type AppDNAEnvironment = 'production' | 'sandbox';

export type AppDNALogLevel = 'none' | 'error' | 'warning' | 'info' | 'debug';

/**
 * The billing provider that fulfils paywall purchases.
 *
 * 🔴 ADAPTY WAS UNREACHABLE FROM TYPESCRIPT. Both natives parse it — as a TAGGED MAP, because unlike
 * the others it carries an associated value (`AppdnaModuleImpl.swift`, `AppdnaModule.kt` →
 * `BillingProvider.fromWire`) — and this union listed only the three bare strings. So a React Native
 * host could not select Adapty at all, while iOS, Android and Flutter hosts could. Dead surface, in
 * the direction that is hardest to see: nothing crashes, TypeScript simply refuses a value it should
 * have accepted, and the feature looks absent rather than broken.
 *
 * Worse is what happened if a host forced a bare `'adapty'` past the type: neither native matches it
 * (a bare string carries no apiKey, so it is deliberately REFUSED rather than run keyless), both fall
 * through to the default — and purchases are silently routed to StoreKit / Play Billing instead of
 * Adapty. No error. The tagged form makes the apiKey a compile-time requirement, so the keyless case
 * cannot be written in the first place.
 */
export type AppDNABillingProvider =
  | 'storeKit2'
  | 'revenueCat'
  | 'none'
  /** Adapty needs its SDK key; a keyless Adapty is refused natively, so the type makes it impossible. */
  | { type: 'adapty'; apiKey: string };

export interface AppDNAOptions {
  /**
   * Automatic flush interval in seconds. When omitted, the server's value from the SDK's bootstrap
   * request (if positive), else 30. Passed to native only when set.
   */
  flushInterval?: number;
  /**
   * A cap on the events one upload sends and the queue length that triggers a flush; the batch is sized
   * by the network (100 on Wi-Fi or wired, 50 on cellular, 20 on an expensive / metered connection) and
   * never exceeds this. When omitted, the server's value from the bootstrap request (if positive) is the
   * cap, else there is none. Below 1 is ignored (as if not set). Passed to native only when set.
   */
  batchSize?: number;
  /**
   * Remote config cache TTL in seconds. When omitted, the server's value from the bootstrap request (if
   * positive), else 3600 (1 hour). Passed to native only when set.
   */
  configTTL?: number;
  /** Log verbosity. Default: 'warning'. */
  logLevel?: AppDNALogLevel;
  /** Billing provider for paywall purchases. Read by BOTH natives (it was documented "iOS only"
   *  after Android gained it). Default: 'storeKit2'. */
  billingProvider?: AppDNABillingProvider;
  /**
   * Seconds a host veto may take before native applies the hook's own default. Default 5.
   *
   * Both `parseOptions` implementations have always read this, and it was missing from this type —
   * so the timeout that all eight veto hooks depend on could not be set from TypeScript without an
   * error. A knob native reads and TS forbids is not a knob.
   */
  vetoTimeout?: number;
  /**
   * When true, analytics stay OFF until `setConsent(true)`, and no event — not even
   * `sdk_initialized` — is emitted before that decision.
   *
   * ⚠️ **Defaults to `false`, and that default is an opt-OUT contract.** Leave it unset and
   * `configure()` emits `sdk_initialized` (device, OS, locale, session context) **before the user
   * has decided anything**. That is the intended, documented behavior — it is what every shipped
   * version of this SDK has done, and changing the default would silently zero the analytics of
   * every existing host on upgrade — but it is *your* decision to accept, not ours to make for you.
   *
   * **If you ship into the EU/UK, or anywhere you need a lawful basis before the first byte leaves
   * the device, set `requireConsent: true`.** Nothing else in this SDK will hold the events back.
   *
   * ```ts
   * await AppDNA.configure(KEY, 'production', { requireConsent: true });
   * // …show your consent UI, then:
   * await AppDNA.setConsent(userSaidYes); // persisted across cold starts
   * ```
   */
  requireConsent?: boolean;
  /**
   * Android-only: the notification small-icon drawable resource id (an `R.drawable.*` int). Ignored
   * on iOS. Only useful when a native Android layer supplies the id.
   */
  notificationIcon?: number;
}
