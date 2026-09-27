package com.omp.terminal.web

import omp.vm.provision.ProvisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The origin seam, and the WebView's policy, on the JVM with no device and no WebView.
 *
 * **What is under test here is the boundary, not the browser.** A `WebView` is an Android object
 * and a JVM test cannot build one, which is the whole reason [WebPolicy] is a value and
 * [UiOrigin.permits] is a function: every decision that decides *what the page can reach* is
 * reachable from here, and the one line that hands the policy to a real `WebSettings` is not.
 * That line is named in the report as untested rather than pretended over.
 */
class UiOriginTest {

    private val token = "0123456789ABCDEFGHJKMNPQ"

    private fun loopback(port: Int = 8731) = LoopbackOrigin("http://127.0.0.1:$port", token)
    private fun guest(port: Int = 8080) = GuestOrigin("http://127.0.0.1:$port", token)

    // ---- the decision -------------------------------------------------------------------------

    @Test
    fun aProvisionedDeviceWithAGuestOriginGetsTheGuest() {
        val chosen = UiOrigin.choose(ProvisionState(true, true), guest(), loopback())
        assertTrue("a device with a whole rootfs and an agent in it is the product, not a fallback",
            chosen is GuestOrigin)
    }

    @Test
    fun aDeviceWithOnlyTheRootfsFallsBack() {
        // A glibc binary with no userland to find its loader in is a download and not an agent.
        // This is `ProvisionState.realAgentInstalled` and the app is not asked to override it.
        val chosen = UiOrigin.choose(ProvisionState(true, false), guest(), loopback())
        assertTrue(chosen is LoopbackOrigin)
    }

    @Test
    fun aDeviceWithOnlyTheAgentFallsBack() {
        val chosen = UiOrigin.choose(ProvisionState(false, true), guest(), loopback())
        assertTrue(chosen is LoopbackOrigin)
    }

    @Test
    fun aBareDeviceFallsBack() {
        // Every 32-bit phone, and every phone before its first provisioning. The Kotlin agent is
        // not a consolation prize here, it is the only answer this device can ever have.
        val chosen = UiOrigin.choose(ProvisionState.NONE, guest(), loopback())
        assertTrue(chosen is LoopbackOrigin)
    }

    @Test
    fun aProvisionedDeviceWithNoGuestOriginStillFallsBack() {
        // `UiOrigins.guest()` is null while the port is undecided. A build that cannot name a guest
        // origin must not show a WebView pointed at nothing.
        val chosen = UiOrigin.choose(ProvisionState(true, true), null, loopback())
        assertTrue(chosen is LoopbackOrigin)
    }

    @Test
    fun theDecisionDoesNotDependOnAnythingButTheState() {
        // The same state and the same two origins must always give the same answer, and the
        // fallback must be returned identically however many times it is asked. There is no
        // parameter here a page could reach, which is the property this test is for.
        val state = ProvisionState(true, true)
        repeat(5) {
            assertTrue(UiOrigin.choose(state, guest(), loopback()) is GuestOrigin)
            assertTrue(UiOrigin.choose(state, guest(), loopback(port = 9999)) is GuestOrigin)
        }
    }

    // ---- the URL, and the token in it ----------------------------------------------------------

    @Test
    fun theUrlCarriesTheTokenAsAQueryParameter() {
        // The one mechanism, for both origins: the app hands the WebView a URL with `?t=`, and the
        // page exchanges it for a cookie. Nothing about it is guest-specific, so it keeps working
        // whichever side the API ends up on.
        assertEquals("http://127.0.0.1:8731/login?t=$token", loopback().url())
        assertEquals("http://127.0.0.1:8080/login?t=$token", guest().url())
    }

    @Test
    fun theTwoOriginsSayWhichAgentTheyAre() {
        assertTrue(loopback().label().contains("Kotlin"))
        assertTrue(guest().label().contains("Debian"))
    }

    // ---- what the page may reach ----------------------------------------------------------------

    @Test
    fun anOriginPermitsItsOwnUrls() {
        val origin = loopback()
        for (url in listOf(
            "http://127.0.0.1:8731/login?t=$token",
            "http://127.0.0.1:8731/app.css",
            "http://127.0.0.1:8731/api/state",
            "http://127.0.0.1:8731/",
        )) {
            assertTrue("should have been permitted: $url", origin.permits(url))
        }
    }

    @Test
    fun anOriginRefusesEveryOtherScheme() {
        for (url in listOf(
            "https://127.0.0.1:8731/",
            "file:///data/user/0/com.omp.terminal/files/agent/openai.key",
            "content://com.omp.terminal.provider/x",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "//127.0.0.1:8731/",
            "/login",
            "",
        )) {
            assertFalse("should have been refused: $url", loopback().permits(url))
        }
    }

    @Test
    fun anOriginRefusesEveryOtherPort() {
        // Compared on a parsed port and not with startsWith: `http://127.0.0.1:87310` begins with
        // the base URL's text and is a different server.
        assertFalse(loopback(8731).permits("http://127.0.0.1:87310/api/state"))
        assertFalse(loopback(8731).permits("http://127.0.0.1:8732/api/state"))
        assertFalse(loopback(8731).permits("http://127.0.0.1:8080/api/state"))
    }

    @Test
    fun anOriginRefusesEveryOtherHost() {
        for (url in listOf(
            "http://example.com/",
            "http://127.0.0.2:8731/",
            "http://localhost:8731/",
            "http://10.0.2.2:8731/",
        )) {
            assertFalse("should have been refused: $url", loopback(8731).permits(url))
        }
    }

    @Test
    fun aGuestOriginIsBoundedTheSameWayAndIsNotTheApps() {
        // The guest is a different port and gets its own boundary; it is not a hole in the
        // fallback's, and the fallback is not a hole in the guest's.
        assertFalse(guest(8080).permits("http://127.0.0.1:8731/api/state"))
        assertFalse(loopback(8731).permits("http://127.0.0.1:8080/api/state"))
        assertTrue(guest(8080).permits("http://127.0.0.1:8080/login?t=$token"))
    }

    // ---- the base URL is checked, not trusted ---------------------------------------------------

    @Test
    fun aBaseUrlThatIsNotHttpOnLoopbackIsRefusedAtConstruction() {
        for (base in listOf(
            "https://127.0.0.1:8731",
            "http://example.com:8731",
            "file:///data/user/0/com.omp.terminal",
            "http://127.0.0.1",
            "http://127.0.0.1:0",
            "http://127.0.0.1:notaport",
        )) {
            try {
                LoopbackOrigin(base, token)
                fail("should have been refused at construction: $base")
            } catch (e: IllegalArgumentException) {
                // The right answer, and it is at construction so the app fails while it is being
                // wired rather than after a page has been handed a URL.
            }
        }
    }
}
