# React Native SDK — release runbook

## AC-32 ruling: deprecate every published version below 1.0.7

**Ruling: YES. `npm deprecate '@appdna-ai/react-native-sdk@<1.0.7'` must run as part of this
release.** This is not housekeeping — it is a correctness fix.

### Why

npm currently carries **1.0.1, 1.0.2, 1.0.3, 1.0.4, 1.0.6**, none deprecated, with `latest = 1.0.6`.
So `npm install @appdna-ai/react-native-sdk` today resolves to 1.0.6 — and **none of those versions
work**:

- **No podspec.** `pod install` cannot resolve the pod, so an iOS build fails outright.
- **No `android/build.gradle`.** Nothing to link on Android.
- **`Environment.staging`** was referenced in shipped source; that case has never existed on either
  native enum.
- **Zero of the eight veto hooks were routed**, and events were subscribed through
  `NativeEventEmitter` — a channel nothing writes to under the New Architecture. Listeners did not
  throw. They simply never fired.
- **No `framework` tag**, so every event those versions did manage to send landed in BigQuery
  attributed as `native`.

A user who follows an old blog post and installs the default version gets a package that cannot
build, and if they work around that, an SDK whose callbacks are silently dead. Leaving those
versions undeprecated means npm keeps recommending them by default until 1.0.7 becomes `latest` —
and keeps serving them forever to anyone who pins.

`npm deprecate` does **not** unpublish: the tarballs stay installable (so no lockfile breaks), the
user just gets a loud warning telling them what to do. That is exactly the right instrument here.

### The commands

Run **after** 1.0.7 has published successfully and is `latest` — deprecating before the replacement
exists would point people at a version they cannot install.

```bash
# 1. Verify 1.0.7 is up and is `latest`.
npm view @appdna-ai/react-native-sdk version        # -> 1.0.7

# 2. Deprecate everything below it. `<1.0.7` is a semver range; npm applies it to each match.
npm deprecate '@appdna-ai/react-native-sdk@<1.0.7' \
  'Non-functional: these versions shipped without a podspec or android/build.gradle (the native module never linked), and their event listeners never fired. Upgrade to >=1.0.7, which requires the React Native New Architecture.'

# 3. Confirm.
npm view @appdna-ai/react-native-sdk@1.0.6 deprecated
```

### What is deliberately NOT done

- **No `npm unpublish`.** Unpublishing breaks every existing lockfile that references those
  versions, including CI for anyone who pinned one. A loud deprecation warning achieves the goal
  without breaking builds that currently succeed.
- **The un-namespaced `@appdna/` scope (no `-ai`) is not touched.** The RN package under that scope
  has never existed — npm 404s it. It appeared only in docs and two console wizards, both fixed.
  There is nothing on the registry to deprecate, and `check:sdk-framework-registry` now fails the
  build if that dead coordinate reappears anywhere — including here, which is why this line no longer
  spells it out. (The gate has no suppression marker, by design: an escape hatch in a coordinate lint
  is how the dead coordinate gets back in.)

---

## 🔴 Publish gate — RN 0.77 / Kotlin 2.0 validation (impl-audit R5, F1)

The `peerDependencies` range is `react-native >=0.76.9` (no upper bound), so it advertises RN 0.77+
(Kotlin 2.0). That support is currently **compile-validated only, and only by a HYBRID check**
(`scripts/rn-k2-compile-check.sh` — the RN 0.76.9 example with Kotlin forced to 2.0.x). Before
publishing a version that keeps the un-capped range, one of these MUST be true:

1. **A real RN ≥ 0.77 app** (not the hybrid) builds the wrapper (`assembleDebug`) AND passes a device
   e2e (the `<AppDNAScreenSlot>` renders, onboarding/paywall present, events reach BQ), OR
2. the range is re-capped to `<0.77.0` and the RN-0.77 claim is pulled from the docs until (1) lands.

Do NOT ship the un-capped range on the strength of the hybrid check alone — it never touches a real
0.77 `react-android` / react-gradle-plugin / codegen, and a runtime Compose-ABI mismatch would surface
only on device. Also note: on **Expo SDK 53** (Swift AppDelegate) the config plugin warns+skips the
dynamic-frameworks ScreenSlot registration — a native Swift Fabric-registration path is still a
follow-up, so the RN-0.77 validation should cover an Expo-53 host too.

## Release order

The wrapper pins the natives, so the natives must exist on their registries first, or `pod install`
and Gradle will resolve nothing.

1. **iOS** `AppDNASDK` 1.0.71 → CocoaPods trunk.
2. **Android** `ai.appdna:sdk-android` 1.0.43 → Maven Central.
3. **Flutter** `appdna_sdk` → pub.dev.
4. **React Native** `@appdna-ai/react-native-sdk` 1.0.8 → npm.
5. **Then** the `npm deprecate` above.

`pnpm check:native-pins` asserts each wrapper pins the version actually being shipped, and
`pnpm check:publishable-version` asserts each publishable version is strictly ahead of what its
registry already has — so a re-publish that would silently no-op fails the build instead.

© 2026 AppDNA AI, Inc.
