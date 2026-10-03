import XCTest
import AppDNASDK
@testable import appdna_sdk_react_native

/**
 The bridge marshalling, on iOS.

 ## Why this file exists

 `AppdnaBridgeJsonTest.kt` is thorough: nested maps, nested lists, `null` inside an object, a refusal
 to stringify what it cannot represent, malformed input → `null`. NOTHING on the iOS side tested
 `AppdnaJSON` or `AppdnaMappers` at all. E9 is the marshalling layer, it is hand-written twice, and a
 marshalling bug that exists on one platform only is the most expensive kind: the two hosts get
 different values from the same call and the SDK reports no error on either.

 A jest test cannot reach any of this — the native module is mocked away, so it observes neither
 `JSONSerialization`'s `.fragmentsAllowed` nor the `withNulls` null-handling in `AppdnaMappers` that
 decides whether a key is absent or present-as-`null`.

 ## Same cases, same oracles as Android

 Deliberately the SAME table as `AppdnaBridgeJsonTest`, because the point of AC-15 is that the two
 encodings agree. Where the platforms' primitives differ, the ORACLE is still the other platform's
 output — notably the leniency cases: `JSONTokener` (Android) reads `not json` as the bare word
 "not" and ignores trailing garbage, while `JSONSerialization` (here) refuses both. Android was
 dragged to iOS's answer; this pins iOS to it so the agreement cannot drift back.
 */
final class AppdnaJSONTests: XCTestCase {

    // MARK: - encode

    func testEncodesScalarsAtTopLevel() {
        // `JSONSerialization` refuses a bare scalar unless `.fragmentsAllowed` is set, and a boolean
        // feature flag is the single most common thing `getRemoteConfig` returns. Without the option
        // every flag crossed as "null".
        XCTAssertEqual(AppdnaJSON.encode(nil), "null")
        XCTAssertEqual(AppdnaJSON.encode(NSNull()), "null")
        XCTAssertEqual(AppdnaJSON.encode(true), "true")
        XCTAssertEqual(AppdnaJSON.encode(3), "3")
        XCTAssertEqual(AppdnaJSON.encode("a"), "\"a\"")
    }

    func testEncodesANestedMapAsJSONNotAsItsDescription() {
        // Android's counterpart bug: `JSONObject(map)` does not wrap recursively, so a nested Map was
        // written as its `toString()` — `{"a":"{b=1}"}` — and an object-valued remote-config flag
        // reached the facade as a string. Swift cannot make that mistake the same way, but the WIRE it
        // has to produce is the same one, so it is asserted the same way.
        XCTAssertEqual(AppdnaJSON.encode(["a": ["b": 1]]), #"{"a":{"b":1}}"#)
    }

    func testEncodesANestedList() {
        XCTAssertEqual(AppdnaJSON.encode(["a": [1, 2]]), #"{"a":[1,2]}"#)
    }

    func testEncodesAJSONNullInsideAnObject() {
        XCTAssertEqual(AppdnaJSON.encode(["a": NSNull()]), #"{"a":null}"#)
    }

    func testRefusesToStringifyATypeItCannotRepresent() {
        // A type neither side can represent encodes as `null`, NEVER as its description. Encoding
        // `Optional(Foo)` or `<Foo: 0x600…>` would be a lie that typechecks: the facade would parse it
        // as a perfectly good string and hand the host garbage that looks like data.
        final class Unrepresentable {}
        XCTAssertEqual(AppdnaJSON.encode(Unrepresentable()), "null")
        XCTAssertEqual(AppdnaJSON.encode(Date()), "null")
    }

    // MARK: - decode (P8 / setSessionData)

    func testDecodesEveryLegalTopLevelValue() {
        XCTAssertNil(AppdnaJSON.decode("null"))
        XCTAssertNil(AppdnaJSON.decode(""))
        XCTAssertEqual(AppdnaJSON.decode("true") as? Bool, true)
        XCTAssertEqual(AppdnaJSON.decode("3") as? Int, 3)
        XCTAssertEqual(AppdnaJSON.decode("\"a\"") as? String, "a")
        XCTAssertEqual(AppdnaJSON.decode(#"{"a":1}"#) as? [String: Int], ["a": 1])
        XCTAssertEqual(AppdnaJSON.decode("[1,2]") as? [Int], [1, 2])
    }

    func testAMalformedReplyDecodesToNilRatherThanThrowing() {
        // A host cannot make native throw by replying with garbage. For a veto hook, `nil` means "the
        // host said nothing — apply the default", which is the same thing a timeout means.
        XCTAssertNil(AppdnaJSON.decode("not json"))
        XCTAssertNil(AppdnaJSON.decode("{"))
    }

    func testLeniencyDoesNotTurnGarbageIntoAnOpinion() {
        // The Android leniency cases, asserted here so the agreement is pinned on BOTH sides.
        // `JSONTokener` read `not` as a bare word and ignored trailing garbage, so garbage came back as
        // a VALUE — an opinion. For a veto that is the difference between "apply the default" and "the
        // host said yes".
        XCTAssertNil(AppdnaJSON.decode("not"))
        XCTAssertNil(AppdnaJSON.decode("true false"))
        XCTAssertNil(AppdnaJSON.decode(#"{"a":1} junk"#))

        // …while the legal forms still decode.
        XCTAssertEqual(AppdnaJSON.decode("\"not\"") as? String, "not")
        XCTAssertEqual(AppdnaJSON.decode("  true  ") as? Bool, true)
    }

    func testANullInsideAnObjectSurvivesDecodingAsNSNull() {
        // Only a TOP-LEVEL null collapses to nil. A null INSIDE an object is data — Android keeps it as
        // a map entry with a null value, and a facade doing `'k' in obj` must see the key on both.
        let decoded = AppdnaJSON.decode(#"{"a":null,"b":1}"#) as? [String: Any]
        XCTAssertNotNil(decoded)
        XCTAssertTrue(decoded?["a"] is NSNull)
        XCTAssertEqual(decoded?["b"] as? Int, 1)
    }

    func testEncodeDecodeRoundTrips() {
        // The pair is used as a pair: `setSessionData(encode(x))` then `getSessionData() -> encode(…)`.
        let original: [String: Any] = ["s": "v", "n": 1, "b": true, "arr": [1, 2], "obj": ["k": "v"]]
        let round = AppdnaJSON.decode(AppdnaJSON.encode(original)) as? [String: Any]
        XCTAssertEqual(round?["s"] as? String, "v")
        XCTAssertEqual(round?["n"] as? Int, 1)
        XCTAssertEqual(round?["b"] as? Bool, true)
        XCTAssertEqual(round?["arr"] as? [Int], [1, 2])
        XCTAssertEqual((round?["obj"] as? [String: Any])?["k"] as? String, "v")
    }
}

/**
 The DTO → bridge-dictionary mappers, on iOS.

 The rule these encode is "the SDKs disagree about the type; the WIRE must not". Every one of them is
 a hand-written translation into ANOTHER platform's key names, and until now nothing checked a single
 one of them. `AppdnaScreenResultWireTests` covers `ScreenResult`'s error dialect and its
 `last_action` null; this covers the rest.
 */
final class AppdnaMappersTests: XCTestCase {

    // MARK: - Entitlement: absent ≠ null ≠ false

    func testEntitlementOmitsExpiryRatherThanSendingNull() {
        // "A key that is absent means *this platform does not know it*; it never means `false` or `""`."
        // Bridging an `NSNull` here would be indistinguishable, in the JS facade, from a host that
        // explicitly has no expiry — and `expiresAt === null` vs `expiresAt === undefined` is exactly
        // the check a host writes to decide whether a subscription is lifetime.
        let mapped = AppdnaMappers.map(
            Entitlement(identifier: "pro", isActive: true, expiresAt: nil, productId: "com.app.pro")
        )
        XCTAssertNil(mapped["expiresAt"], "an absent expiry must not cross as NSNull")
        XCTAssertEqual(mapped["identifier"] as? String, "pro")
        XCTAssertEqual(mapped["productId"] as? String, "com.app.pro")
        XCTAssertEqual(mapped["isActive"] as? Bool, true)

        // The Android-only fields are OMITTED, not faked. `store: ""` would read as a real store.
        XCTAssertNil(mapped["store"])
        XCTAssertNil(mapped["status"])
        XCTAssertNil(mapped["isTrial"])
    }

    func testEntitlementExpiryCrossesAsAnISO8601String() {
        // Not an epoch number: Android already stores these as ISO strings, and a string round-trips
        // losslessly through the bridge's number coercion (every JS number arrives as a Double).
        let mapped = AppdnaMappers.map(
            Entitlement(
                identifier: "pro",
                isActive: true,
                expiresAt: Date(timeIntervalSince1970: 1_700_000_000),
                productId: "com.app.pro"
            )
        )
        XCTAssertEqual(mapped["expiresAt"] as? String, "2023-11-14T22:13:20Z")
    }

    // MARK: - SectionAction: the discriminators are Android's, verbatim

    func testSectionActionDiscriminatorsMatchAndroid() {
        XCTAssertEqual(AppdnaMappers.map(SectionAction.next)["type"] as? String, "next")
        XCTAssertEqual(AppdnaMappers.map(SectionAction.dismiss)["type"] as? String, "dismiss")
        XCTAssertEqual(AppdnaMappers.map(SectionAction.restart)["type"] as? String, "restart")
        XCTAssertEqual(AppdnaMappers.map(SectionAction.complete)["type"] as? String, "complete")

        let navigate = AppdnaMappers.map(SectionAction.navigate(screenId: "s2"))
        XCTAssertEqual(navigate["type"] as? String, "navigate")
        XCTAssertEqual(navigate["screenId"] as? String, "s2")

        // `custom` renames its payload on the wire — `customType`, not `type`, which would collide with
        // the discriminator itself and make the action unreadable.
        let custom = AppdnaMappers.map(SectionAction.custom(type: "confetti", value: "big"))
        XCTAssertEqual(custom["type"] as? String, "custom")
        XCTAssertEqual(custom["customType"] as? String, "confetti")
        XCTAssertEqual(custom["value"] as? String, "big")
    }

    func testSectionActionKeepsNilsAsNSNullForAndroidParity() {
        // R9 wire-parity: Android's `toActionMap` (SectionContext.kt) builds with `mapOf`, which keeps an
        // optional key PRESENT-as-null. The iOS mapper matches by emitting `NSNull()` (via `withNulls`),
        // NOT by dropping the key — otherwise a host doing `'id' in action` / `action.id === null` would
        // read `undefined` on iOS and `null` on Android for the same input. `NSNull()` reaches JS as
        // `null` (the same path `last_action` uses; mirrors AppdnaScreenResultWireTests' last_action test).
        let paywall = AppdnaMappers.map(SectionAction.showPaywall(id: nil))
        XCTAssertEqual(paywall["type"] as? String, "showPaywall")
        if let paywallId = paywall["id"] {
            XCTAssertTrue(paywallId is NSNull, "showPaywall.id must be present as NSNull, not a real value")
        } else {
            XCTFail("showPaywall.id must be PRESENT as NSNull (Android parity), but the key was omitted")
        }

        let track = AppdnaMappers.map(SectionAction.track(event: "tapped", properties: nil))
        XCTAssertEqual(track["event"] as? String, "tapped")
        if let trackProps = track["properties"] {
            XCTAssertTrue(trackProps is NSNull, "track.properties must be present as NSNull, not a real value")
        } else {
            XCTFail("track.properties must be PRESENT as NSNull (Android parity), but the key was omitted")
        }
    }
}
