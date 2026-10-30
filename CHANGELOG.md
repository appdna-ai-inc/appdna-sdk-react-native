# Changelog

All notable changes to the AppDNA React Native SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.21] — unreleased

Wraps iOS 1.0.84 / Android 1.0.56. Rendering, billing and networking live in the native SDKs, so
every native change below reaches a React Native app with no JavaScript change.

### Added

- **`AppDNA.paywall.skipNextAutoDismissOnRestore(true)`**. A successful restore that finds
  products dismisses the paywall; calling this before the user taps Restore keeps it up for that
  presentation, so you can confirm or upsell on it. iOS, Android and Flutter already had it.
- **`AppDNA.isReady()`**. Resolves whether `configure()` has finished. A `present…` call made
  before then now logs that the SDK is not ready yet instead of "Onboarding flow not found".
- **`onConfigurationIssue(surface, id, detail)`** on the lifecycle delegate: part of a
  published config could not be decoded and was dropped while the rest still renders; `detail`
  carries the field's path and the reason. The SDK also tracks `sdk_decode_dropped`.
- **`onEventTracked(name, properties)`** on the lifecycle delegate: called for every event
  the SDK tracks, right after it is queued, for forwarding AppDNA analytics into your own pipeline.
- **`getLocationData` includes `mapbox_id` and `place_id`**, set on an answer resolved from a
  tapped Mapbox or Google suggestion.

### Changed

- **Android: Play Billing Library 8**. Google Play refuses an app update built on Play
  Billing Library 7, which every app using 1.0.20 or earlier got from the native Android SDK. Android
  1.0.56 builds on Billing 8.0.0, so an app on this version can publish to Play again.

### Fixed

- **Android: a trait holding `null` is kept**. `identify` dropped any trait that was or held
  a `null`, including the object `getLocationData` resolves (it holds `null` for each unset key), so
  passing a location answer as a trait sent nothing.
- **Android: a flow the OS ends in the background closes at once**, with a warning, instead
  of after a 15-second wait, and `onboarding_flow_dismissed` is sent. (It is not resumed, because
  `configure` runs from JavaScript after Android restores the screen.)

### Native SDK changes: paywalls

- **Prices, billing periods and free trials come from the App Store and Google Play**, with the authored values as the fallback; `use_store_price: false` keeps the authored price.
  A free trial is shown only when the user is eligible for it.
- **Fixed (Android): a subscription purchase starts the free trial the paywall shows**.
  Without an `offerToken` the native SDK bought the base plan, so Play charged the full price from day
  one while the paywall said "1 week free trial".
- **A failed purchase leaves the paywall usable on both platforms**: a bottom
  banner (Android drew a full-screen card over the purchase button), whose retry runs the purchase
  again; on iOS a tap on the purchase button while the banner is up now buys.
- **A purchase button on a multi-plan paywall names the plan it will buy**, unless
  `cta.name_plan` is `false`; `cta.plan_format` rewords it.
- **New authoring reaches devices**: sections pinned with `zone: "bottom"`,
  `element_style` on every element, `tab_groups`, countdown variants and
  styling, `sort_order`, a plan's `period`, social-proof caption
  and author.
- **`onPaywallAction` reports `cta_tapped`** (never sent on iOS before), **`plan_changed`,
  `link_tapped`, `custom` and `feature_selected`**.
- **The particle effect fires on its authored trigger**, and after a successful purchase rather than
  on the tap.
- **A paywall's unauthored colours follow the page, not the phone**:
  light paywalls no longer draw blank sections on Android, and iOS no longer changes with the phone's
  appearance.
- **Fixed (Android): an entitlement change is reported on a renewal**, a grace period or a
  billing-retry recovery, not only when the product changes.

### Native SDK changes: onboarding

- **New step behaviour**: `auto_advance_seconds`, `wait_for_host` and `tap_anywhere_advances`, `skip_to_step`, `dismiss_button_style`, `request_store_review` and the
  `rate_app` button action, and the flow's Celebration Effect.
- **Lottie and Rive render on iOS**, instead of a placeholder box. The iOS SDK now depends on
  `lottie-ios` 4.6+ and `RiveRuntime` 6.x. Rive state-machine inputs apply on both platforms.
- **Sign in with Google draws Google's official mark**; info banners gain an icon,
  colour and link options; form fields honour their authored style,
  `min_length` and `validate_format`; `bubble_scroll` category rows and same-step visibility
  conditions; a media gallery title.
- **Unstyled text and controls follow the step's background**: light on a dark,
  gradient or image step, unchanged on a light one.
- **Location fields resolve a tapped suggestion** and support Google as a provider, with Google's
  attribution drawn for you. Location settings now reach the Location content block on
  both platforms.
- **Fixed: a button with an unrecognised action keeps the step's answers**; a question step
  the user comes back to shows their choice; a dropdown select opens its option sheet;
 `{{onboarding.<step>.<field>.city}}` resolves; a circular countdown's ring moves.
- **Fixed (Android):** a block-based step no longer draws a Skip over a bottom-zone Continue;
  typed text and an interactive chat step survive a rotation; a nested answer reads
  back the same after a relaunch; a map pans with a drag and no longer stalls the app when
  its step opens; an empty `layout.content_blocks` falls back to the step's blocks;
 the location field counts characters as a user sees them and its Search key closes
  the keyboard.
- **Fixed (Android): rich text draws as on iOS**: CommonMark emphasis, strikethrough, code spans and
  escapes, HTML entities (`&amp;` as `&`), and a link to an international host
  opens the same domain as on iOS.

### Native SDK changes: surveys, push, targeting and events

- **Store reviews are never pre-screened by a survey's answers**; `review_prompt_accepted` /
  `review_prompt_declined` are no longer sent.
- **Fixed: emoji answers are scored against the question's own scale**; the feedback
  form appears after a negative answer and records how it ended; likert
  questions render on iOS; `neq` / `in` / `not_in` trigger conditions work and an unknown
  `frequency` means `once`.
- **iOS reports `push_dismissed`** for every AppDNA push.
- **Targeting**: a trait counts as present only when non-null; an operator the SDK does not
  implement matches nobody instead of everyone, and `not_exists` works.
- **Fixed: a null trait is kept** and `{{user.x | fallback}}` takes the fallback for it; a deferred deep link survives a launch with no network; one wrongly-typed field no
  longer drops a whole iOS flow, paywall or survey; compressed responses are read on Android.
- **`store_listing_opened` carries `store`** on both platforms.
