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

  # SPEC-070-B P0. The iOS core SDK targets 16.0 (AppDNASDK.podspec:15); a wrapper cannot ask for
  # less than the thing it wraps.
  s.platform         = :ios, "16.0"
  s.swift_version    = "5.9"

  # `ios/Tests/**` is EXCLUDED from the shipped sources — it belongs to the test_spec below. Without
  # the exclusion the glob swallows the XCTest files into the library itself, and every consumer app
  # links XCTest into its release binary (App Store rejection, ITMS-90338).
  s.source_files     = "ios/**/*.{h,m,mm,swift}"
  s.exclude_files    = "ios/Tests/**/*"

  # SPEC-070-B AC-11 / AC-21 / AC-30b — the iOS half had ZERO unit tests.
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
  # `--include-podspecs` requirement while AppDNASDK 1.0.73 is unpublished.
  s.test_spec 'Tests' do |test_spec|
    test_spec.source_files = "ios/Tests/**/*.{swift}"
    # `requires_app_host` because the tests exercise MainActor delivery and a DispatchQueue.main
    # timeout — both need a real run loop, which an app host provides and a bare logic bundle does not.
    test_spec.requires_app_host = true
  end

  # SPEC-070-B P0 (AC-38 / W7): Apple merges the app's, the pod's, and every dependency's privacy
  # manifest. The wrapper collects nothing of its own — it declares only the API-usage reasons its
  # own code triggers. The collected-data types are declared by the core pod's manifest, which is
  # where the collection actually happens.
  s.resource_bundles = { "AppdnaSdkReactNative" => ["ios/PrivacyInfo.xcprivacy"] }

  # SPEC-070-B D-v: the wrapper always pins the freshest native. `~>` admits a newer PATCH with no
  # source edit, so `check:version-lockstep` (AC-34) asserts this line matches the shipped iOS
  # version rather than trusting that it does.
  s.dependency "AppDNASDK", "~> 1.0.79"

  # A pure-Swift TurboModule is impossible: codegen emits a C++ `NativeAppdnaModuleSpecJSI` plus an
  # ObjC @protocol, and registration returns a std::shared_ptr over headers Swift cannot import. The
  # ObjC++ adapter (P2) imports the generated Swift header, which requires DEFINES_MODULE.
  s.pod_target_xcconfig = {
    "DEFINES_MODULE" => "YES",
  }

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
