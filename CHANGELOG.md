# Changelog

All notable changes to the AppDNA React Native SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.23] — unreleased

Feature parity: iOS 1.0.86.

### Added

- **A generated message's or paywall's animation and video play (native iOS 1.0.86 / Android).** The native
  renderers draw an in-app video hero in a modal on iOS too, play an autoplaying paywall video section with no controls,
  and treat a blank animation or video URL as no media. Nothing changes in this package's API.

- **A Share button action (native iOS 1.0.86 / Android 1.0.58).** Onboarding buttons, in-app message CTAs and
  paywall extra buttons can open the phone's share sheet with text and an optional link set in the console. The
  native SDK does it; no API change.
- **`AppDNA.push.setSdkDisplaysForwardedMessages(enabled)`** (Android; does nothing on iOS). An app that forwards
  every AppDNA message from its own Firebase messaging handler can let the native SDK display what it forwards;
  AppDNA then sends the device data-only messages, so deliveries are confirmed in the background too.
- **Push deliveries are confirmed by the device** (native SDKs: iOS 1.0.86, Android). On Android the native
  messaging service confirms AppDNA pushes in the background too (AppDNA sends it data-only messages); on iOS the
  AppDNA Notification Service Extension confirms a push that arrives while the app is closed, and, with an App Group
  named under `AppDNAAppGroup`, hands it to the app for `push_delivered`. Nothing changes in the wrapper's API.

- **A step that advances by itself can show a countdown** (native SDKs: iOS 1.0.86, Android 1.0.59). `auto_advance_indicator` on the step; drawn by the native step host. Nothing changes in the wrapper's API.
- **A button can depend on other elements on its screen** (native SDKs). `field_config.cta_dependencies` keeps a
  button inactive in the native renderer until the named conditions hold. Nothing changes in the wrapper's API.
- **A Select that advances on the pick can ask first** (native SDKs). `field_config.advance_confirm` opens a
  confirmation dialog in the native renderer before the step moves on. Nothing changes in the wrapper's API.
- **A Select can say whether the pick was right** (native SDKs). `field_config.correct_values` marks the correct
  option(s) and the native renderer shows a right or wrong message under the options once the user has answered.
  Nothing changes in the wrapper's API.
- **An "Add widget to home screen" button action** (native SDKs: iOS 1.0.86, Android 1.0.59). On Android the
  native SDK asks the launcher to pin the app's own home-screen widget; on iOS, where no app can add a widget, it
  shows the how-to sheet written in the console. The step then advances with `widget_status` (`added`,
  `not_added` or `unavailable`) in its answers. The widget itself lives in the native projects (a WidgetKit
  extension in `ios/`, an `AppWidgetProvider` in `android/`); nothing changes in the wrapper's API.
- **Consent signal (native iOS 1.0.86 / Android).** The native SDKs now send AppDNA one consent signal when the
  analytics decision is set or changes, when the user changes while declined, and at each launch while declined,
  so the server can honour the decision on messages it sends. No API change: `setConsent` works as before, and it
  can now be called before `configure`.

### Changed

- **Every date picker works in the app's time zone, a date-only one too (native iOS 1.0.86 / Android).** Its opening
  day, `today` / relative bounds and `allow_past` / `allow_future` follow the app's zone; a form step's time and
  date-and-time fields store the wall time in that zone with its offset. Nothing changes in this package's API.

- **An image with no description is decorative, and screen readers skip it.** An onboarding image block, a
  paywall image section or a screen image section whose Alt text is blank or unset is now hidden from VoiceOver and TalkBack
  (it was announced as "Image", or with no description); one with Alt text is read as that text, as an image. An
  in-app message's image and a survey question's image have no description field, so they are always skipped.

### Fixed

- **`survey_question_answered` is sent once per question, not once per keystroke (native iOS 1.0.86 / Android).** A
  typed answer is sent when the user moves on with Next or Submit. Nothing changes in this package's API.

- **A survey stays above the keyboard while an answer is typed (native Android).** The native renderer moves a
  bottom-sheet or modal survey up when the keyboard opens for a free-text question, so Submit stays tappable; it was
  covered, and a tap there typed a letter. Nothing changes in this package's API.

- **A queued event batch is deleted only when the server refuses its content** (native iOS 1.0.86 / Android). A 401,
  403, 404, or an error page from a tunnel or proxy in front of the API, keeps the events instead of deleting them; only
  a 400, 413 or 422 carrying the AppDNA API's JSON error body drops a batch, counted. The native SDK does it; no API
  change. The consent signal follows the same rule: a 401 / 403 / 404 or a
  proxy's page is retried instead of dropping the signal until the next launch.

- **An in-app message triggered while the app is in the background is shown when the user opens the app** (native SDKs). A message whose trigger event fires during a background launch (a silent push, a background task) was drawn into the background scene on iOS, counted shown while nobody could see it, and dropped on Android. Both now hold it and show it the next time the app comes to the foreground, if it is still published, inside its dates and allowed by its frequency. Nothing changes in the wrapper's API.
- **The Date Picker draws the style chosen in the console, and can pick a range of days** (native SDKs): `picker_variant` is honoured on Android, an inline Date field draws its variant, and `selection_mode: "range"` answers `start/end` with `_start`, `_end` and `_days`. Nothing changes in the wrapper's API.
- **A linear style for the measurement wheel picker** (native SDKs): `measurement_style: "linear"`, a vertical slider beside the value with one locked unit, sized by `linear_height`, `track_width` and `thumb_size`, thumb in `thumb_color`. Nothing changes in the wrapper's API.
- **Every colour of a measurement wheel picker can be set** (native SDKs). The `wheel` and `dial` styles take `band_color`, `band_text_color` and `row_text_color`, and every style takes `unit_color`, `label_color`, `toggle_active_text_color` and `toggle_text_color`. Nothing changes in the wrapper's API.
- **`app_open` is tracked on a cold launch too** (native SDKs: iOS 1.0.86, Android 1.0.59). It was tracked only
  when the app returned from the background; it is now tracked once each time the app comes to the foreground,
  the launch included. Nothing changes in the wrapper's API.
- **A plan's savings figure is computed from the prices drawn.** `{{savings_percent}}` in a plan's `badge` or `savings_text` is filled by the native SDKs from the prices the paywall shows (the store's once it answers), and the text is hidden when no figure can be worked out.
- **Onboarding flows and surveys can be asked for by name.** `AppDNA.onboarding.presentByPlacement(placement)` and `AppDNA.surveys.presentByPlacement(placement)` forward the name to the native SDK. `presentOnboarding()` with no id now presents the app's default flow.
- **A placement can be a name of your own.** `presentPaywallByPlacement` / `presentByPlacement` already take any string; a paywall's placement may now be any name set in the console. Ties between several live paywalls on one placement resolve the same way on every launch, in the native SDKs.
- **An archived, unpublished or deleted flow, paywall or survey stops showing.** After a config fetch, every
  onboarding flow, paywall and survey the server no longer lists is dropped from memory and from the cached
  config, so it does not come back after a restart. An empty list means none are live. A failed fetch
  (offline, an error) removes nothing. Before, a device kept serving every one it had ever fetched.
- Android: **An event upload is one request.** When a reused connection dropped after a batch was sent, OkHttp
  re-sent the batch on its own, outside the queue's retry and backoff, so a batch could reach the server more
  times than the retry policy allows. The upload (in the app and in the background worker) now makes one
  request per attempt and leaves the retry to the queue.
- **Option labels and summary headlines are readable on any step.** An unstyled option label in a Select
  (options without their own fill) and a `summary_screen` headline with no `text_color` take their ink from
  what is behind them; before, labels were dark on a dark step and the headline white on a light one.
- **Unstyled rich text and lists are readable on dark screens.** A `rich_text` or `list` block with no colour
  takes its ink from what is behind it, as text does; before, it was dark on a dark step or fill.
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

### Added

- **`animated_image`: one long image scrolling in a seamless loop** (native SDKs: iOS 1.0.86, Android 1.0.59). Drawn by the native renderers; nothing changes in the wrapper's API.
- **A block's background can reach past it on each side** (native SDKs: iOS 1.0.86, Android 1.0.59). `field_config.background_bleed_top` / `_bottom` / `_left` / `_right` take `"edge"` or a number of points; the native renderers draw it. Nothing changes in the wrapper's API.

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
