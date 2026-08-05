# AppDNA SDK for React Native

The official React Native SDK for [AppDNA](https://appdna.ai) — the growth console for subscription apps.

> ⚠️ **Proprietary software.** A Commercial Agreement with AppDNA AI, Inc. is required to use this SDK. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md).
>
> **Migrating from MIT-licensed v1.0.1 or earlier?** See [DEPRECATION_NOTICE.md](./DEPRECATION_NOTICE.md). MIT versions stop receiving server support after **15 May 2026**.

## What it does

AppDNA gives you a single drop-in SDK for the growth surfaces every subscription app needs, on both iOS and Android from one TypeScript codebase:

- **Analytics & events** — track user behavior with batched, offline-resilient delivery.
- **Experiments & feature flags** — server-driven A/B tests with deterministic variant assignment.
- **Paywalls** — render console-designed paywall layouts with native StoreKit 2 / Google Play Billing.
- **Onboarding flows** — multi-step onboarding with form inputs, async hooks, conditional branching, and rich media.
- **Surveys & feedback** — NPS, CSAT, free text, multi-choice with scheduling and frequency caps.
- **In-app messages** — modal, banner, fullscreen messages with audience targeting.
- **Push notifications** — rich content, action buttons, deep links, and delivery analytics.
- **Web entitlements & deep links** — server-validated entitlements and deferred deep linking.

## Requirements

- **React Native 0.76.9+ with the New Architecture enabled.** (RN 0.77+/Kotlin 2.0 is supported via a
  version-gated Compose config; on Expo SDK 53 the SDK installs but `<AppDNAScreenSlot>` needs a manual
  Fabric registration under dynamic frameworks — a warning explains how.) The SDK ships a TurboModule and a
  Fabric component; there is no legacy-bridge fallback, and it will not link on the old architecture.
- React 18.0+
- iOS 16.0+ (when targeting iOS)
- Android API 24+ (when targeting Android)

> **Versions 1.0.6 and earlier shipped no podspec and no Android Gradle module** and could not be
> linked into an app at all. Install `1.0.7` or later.

## Installation

```bash
npm install @appdna-ai/react-native-sdk
# or
yarn add @appdna-ai/react-native-sdk
```

For iOS, install the pods with the New Architecture switched on:

```bash
cd ios && RCT_NEW_ARCH_ENABLED=1 pod install && cd ..
```

On Android, set `newArchEnabled=true` in `android/gradle.properties`.

**Expo:** the package ships a config plugin — add `"plugins": ["@appdna-ai/react-native-sdk"]` to
`app.json` and run `npx expo prebuild`. Expo Go cannot host a native module; use a development build.

## Quick start

```typescript
import { AppDNA } from '@appdna-ai/react-native-sdk';

// Positional args: apiKey, environment, options. The environment is 'production' | 'sandbox'.
await AppDNA.configure('adn_live_xxx', 'production', { logLevel: 'warning' });
```

Track an event (fire-and-forget — it returns `void`, not a Promise):

```typescript
AppDNA.track('subscription_viewed', { plan_id: 'premium_monthly' });
```

Identify a user (after sign-in):

```typescript
await AppDNA.identify('user-123', { plan: 'premium' });
```

Present a paywall. Presentation and its outcome are two different things: `present()` resolves once
the paywall is on screen, and what the user then does arrives on the delegate.

```typescript
import { AppDNA, type AppDNAPaywallDelegate } from '@appdna-ai/react-native-sdk';

const delegate: AppDNAPaywallDelegate = {
  onPaywallPresented: () => {},
  onPaywallAction: () => {},
  onPaywallPurchaseStarted: () => {},
  onPaywallPurchaseCompleted: (paywallId, productId) => console.log('Purchased', productId),
  // `error` is a message string, and `errorType` is the stable reason code
  // (userCancelled | networkError | serverError | …). `productId` is null if none was selected.
  onPaywallPurchaseFailed: (paywallId, error, errorType) => console.log('Failed:', errorType, error),
  onPaywallRestoreStarted: () => {},
  onPaywallRestoreCompleted: () => {},
  onPaywallRestoreFailed: () => {},
  onPostPurchaseDeepLink: () => {},
  onPostPurchaseNextStep: () => {},
  onPaywallDismissed: () => {},
};

AppDNA.paywall.setDelegate(delegate);
await AppDNA.paywall.present('default', { placement: 'settings' });
```

## Documentation

Full integration guide, configuration reference, and API docs at **[docs.appdna.ai/sdks/react-native](https://docs.appdna.ai/sdks/react-native/installation)**.

## Support

- Technical questions: [support@appdna.ai](mailto:support@appdna.ai)
- Sales / commercial: [sales@appdna.ai](mailto:sales@appdna.ai)
- Licensing: [legal@appdna.ai](mailto:legal@appdna.ai)

## Reporting issues

- 🐞 **SDK bug or rendering issue** → [open an issue](https://github.com/appdna-ai-inc/appdna-sdk-react-native/issues/new/choose) using the bug report template.
- 🆕 **Missing capability / configuration gap** → use the capability gap template on the same page.
- 🔒 **Security vulnerability** → [report privately](https://github.com/appdna-ai-inc/appdna-sdk-react-native/security/advisories/new) — see [SECURITY.md](./SECURITY.md). Never in a public issue.
- 🖥️ **Console / platform issue** (not the SDK) → use the Support widget in the [console](https://console.appdna.ai).

> This repository is a read-only release mirror — development happens in our internal repository, so we don't accept pull requests here. Issues are very welcome and are triaged into our internal backlog; you'll see a comment on your issue when a fix ships.

## License

⚠️ **The AppDNA SDK is proprietary software, not open source.** This repository is publicly visible for marketing, evaluation, and reference purposes only.

**You may NOT** download, install, run, modify, or use the SDK without a Commercial Agreement with AppDNA AI, Inc. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md) for the full terms.

**You MAY** view the source on GitHub and read the documentation at <https://docs.appdna.ai> for evaluation purposes.

To use the SDK in your application, sign up at <https://appdna.ai> (self-serve) or contact <sales@appdna.ai> (enterprise).

**Existing customers**: your Terms of Service or Statement of Work governs your use of the SDK.

**Versions before v1.0.2** were distributed under the MIT License — see [DEPRECATION_NOTICE.md](./DEPRECATION_NOTICE.md) for the migration timeline (deadline: **15 May 2026**).

---

© 2026 AppDNA AI, Inc. All rights reserved. "AppDNA" and the AppDNA logo are trademarks of AppDNA AI, Inc.
