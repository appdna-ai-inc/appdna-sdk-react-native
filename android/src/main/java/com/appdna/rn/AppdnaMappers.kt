package com.appdna.rn

import ai.appdna.sdk.PushPayload
import ai.appdna.sdk.SurveyResponse
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.billing.Entitlement
import ai.appdna.sdk.billing.ProductInfo

/**
 * DTO → bridge-safe map mappers.
 *
 * The old shim called `.toMap()` on `List<String>` (from `restorePurchases()`) and on
 * `TransactionInfo`. Neither has such a method. Those calls could never have compiled, and RN's CI
 * — `npm install` + `tsc` — never compiles Kotlin, so nothing said so.
 *
 * ## The reconciled wire shape (N11)
 *
 * The two natives disagree, and neither is a superset:
 *   - iOS `Entitlement`     = `{identifier, isActive, expiresAt, productId}`
 *   - Android `Entitlement` = `{productId, store, status, expiresAt, isTrial, offerType}`
 *
 * Ruling: **union, with the platform-specific fields omitted rather than faked.** An absent key
 * means "this platform does not know it"; it never means `false` or `""`. `isActive` is synthesised
 * here from `status`, because a host asking "does this user have access" must not have to learn the
 * Play status vocabulary. `identifier` mirrors `productId`: Android has no separate identifier, and
 * inventing one would be worse than aliasing an honest value.
 *
 * Dates cross as ISO-8601 strings on both platforms — Android already stores them that way.
 */
internal object AppdnaMappers {

    /** Play statuses that mean the user currently has access. Mirrors `EntitlementCache`. */
    private val ACTIVE_STATUSES = setOf("active", "trialing", "grace_period", "billing_retry")

    fun map(tx: TransactionInfo): Map<String, Any?> = mapOf(
        "transactionId" to tx.transactionId,
        "productId" to tx.productId,
        // Epoch-millis as a String (Play `purchaseTime`). iOS now converts its `Date` to the same
        // epoch-millis shape (was ISO-8601, which made `new Date(tx.purchaseDate)` fail on Android).
        "purchaseDate" to tx.purchaseDate,
        "environment" to tx.environment,
    )

    fun map(entitlement: Entitlement): Map<String, Any?> = buildMap {
        put("identifier", entitlement.productId)
        put("productId", entitlement.productId)
        put("isActive", entitlement.status.lowercase() in ACTIVE_STATUSES)
        entitlement.expiresAt?.let { put("expiresAt", it) }
        // Android-only, and genuinely populated here (unlike iOS, whose getEntitlements() hardcodes
        // isActive=true / expiresAt=null). Present means real.
        put("store", entitlement.store)
        put("status", entitlement.status)
        put("isTrial", entitlement.isTrial)
        entitlement.offerType?.let { put("offerType", it) }
    }

    fun map(product: ProductInfo): Map<String, Any?> = buildMap {
        put("id", product.id)
        put("name", product.name)
        put("description", product.description)
        put("displayPrice", product.formattedPrice)
        put("priceMicros", product.priceMicros)
        // Android-only: iOS's ProductInfo exposes neither a currency code nor an offer token.
        put("currencyCode", product.currencyCode)
        product.offerToken?.let { put("offerToken", it) }
        // `isSubscription` is iOS-only: Play's ProductDetails does not surface it on this DTO, and
        // guessing from the product id would be a lie dressed as a field.
    }

    /**
     * The push payload the SDK parsed. `action` is flattened to `action_type` / `action_value` so the
     * facade's `PushPayload` type matches the snake_case shape the console publishes and the other
     * three SDKs already expose.
     */
    fun map(payload: PushPayload): Map<String, Any?> = buildMap {
        put("push_id", payload.pushId)
        put("title", payload.title)
        put("body", payload.body)
        payload.imageUrl?.let { put("image_url", it) }
        payload.data?.let { put("data", it) }
        payload.action?.let {
            put("action_type", it.type)
            put("action_value", it.value)
        }
        // The registered action BUTTONS. Native has always sent these; the wrapper used to drop the
        // whole list, so a host that got an `actionId` on tap could not look up the button.
        if (payload.actions.isNotEmpty()) {
            put("actions", payload.actions.map { btn ->
                buildMap<String, Any?> {
                    put("id", btn.id)
                    put("label", btn.label)
                    put("action_type", btn.type)
                    // Only when non-empty, as iOS (`AppdnaMappers.swift`) and the Flutter plugin send it.
                    btn.value?.takeIf { it.isNotEmpty() }?.let { put("action_value", it) }
                }
            })
        }
    }

    /**
     * `{questionId, answer}` — and `metadata` when the SDK captured any.
     *
     * `answer` is `Any` natively: a string, a number, a bool, or a list. It crosses as-is through
     * `AppdnaBridge.toWritableMap`, which throws on a type it cannot represent rather than
     * stringifying it.
     */
    fun map(response: SurveyResponse): Map<String, Any?> = buildMap {
        put("questionId", response.questionId)
        put("answer", response.answer)
        response.metadata?.let { put("metadata", it) }
    }
    /**
     * The onboarding location field's structured answer.
     *
     * Both natives already declare these keys in snake_case (`formatted_address`, `state_code`, …),
     * so the two wires agree without a translation layer. Mapping field-by-field rather than
     * reflecting keeps `postal_code`'s nullability explicit: it is the one optional field, and a
     * reflective encoder would emit it as `null` on one platform and omit it on the other.
     */
    fun map(loc: ai.appdna.sdk.onboarding.LocationData): Map<String, Any?> = mapOf(
        "formatted_address" to loc.formatted_address,
        "city" to loc.city,
        "state" to loc.state,
        "state_code" to loc.state_code,
        "country" to loc.country,
        "country_code" to loc.country_code,
        "latitude" to loc.latitude,
        "longitude" to loc.longitude,
        "timezone" to loc.timezone,
        "timezone_offset" to loc.timezone_offset,
        "postal_code" to loc.postal_code,
        "raw_query" to loc.raw_query,
    )

}
