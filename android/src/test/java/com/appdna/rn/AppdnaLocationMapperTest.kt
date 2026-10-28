package com.appdna.rn

import ai.appdna.sdk.onboarding.LocationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location answer's provenance ids cross the bridge. `getLocationData` returns the native `LocationData` through
 * `AppdnaMappers.map`, a hand-written key list: a key left out of it never reaches JavaScript, and the jest tests
 * (which mock the native module) cannot see that. Red control: remove `mapbox_id` from the mapper and the first test
 * fails.
 */
class AppdnaLocationMapperTest {
    @Test
    fun aResolvedAnswerCarriesItsProvenanceIds() {
        val out = AppdnaMappers.map(LocationData(formatted_address = "Porto, Porto, Portugal", city = "Porto", raw_query = "port", mapbox_id = "dXJuOm1ieHBsYzpwcm9iZQ", place_id = "probe-place-id"))
        assertEquals("dXJuOm1ieHBsYzpwcm9iZQ", out["mapbox_id"])
        assertEquals("probe-place-id", out["place_id"])
        assertEquals("Porto", out["city"])
    }

    @Test
    fun aTypedAnswerCarriesThemAsNull() {
        val out = AppdnaMappers.map(LocationData(formatted_address = "porto", raw_query = "porto"))
        assertTrue(out.containsKey("mapbox_id"))
        assertNull(out["mapbox_id"])
        assertNull(out["place_id"])
    }
}
