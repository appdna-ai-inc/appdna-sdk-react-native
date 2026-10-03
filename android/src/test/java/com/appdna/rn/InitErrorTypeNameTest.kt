package com.appdna.rn

import ai.appdna.sdk.AppDNAInitError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `type` an init error carries to JS is an explicit string per SDK error class — the names iOS sends — not the
 * class's runtime name, which R8 renames in a minified host build (the wrapper shipped no keep rule for them).
 *
 * A JVM test cannot minify, so a runtime-name mapping and the explicit one answer alike here. What this test pins is
 * the table, on both paths that report a type (`onInitDegraded` through the forwarder, and `getLastInitError`, which
 * calls the same [initErrorTypeName]). `scripts/__tests__/wrapper-error-type-names.test.ts` is the discriminating
 * check: no bridge path reads a runtime class name for a type, every `AppDNAInitError` subclass is in the table, and
 * the keep rule ships (NEGATIVE CONTROL: it fails on the previous sources).
 */
class InitErrorTypeNameTest {
    @Test fun everyInitErrorHasItsExplicitTypeString() {
        assertEquals("BootstrapFailed", initErrorTypeName(AppDNAInitError.BootstrapFailed("x")))
        assertEquals("SubsystemFailed", initErrorTypeName(AppDNAInitError.SubsystemFailed("billing", "x")))
        assertEquals("FirebaseConfigMissing", initErrorTypeName(AppDNAInitError.FirebaseConfigMissing("x")))
        // Any other throwable keeps its class name.
        assertEquals("IllegalStateException", initErrorTypeName(IllegalStateException("x")))
    }

    @Test fun theForwarderReportsTheExplicitType() {
        val emitted = mutableListOf<Map<String, Any?>>()
        val forwarder = InitForwarder { _, payload -> emitted += payload }
        forwarder.onInitDegraded(AppDNAInitError.SubsystemFailed("billing", "boom"))
        assertEquals("SubsystemFailed", emitted.single()["type"])
        assertEquals("Subsystem 'billing' failed to initialize: boom", emitted.single()["message"])
    }
}
