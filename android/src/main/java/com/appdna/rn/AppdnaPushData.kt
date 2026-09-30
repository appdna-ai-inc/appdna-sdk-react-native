package com.appdna.rn

import com.facebook.react.bridge.ReadableMap

/**
 * SPEC-497 §9.2 "Nested values" — a JS push payload → the SDK's `Map<String, String>` for
 * `AppDNA.push.isAppDNAMessage` / `handleMessageData` / `handleTapData`.
 *
 * - a scalar crosses as its string. JS numbers arrive as `Double` on Android, so a number is written in
 *   plain decimal without a trailing `.0` (`5.0` → `"5"`, R82) — what the host wrote in JS, and what
 *   Flutter produces for the same payload; NaN / ±Infinity are dropped;
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
        is Double -> plain(value)
        is Float -> plain(value.toDouble())
        is Map<*, *>, is List<*> -> AppdnaBridge.toJson(finiteOnly(value))
        else -> value.toString()
    }

    /**
     * A JS number as the host wrote it: `5.0` → `"5"`, `1e15` → `"1000000000000000"`, `0.0001` →
     * `"0.0001"` (never scientific notation), `-0.0` → `"0"`. NaN / ±Infinity have no JSON or decimal
     * form, so they are dropped (null), like a null value.
     */
    internal fun plain(d: Double): String? {
        if (!d.isFinite()) return null
        if (d == 0.0) return "0"
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
    }

    /** Nested values: non-finite numbers become null (org.json refuses them and would throw). */
    private fun finiteOnly(v: Any?): Any? = when (v) {
        is Double -> if (v.isFinite()) v else null
        is Float -> if (v.isFinite()) v else null
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to finiteOnly(x) }
        is List<*> -> v.map { finiteOnly(it) }
        else -> v
    }
}
