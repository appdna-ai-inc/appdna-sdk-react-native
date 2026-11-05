# Changelog

All notable changes to the AppDNA React Native SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

Feature parity: iOS 1.0.86.

### Fixed

- iOS: **unstyled headings and text inside a dark row or section background are drawn light**, as on Android.
- iOS: **an `animated_loading` block that sets `variant`** draws that variant, as on Android.
- **Text inside a zoned section background is readable.** Unstyled text in a `section_background` takes its ink
  from the zones behind it (one zone: its colour; several: any dark zone means light text). Before, it took the
  step's background, so it could be dark on a dark zone.
- iOS: **the rating block stores its answer** at its `field_id`, as on Android.
- iOS: **Multi-buttons no longer draws a button your data hid**, matching Android.
- **Onboarding image alt text is set in the Console**, in the image block's new "Alt text" field, and read
  out as the image's accessibility label. A blank value is now announced as "Image", as an unset one is
  (it was announced as nothing).
- **Paywall images use their Alt Text as the accessibility label.** The Console's "Alt Text" on an image
  section (`alt`) is now read out by VoiceOver and TalkBack; before, it was saved and ignored.
- **In-app messages honour "Allow Dismiss" and "Dismiss Delay".** With dismissal off, a message draws no
  close button or dismiss text and ignores a tap on its backdrop (and the Android **Back** button); with a delay of N seconds, the close
  button appears after N seconds and the message can't be dismissed before then. The CTA and the secondary
  button keep working, and a message with neither stays dismissable, so the user always has a way out.
  Applies to banner, modal, fullscreen and tooltip messages. Before, the SDK always showed the close button.

## [1.0.22] — unreleased

Wraps iOS 1.0.85 / Android 1.0.57.

### Security

- iOS: the package requires Cloud Firestore 12.19.3 or later. Firebase reported that Cloud Firestore's Apple SDK compiles in TLS root certificates that trusted intermediate certificate authorities whose private keys were compromised (eMudhra, revoked on 17 October 2026). A network attacker holding such a certificate could impersonate the Firestore servers to an app built with an older Firestore. Firestore 12.19.3 removes them; no 11.x release has the fix. **Rebuild and republish your app** after
  updating. Android is not affected.

### Fixed

- In-app message buttons open their link on both platforms, including a CTA saved as `{type, value}`
  (the shape the console writes), which before only dismissed the message.

## [1.0.21] — unreleased

Wraps iOS 1.0.84 / Android 1.0.57. Rendering, billing and networking live in the native SDKs, so
every native change below reaches a React Native app with no JavaScript change.

### Installation

- **iOS: the package carries the AppDNA iOS SDK.** CocoaPods Trunk stops accepting new pods on
  2 December 2026, so the iOS SDK is no longer a separate `AppDNASDK` pod: this package compiles it in
  its own pod. Your `Podfile` names nothing for AppDNA; remove a `pod 'AppDNASDK'` line if you added one.
  The SDK's dependencies (Firebase, Google Maps, Lottie, Rive) still install from CocoaPods.

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

- **Android: push token on first launch** (Android 1.0.57). The token was requested before the SDK's
  own Firebase app existed, so the first launch registered none (or, in an app with its own Firebase, one
  AppDNA cannot send to).

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

- **A dropdown-style Select can be multi-select**: with `multi_select` the default dropdown is a
  multi-pick menu that stores a list, records each pick for `{{selected.<field_id>.<n>.…}}`, honours
  `max_selections` and closes after each pick. A multi-select field's stored value now draws and records
  the same way on iOS and Android, also when a host writes it mid-step.
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
