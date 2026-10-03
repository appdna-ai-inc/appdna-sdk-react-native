import Foundation

/**
 * Encode a value of unknown shape as JSON.
 *
 * `getRemoteConfig`, `getFeatureVariant` and `getExperimentConfig` can each return a bool, a number,
 * a string, an array or an object. There is no codegen type for "any JSON value", so the value
 * crosses as a JSON **string** and is parsed in the facade. This is also the only encoding whose
 * meaning is identical on both platforms — Android's mirror is `AppdnaBridge.toJson`.
 *
 * `JSONSerialization` refuses a bare scalar unless `.fragmentsAllowed` is set, which is exactly the
 * case that matters here: a boolean flag is the common one.
 */
enum AppdnaJSON {

    static func encode(_ value: Any?) -> String {
        guard let value, !(value is NSNull) else { return "null" }

        // `.fragmentsAllowed` is what lets `true`, `3`, and `"a"` encode at top level.
        if JSONSerialization.isValidJSONObject(value) || value is NSNumber || value is String {
            if let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
               let json = String(data: data, encoding: .utf8) {
                return json
            }
        }

        // A type we cannot represent is a mapper bug. Encoding its `description` would be a lie that
        // typechecks — refuse instead, and make the facade's parse fail loudly.
        Log.warning("AppDNA: cannot JSON-encode a value of type \(type(of: value)); returning null")
        return "null"
    }

    /**
     * The inverse (P8, for `setSessionData`). Android's mirror is `AppdnaBridge.fromJson`.
     *
     * Returns nil for a JSON `null`, for empty input, and for anything unparseable — all three mean
     * "no value" to every caller, and collapsing them here keeps the callers from each inventing a
     * different answer. `.fragmentsAllowed` again, because a bare `true` or `3` is the common case.
     */
    static func decode(_ json: String) -> Any? {
        guard !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        guard let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              !(value is NSNull) else { return nil }
        return value
    }
}

/// The core SDK's logger is internal to `AppDNASDK`; this wrapper has no logger of its own and must
/// not invent one. `NSLog` keeps the single diagnostic above visible without adding a subsystem.
private enum Log {
    static func warning(_ message: String) { NSLog("%@", message) }
}
