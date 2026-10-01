# AppDNA React Native SDK — agent brief

A **thin wrapper** (ADR-001) around the native iOS and Android SDKs. It contains API facades, DTOs
and bridge calls — and no rendering, networking, storage, or business logic. Everything you see on
screen is drawn by native.

> **This file deliberately does NOT restate the API.** The previous version did, and it rotted: it
> self-labelled `v0.3.0` long after 1.0.x shipped, and it told you the SDK delivers events through
> `NativeEventEmitter` — which, under the New Architecture, is a channel **nothing writes to** (see
> #1 below). A hand-copied surface is a second source of truth, and the second one is always the one
> that lies. Read the real ones instead:

| What | Where | Kept honest by |
| --- | --- | --- |
| The method + event surface (source of truth) | `src/lib/sdk-delegates/sdk-methods.ts` (the IR) | — |
| The public facade a host calls | `src/index.ts` | `pnpm check:rn-facade-parity` |
| TurboModule spec (codegen'd — do not hand-edit) | `src/specs/NativeAppdnaModule.ts` | `pnpm sdk-codegen --check` |
| The 9 delegate interfaces (codegen'd) | `src/generated/delegates.ts` | `pnpm sdk-codegen --check` |
| Public docs | `docs/sdks/react-native/*.mdx` | `pnpm check:rn-docs-api` |
| Current version | `package.json` | `pnpm check:publishable-version` |

`check:rn-facade-parity` asserts the IR, the Kotlin module, the Swift impl, the ObjC++ adapter, the
TS spec and the facade all agree **in both directions**. So "it is in the facade" transitively means
"it is implemented everywhere". That is the only claim about the API worth trusting.

---

## The six things that will bite you

**1. The New Architecture is required, not preferred.**
The module resolves via `TurboModuleRegistry.get`, and native emits through the codegen'd
`EventEmitter<T>` **spec properties**. On the legacy bridge the module still *resolves* — so every
method appears to work — but no emitter property exists, so **every listener silently never fires**.
`src/nativeModule.ts` therefore refuses the legacy bridge *by name* rather than letting it look
healthy. `new NativeEventEmitter(module).addListener(...)` subscribes to nothing. Do not bring it
back.

**2. Vetoes are not events.**
Eight hooks are vetoes: native *awaits your answer* before acting. A listener's return value is
discarded, so a veto cannot ride the event channel — they go through `hostCallbacks.ts` →
`respondToHostCallback`. Timeout defaults are **per-hook**, and one is not like the others:
`onPromoCodeSubmit` defaults to **reject**, the other seven to allow. A uniform default is how a
paywall starts accepting unvalidated promo codes.

**3. `framework` is injected by native, never by the host.**
The bridge stamps `react_native` (underscore) inside `parseOptions`. It is not on the public
`AppDNAOptions` and must not be — a host must not be able to set, spoof, or omit its own
attribution. The envelope schema is `.catch('native')`, so a wrong tag does not error, is not
logged, and is not metered. It just quietly lies in BigQuery.

**4. Threading (E10).**
TurboModule method bodies run on the **JS thread** on Android, and `getMethodQueue()` is ignored.
Present-style calls dispatch to the **UI thread** (native's own main-looper check then takes a
latch-free path). `configure()` dispatches **off** the JS thread because it opens SQLite. But
`parseOptions` stays **on** it — a `ReadableMap` is only valid on the thread the bridge delivered it
on.

**5. Values of unknown shape cross as JSON strings (E2).**
There is no codegen type for "any JSON value", so `getRemoteConfig`, `getFeatureVariant`,
`getSessionData` and friends cross as a JSON **string**, parsed in the facade. Also:
`Record<string, unknown>` is codegen-**illegal** (`UnsupportedGenericParserError`) — general TS
unions are fine.

**6. Under dynamic frameworks, the Fabric component does not register itself.**
Codegen's `RCTThirdPartyFabricComponentsProvider` wraps its component map in
`#ifndef RCT_DYNAMIC_FRAMEWORKS`. The core SDK needs `use_frameworks!` (its Firebase graph does not
build from static libraries); with dynamic frameworks — the example's and the Expo plugin's default;
static frameworks need nothing — the registration is compiled out and `<AppDNAScreenSlot>` renders React's
`Unimplemented component` placeholder. No throw, no log. The pod exports `AppdnaFabricComponents()`
and the host merges it into `RCTAppDelegate`'s `thirdPartyFabricComponents`. The example does this;
so must the docs' install steps.

---

## Screens are two different things

- **`<AppDNAScreenSlot name="…" />`** embeds a server-driven screen **inline** in your React layout
  (a Fabric component). It raises no delegate events.
- **`AppDNA.screens.show(id)` / `.showFlow(id)`** **present** a screen over the app. These are what
  fire `onScreenPresented` / `onScreenDismissed` / `onFlowCompleted` on the 9th delegate.

A `custom_view` block is registered from **native** code — `AppDNA.registerCustomView` takes an
`AnyView` / `@Composable`. You cannot pass a React component: JavaScript has no native view to hand
it.

---

## Before you change anything

1. Edit the **IR** (`src/lib/sdk-delegates/sdk-methods.ts`), then run `pnpm sdk-codegen`. Generated
   files carry a do-not-edit banner and CI fails on hand-edits.
2. Implement it in **Kotlin, Swift, and the facade**. `check:rn-facade-parity` names exactly which of
   the six sides you missed.
3. New behaviour needs a **shared fixture** (`packages/sdk-shared-fixtures/`) so all four SDKs assert
   identical output.
4. **Compile both natives.** A typecheck and a jest run compile *neither* — that is how
   `Environment.staging` (a case the enum has never had) survived in shipped source.

© 2026 AppDNA AI, Inc.
