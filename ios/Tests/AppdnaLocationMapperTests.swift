import XCTest
import AppDNASDK
@testable import appdna_sdk_react_native

/// The location answer's provenance ids cross the bridge. `getLocationData` returns the native `LocationData`
/// through `AppdnaMappers.map`, a hand-written key list: a key left out of it never reaches JavaScript, and the jest
/// tests (which mock the native module) cannot see that. Red control: remove `mapbox_id` from the mapper and the
/// first test fails.
final class AppdnaLocationMapperTests: XCTestCase {
    private func decode(_ json: String) throws -> LocationData {
        try JSONDecoder().decode(LocationData.self, from: Data(json.utf8))
    }

    func testAResolvedAnswerCarriesItsProvenanceIds() throws {
        let loc = try decode(#"{"formatted_address":"Porto, Porto, Portugal","city":"Porto","raw_query":"port","mapbox_id":"dXJuOm1ieHBsYzpwcm9iZQ","place_id":"probe-place-id"}"#)
        let out = AppdnaMappers.map(loc)
        XCTAssertEqual(out["mapbox_id"] as? String, "dXJuOm1ieHBsYzpwcm9iZQ")
        XCTAssertEqual(out["place_id"] as? String, "probe-place-id")
        XCTAssertEqual(out["city"] as? String, "Porto")
    }

    func testATypedAnswerCarriesThemAsNull() throws {
        let out = AppdnaMappers.map(try decode(#"{"formatted_address":"porto","raw_query":"porto"}"#))
        XCTAssertTrue(out["mapbox_id"] is NSNull)
        XCTAssertTrue(out["place_id"] is NSNull)
    }
}
