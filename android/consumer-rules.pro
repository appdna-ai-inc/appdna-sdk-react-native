# AppDNA React Native wrapper — consumer ProGuard / R8 rules, shipped to the host app through
# `consumerProguardFiles`. The core SDK (`ai.appdna:sdk-android`) ships its own rules for its DTO packages.
#
# The bridge reports a failure's type to JS (`type` on `onInitDegraded` and `getLastInitError`). The SDK's init
# errors map to explicit strings (`initErrorTypeName`); any other SDK exception falls back to its class name, so keep
# the names of the SDK's exception types — a minified build then reports the same strings as a debug build.
-keepnames class ai.appdna.sdk.** extends java.lang.Throwable
