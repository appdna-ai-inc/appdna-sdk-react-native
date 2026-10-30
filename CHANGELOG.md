# Changelog

All notable changes to the AppDNA React Native SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.21] — unreleased

Wraps iOS 1.0.84 / Android 1.0.56. Rendering, billing and networking live in the native SDKs, so
every native change below reaches a React Native app with no JavaScript change.

### Added

- **`AppDNA.paywall.skipNextAutoDismissOnRestore(true)`** (#970). A successful restore that finds
  products dismisses the paywall; calling this before the user taps Restore keeps it up for that
  presentation, so you can confirm or upsell on it. iOS, Android and Flutter already had it.
- **`AppDNA.isReady()`** (#855). Resolves whether `configure()` has finished. A `present…` call made
  before then now logs that the SDK is not ready yet instead of "Onboarding flow not found".
- **`onConfigurationIssue(surface, id, detail)`** on the lifecycle delegate (#935): part of a
  published config could not be decoded and was dropped while the rest still renders; `detail`
  carries the field's path and the reason. The SDK also tracks `sdk_decode_dropped`.
- **`onEventTracked(name, properties)`** on the lifecycle delegate (#1047): called for every event
  the SDK tracks, right after it is queued, for forwarding AppDNA analytics into your own pipeline.
- **`getLocationData` includes `mapbox_id` and `place_id`** (#1147), set on an answer resolved from a
  tapped Mapbox or Google suggestion.

### Changed

- **Android: Play Billing Library 8** (#1112). Google Play refuses an app update built on Play
  Billing Library 7, which every app using 1.0.20 or earlier got from the native Android SDK. Android
  1.0.56 builds on Billing 8.0.0, so an app on this version can publish to Play again.

### Fixed

- **Android: a trait holding `null` is kept** (#1208). `identify` dropped any trait that was or held
  a `null`, including the object `getLocationData` resolves (it holds `null` for each unset key), so
  passing a location answer as a trait sent nothing.
- **Android: a flow the OS ends in the background closes at once** (#1170), with a warning, instead
  of after a 15-second wait, and `onboarding_flow_dismissed` is sent. (It is not resumed, because
  `configure` runs from JavaScript after Android restores the screen.)

### Native SDK changes: paywalls

- **Prices, billing periods and free trials come from the App Store and Google Play** (#923, #933,
  #989), with the authored values as the fallback; `use_store_price: false` keeps the authored price.
  A free trial is shown only when the user is eligible for it.
- **Fixed (Android): a subscription purchase starts the free trial the paywall shows** (#1113).
  Without an `offerToken` the native SDK bought the base plan, so Play charged the full price from day
  one while the paywall said "1 week free trial".
- **A failed purchase leaves the paywall usable on both platforms** (#1105, #1107, #1111): a bottom
  banner (Android drew a full-screen card over the purchase button), whose retry runs the purchase
  again; on iOS a tap on the purchase button while the banner is up now buys.
- **A purchase button on a multi-plan paywall names the plan it will buy** (#1100, #1104), unless
  `cta.name_plan` is `false`; `cta.plan_format` rewords it.
- **New authoring reaches devices**: sections pinned with `zone: "bottom"` (#884, #903),
  `element_style` on every element (#905, #910, #913), `tab_groups` (#960), countdown variants and
  styling (#980, #979), `sort_order` (#1056, #1097), a plan's `period` (#913), social-proof caption
  and author (#954).
- **`onPaywallAction` reports `cta_tapped`** (never sent on iOS before), **`plan_changed`,
  `link_tapped`, `custom` and `feature_selected`** (#1074, #1076).
- **The particle effect fires on its authored trigger**, and after a successful purchase rather than
  on the tap (#997, #1103).
- **A paywall's unauthored colours follow the page, not the phone** (#891, #909, #938, #946, #947):
  light paywalls no longer draw blank sections on Android, and iOS no longer changes with the phone's
  appearance.
- **Fixed (Android): an entitlement change is reported on a renewal**, a grace period or a
  billing-retry recovery, not only when the product changes (#1061).

### Native SDK changes: onboarding

- **New step behaviour**: `auto_advance_seconds`, `wait_for_host` and `tap_anywhere_advances` (#921,
  #1014), `skip_to_step` (#1056), `dismiss_button_style` (#1004), `request_store_review` and the
  `rate_app` button action (#896), and the flow's Celebration Effect (#1026).
- **Lottie and Rive render on iOS** (#982), instead of a placeholder box. The iOS SDK now depends on
  `lottie-ios` 4.6+ and `RiveRuntime` 6.x. Rive state-machine inputs apply on both platforms (#1017,
  #1024).
- **Sign in with Google draws Google's official mark** (#1003, #1030); info banners gain an icon,
  colour and link options (#1005); form fields honour their authored style (#985, #1052, #1053),
  `min_length` and `validate_format` (#984); `bubble_scroll` category rows and same-step visibility
  conditions (#1013); a media gallery title (#1021, #1060).
- **Unstyled text and controls follow the step's background** (#1194, #1196, #1198): light on a dark,
  gradient or image step, unchanged on a light one.
- **Location fields resolve a tapped suggestion** and support Google as a provider, with Google's
  attribution drawn for you (#1147, #1173). Location settings now reach the Location content block on
  both platforms (#963, #964).
- **Fixed: a button with an unrecognised action keeps the step's answers** (#1091); a question step
  the user comes back to shows their choice (#1188); a dropdown select opens its option sheet
  (#1172); `{{onboarding.<step>.<field>.city}}` resolves (#1158); a circular countdown's ring moves
  (#1066).
- **Fixed (Android):** a block-based step no longer draws a Skip over a bottom-zone Continue (#1200);
  typed text and an interactive chat step survive a rotation (#1189, #1193); a nested answer reads
  back the same after a relaunch (#1150); a map pans with a drag and no longer stalls the app when
  its step opens (#951, #952); an empty `layout.content_blocks` falls back to the step's blocks
  (#772); the location field counts characters as a user sees them (#1209) and its Search key closes
  the keyboard (#1195).
- **Fixed (Android): rich text draws as on iOS**: CommonMark emphasis, strikethrough, code spans and
  escapes (#1183, #1186), HTML entities (`&amp;` as `&`) (#1221), and a link to an international host
  opens the same domain as on iOS (#1185).

### Native SDK changes: surveys, push, targeting and events

- **Store reviews are never pre-screened by a survey's answers** (#897); `review_prompt_accepted` /
  `review_prompt_declined` are no longer sent.
- **Fixed: emoji answers are scored against the question's own scale** (#1011, #1037); the feedback
  form appears after a negative answer (#898, #901) and records how it ended (#920, #1010); likert
  questions render on iOS (#998); `neq` / `in` / `not_in` trigger conditions work and an unknown
  `frequency` means `once` (#1064).
- **iOS reports `push_dismissed`** for every AppDNA push (#968, #987).
- **Targeting**: a trait counts as present only when non-null (#1212); an operator the SDK does not
  implement matches nobody instead of everyone, and `not_exists` works (#1213).
- **Fixed: a null trait is kept** and `{{user.x | fallback}}` takes the fallback for it (#1208,
  #1211); a deferred deep link survives a launch with no network (#1063); one wrongly-typed field no
  longer drops a whole iOS flow, paywall or survey (#919); compressed responses are read on Android
  (#857).
- **`store_listing_opened` carries `store`** on both platforms (#974).
