package com.appdna.rn

import com.facebook.react.bridge.ReadableMap

/**
 * SPEC-497 §9.2 "Nested values" — a JS push payload → the SDK's `Map<String, String>` for
 * `AppDNA.push.isAppDNAMessage` / `handleMessageData` / `handleTapData`.
 *
 * - a scalar crosses as its string. JS numbers arrive as `Double` on Android, so an INTEGRAL double is
 *   written without `.0` (`5.0` → `"5"`, R82) — what the host wrote in JS, and what Flutter produces
 *   for the same payload;
 * - a nested object or array crosses as JSON text — never `toString()`, which yields `{type=deep_link,
 *   …}` that the SDK's `PushPayloadParser` cannot read;
 * - a `null` value is dropped (the SDK map has no null).
 *
 * Marshalling only: which message is AppDNA's, and what it does, is decided in the native SDK.
 */
internal object AppdnaPushData {

    fun toStringMap(map: ReadableMap?): Map<String, String> = fromValues(AppdnaBridge.toValueMap(map))

    /** The same conversion over already-decoded values — the unit-testable half. */
    fun fromValues(values: Map<String, Any?>?): Map<String, String> {
        if (values == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            val s = stringify(value) ?: continue
            out[key] = s
        }
        return out
    }

    private fun stringify(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Double -> integral(value)?.toString() ?: value.toString()
        is Float -> integral(value.toDouble())?.toString() ?: value.toString()
        is Map<*, *>, is List<*> -> AppdnaBridge.toJson(value)
        else -> value.toString()
    }

    private fun integral(d: Double): Long? =
        if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else null
}
