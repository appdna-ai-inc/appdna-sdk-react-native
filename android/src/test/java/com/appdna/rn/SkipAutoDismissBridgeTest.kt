package com.appdna.rn

import ai.appdna.sdk.AppDNA
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The paywall auto-dismiss opt-out reaches the native flag from React Native.
 *
 * ## What is under test, and what is NOT
 *
 * That the FLAG suppresses the dismiss is the native SDK's own property, and `PaywallManager`'s
 * suite already holds it: the restore handler re-reads `skipNextAutoDismissOnRestore` on the line
 * after it forwards `onPaywallRestoreCompleted`, and clears it on every terminal event. Repeating
 * that here would be testing the SDK through a wrapper.
 *
 * What was missing is the WRAPPER half, and it is the half that was actually broken: React Native
 * had no way to set the flag at all, while iOS, Android and Flutter all did. So the assertion is
 * that the module method writes THE SAME property the restore handler reads — not a private copy,
 * not a no-op body.
 *
 * ## Why `false` first is the control, not ceremony
 *
 * The defect shape for a bridged method is a body that runs and does nothing — bridged, and dead.
 * A test that only asserted `set(true) ⇒ true` would pass against a flag that
 * was already `true` for some other reason, and a test that only asserted the write would pass
 * against a setter stuck at `true`. Reading the baseline, writing both values, and reading back
 * each time is what distinguishes a real setter from both.
 */
@RunWith(RobolectricTestRunner::class)
class SkipAutoDismissBridgeTest {

    private lateinit var module: AppdnaModule

    @Before
    fun setUp() {
        val ctx = mock(ReactApplicationContext::class.java)
        org.mockito.Mockito.`when`(ctx.applicationContext)
            .thenReturn(RuntimeEnvironment.getApplication())
        module = AppdnaModule(ctx)
    }

    /** The flag is global SDK state — leaving it set would arm the next test's restore. */
    @After
    fun tearDown() {
        AppDNA.paywall.skipNextAutoDismissOnRestore = false
    }

    @Test
    fun `the module setter writes the flag the restore handler reads`() {
        assertFalse(
            "the SDK's default is to auto-dismiss after a restore that found products",
            AppDNA.paywall.skipNextAutoDismissOnRestore,
        )

        module.skipNextAutoDismissOnRestore(true)
        assertTrue(
            "skipNextAutoDismissOnRestore(true) did not reach AppDNA.paywall — a bridged-but-dead body",
            AppDNA.paywall.skipNextAutoDismissOnRestore,
        )

        module.skipNextAutoDismissOnRestore(false)
        assertFalse(
            "the setter could not take the flag back down — a host cannot cancel its own opt-out",
            AppDNA.paywall.skipNextAutoDismissOnRestore,
        )
    }

    /**
     * The flag is readable BEFORE any paywall is presented, which is what makes the documented
     * usage possible at all: a React Native host must arm it before the Restore tap, because its
     * `onPaywallRestoreCompleted` crosses the bridge asynchronously and runs after native has
     * already decided. Were `AppDNA.paywall` to require a live presentation, that advice would be
     * impossible to follow and the API would be useless on this platform.
     */
    @Test
    fun `the flag is settable with no paywall on screen`() {
        module.skipNextAutoDismissOnRestore(true)
        assertTrue(AppDNA.paywall.skipNextAutoDismissOnRestore)
    }
}
