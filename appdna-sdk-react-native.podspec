require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name             = "appdna-sdk-react-native"
  s.version          = package["version"]
  s.summary          = package["description"]
  s.homepage         = "https://appdna.ai"
  s.license          = { :type => "SEE LICENSE IN LICENSE", :file => "LICENSE" }
  s.authors          = { "AppDNA AI" => "support@appdna.ai" }
  s.source           = { :git => "https://github.com/appdna-ai-inc/appdna-sdk-react-native.git", :tag => "v#{s.version}" }

  # The iOS core SDK targets 16.0 (AppDNASDK.podspec:15); a wrapper cannot ask for
  # less than the thing it wraps.
  s.platform         = :ios, "16.0"
  s.swift_version    = "5.9"

  # `ios/Tests/**` is EXCLUDED from the shipped sources — it belongs to the test_spec below. Without
  # the exclusion the glob swallows the XCTest files into the library itself, and every consumer app
  # links XCTest into its release binary (App Store rejection, ITMS-90338).
  s.source_files     = "ios/**/*.{h,m,mm,swift}"
  s.exclude_files    = "ios/Tests/**/*"

  # The iOS half had ZERO unit tests.
  #
  # `parseOptions` (the `framework` tag, the `configTTL` default, `billingProvider`) was proven on
  # Android only, and "Android is right" is not evidence about Swift: the two are separate
  # hand-written functions and drifting apart is the entire bug class. E7's `?? 300` — the wrapper
  # literal that sat 12× below native's configTTL — lived in the SWIFT file. The `onPromoCodeSubmit`
  # default (which must REJECT: a code no host validated is not a valid code) was likewise asserted
  # by nothing on this platform.
  #
  # It is a `test_spec` and NOT a target in the example .xcodeproj because CocoaPods will not wire
  # `ENABLE_TESTABILITY` for an ad-hoc target there, and RN's static linkage makes `@testable import`
  # of a pod module fragile-to-infeasible. CocoaPods wires all of that for a test_spec, and
  # `pod lib lint` runs test_specs by default (`--skip-tests` opts out).
  #
  # ⚠️ A test_spec LAUNCHES A SIMULATOR → macOS runner only. It also inherits P0's
  # `--include-podspecs` requirement whenever the AppDNASDK version it pins is not yet on trunk.
  s.test_spec 'Tests' do |test_spec|
    test_spec.source_files = "ios/Tests/**/*.{swift}"
    # `requires_app_host` because the tests exercise MainActor delivery and a DispatchQueue.main
    # timeout — both need a real run loop, which an app host provides and a bare logic bundle does not.
    test_spec.requires_app_host = true
    # Fallback: the app-hosted StoreKit tests (`AppdnaStoreKitHostedTests`) load this
    # StoreKit configuration into an `SKTestSession` — the same five test products as the core SDK's own
    # test target — and need the StoreKit framework plus the principal class below.
    test_spec.resources = ['ios/Tests/AppDNATestProducts.storekit']
    test_spec.frameworks = 'StoreKit'
    # The principal class installs the notification proxy explicitly before any test
    # configures the SDK (install is once per process).
    test_spec.info_plist = { 'NSPrincipalClass' => 'AppdnaTestObservation' }
  end

  # Apple merges the app's, the pod's, and every dependency's privacy
  # manifest. The wrapper collects nothing of its own — it declares only the API-usage reasons its
  # own code triggers. The collected-data types are declared by the core pod's manifest, which is
  # where the collection actually happens.
  s.resource_bundles = { "AppdnaSdkReactNative" => ["ios/PrivacyInfo.xcprivacy"] }

  # The wrapper always pins the freshest native. `~>` admits a newer PATCH with no
  # source edit, so `check:version-lockstep` asserts this line matches the shipped iOS
  # version rather than trusting that it does.
  #
  # How the iOS SDK reaches the app. CocoaPods Trunk accepts no new versions after 2026-12-02, so a
  # published package cannot depend on the AppDNASDK pod for a new version. The publish copies the iOS
  # SDK (its podspec and the sources it lists) into `ios-core/` of the npm package, and this pod compiles
  # them itself. The SDK's own dependencies (Firebase, Google Maps, Lottie, Rive, KeychainAccess) are read
  # from that podspec, so they cannot drift, and are older versions that stay on Trunk. The app adds nothing.
  #
  # The SDK resolves its classes by Objective-C name and its resources by bundle name ("AppDNASDK"), so
  # compiling it inside this module changes nothing it looks up. The wrapper's Swift imports `AppDNASDK`
  # only when it is a separate module, `#if !APPDNA_CORE_BUNDLED`.
  #
  # The separate pod is still used when `ios-core/` is absent (a checkout of this repo) or when
  # APPDNA_IOS_SDK_PATH names a local SDK (this repo's example app and CI): then the Podfile names it.
  # Not Swift Package Manager: with React Native's `spm_dependency` the binary Firestore target never
  # reaches the app's link (`_OBJC_CLASS_$_FIRFirestore` undefined, RN 0.76.9, 2026-11-03).
  appdna_core_podspec = File.join(__dir__, "ios-core", "AppDNASDK.podspec")
  appdna_core_bundled = File.exist?(appdna_core_podspec) && ENV["APPDNA_IOS_SDK_PATH"].to_s.empty?
  appdna_core_xcconfig = {}
  if appdna_core_bundled
    core = Pod::Specification.from_file(appdna_core_podspec)
    core_ios = core.consumer(:ios)
    s.source_files = ["ios/**/*.{h,m,mm,swift}"] + core_ios.source_files.map { |g| "ios-core/#{g}" }
    core_ios.resource_bundles.each do |name, files|
      s.resource_bundles = s.attributes_hash["resource_bundles"].merge(name => Array(files).map { |f| "ios-core/#{f}" })
    end
    core.all_dependencies.each { |d| s.dependency d.name, *d.requirement.as_list }
    s.frameworks = core_ios.frameworks
    appdna_core_xcconfig = { "SWIFT_ACTIVE_COMPILATION_CONDITIONS" => "$(inherited) APPDNA_CORE_BUNDLED" }
  else
    s.dependency "AppDNASDK", "~> 1.0.91"
  end

  # 🔴 STATIC, because this pod links the static AppDNASDK and inherits its map symbols.
  #
  # AppDNASDK is `static_framework` (GoogleMaps ships as a static xcframework). A DYNAMIC framework
  # that links a static library must resolve that library's external symbols at its own link step,
  # so this wrapper failed exactly where the SDK used to:
  #
  #     Undefined symbols for architecture arm64:
  #       "_OBJC_CLASS_$_GMSMapView", referenced from:
  #            in AppDNASDK[arm64](MapInteractive.o)
  #     (in target 'appdna-sdk-react-native' from project 'Pods')
  #
  # Declaring this pod static too removes that link step: the objects flow into the app, which links
  # GoogleMaps alongside them. Every pod in the chain from the map code to the app has to be static
  # or link GoogleMaps itself; static is the one that does not multiply.
  s.static_framework = true

  # A pure-Swift TurboModule is impossible: codegen emits a C++ `NativeAppdnaModuleSpecJSI` plus an
  # ObjC @protocol, and registration returns a std::shared_ptr over headers Swift cannot import. The
  # ObjC++ adapter (P2) imports the generated Swift header, which requires DEFINES_MODULE.
  s.pod_target_xcconfig = {
    "DEFINES_MODULE" => "YES",
  }.merge(appdna_core_xcconfig)

  # install_modules_dependencies wires the New-Architecture flags, the codegen'd spec pod,
  # React-Core, ReactCommon and folly. It is defined by react_native_pods.rb, which only a host
  # app's Podfile loads — so a standalone `pod ipc spec` (P0's exit gate) does not have it. Guard,
  # or the podspec cannot be validated outside an app.
  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency "React-Core"
  end
end
