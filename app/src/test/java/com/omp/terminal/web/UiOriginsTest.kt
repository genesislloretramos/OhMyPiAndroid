package com.omp.terminal.web

import omp.vm.provision.GuestWeb
import omp.vm.provision.ProvisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The origin decision, on the JVM, with no `Context` and no WebView.
 *
 * **What is under test is the gate, and the gate is one boolean.** Everything else the app knows
 * about the chat — a token, a published URL, a rootfs on the disk — was already there before the
 * guest was reachable, and what made the guest's branch unreachable was that nothing ever asked
 * whether a page came back. This test is the gate: **a `GuestOrigin` exists if and only if a request
 * for this build's own chat document came back from the port Apache inside the Debian was told to
 * use**, and the four values that decide it are four arguments rather than a `Context`.
 *
 * **The WebView's boundary is not re-decided here and is not weakened by the port changing.**
 * [UiOriginTest] holds it against a guest on any port; [UiOriginsTest.theGuestsPortIsBoundedTheSameWay]
 * holds it against the one this build actually reserves.
 */
class UiOriginsTest {

    private val token = "0123456789ABCDEFGHJKMNPQ"
    private val loopbackBase = "http://127.0.0.1:8731"
    private val guestBase = GuestWeb.baseUrl(GuestWeb.RESERVED_PORT)

    // ---- the gate ------------------------------------------------------------------------------------

    @Test
    fun chooseIsGivenNoGuestUntilAPageCameBack() {
        // The load-bearing line. `guestServing = false` is what a guest that was started and answered
        // nothing looks like, and it is the state every device is in until proot has run.
        val chosen = UiOrigins.choose(ProvisionState(true, true), guestServing = false, token, loopbackBase)

        assertTrue("a guest that was not seen answering must not be shown", chosen is LoopbackOrigin)
    }

    @Test
    fun chooseIsGivenTheGuestOnceAPageCameBack() {
        val chosen = UiOrigins.choose(ProvisionState(true, true), guestServing = true, token, loopbackBase)

        assertTrue("a serving Debian is the product, not a fallback", chosen is GuestOrigin)
        assertEquals(guestBase, (chosen as GuestOrigin).base.text)
    }

    @Test
    fun aPageComingBackIsNotEnoughWithoutADebianAndItsAgentOnTheDisk() {
        // The other half, and it is a fact about the filesystem rather than about the server: a
        // 32-bit device can have Apache answering and will never have the real agent, so the chat
        // stays on this app's own server and says which agent it is.
        for (state in listOf(ProvisionState.NONE, ProvisionState(true, false), ProvisionState(false, true))) {
            assertTrue(
                "a guest origin for $state would be a page with no agent behind it",
                UiOrigins.choose(state, guestServing = true, token, loopbackBase) is LoopbackOrigin,
            )
        }
    }

    @Test
    fun aStartThatFailedLeavesTheAppsOwnServerInPlaceAndNamesIt() {
        val chosen = UiOrigins.choose(ProvisionState(true, true), guestServing = false, token, loopbackBase)

        // "And says so" is the app's own half of the contract: the origin says which agent it is on
        // its own label, and `omp doctor`'s `guest origin` section says which of the five states put
        // it there. Neither is a silent substitution.
        assertTrue(chosen!!.label().contains("Kotlin"))
        assertFalse(chosen.label().contains("Debian"))
    }

    // ---- nothing to show yet -------------------------------------------------------------------------

    @Test
    fun nothingIsChosenBeforeTheServiceHasPublishedAnAddress() {
        assertNull(UiOrigins.choose(ProvisionState(true, true), guestServing = true, token, null))
    }

    @Test
    fun nothingIsChosenBeforeTheTokenHasBeenIssued() {
        // A base URL with a blank credential is not an origin. `HttpLoopback.Base.parse` would accept
        // the base and the page would then be handed `?t=`, which is a credential that does not work
        // and a screen that never loads.
        assertNull(UiOrigins.choose(ProvisionState(true, true), guestServing = true, null, loopbackBase))
    }

    @Test
    fun aGuestThatIsServingIsStillRefusedWithNoLoopbackAddressToFallBackTo() {
        // Null rather than the guest. The fallback is a parameter of `UiOrigin.choose` and is not
        // optional there, so a device whose own server has not bound has no origin at all — which is
        // a real answer, and the terminal is what the app is showing until there is an address.
        assertNull(UiOrigins.choose(ProvisionState(true, true), guestServing = true, token, loopbackBase = null))
    }

    // ---- the port, and the boundary it has to keep --------------------------------------------------------

    @Test
    fun theGuestsPortIsTheOneThisBuildReserves() {
        // One number in one place. `omp.vm.provision.GuestStart` checks it, `omp doctor` prints it
        // and the WebView is pointed at it; a second spelling of it anywhere would be a second chance
        // to load a page from a different server than the one that was probed.
        assertEquals(GuestWeb.RESERVED_PORT, UiOrigins.GUEST_UI_PORT)
        assertEquals("http://127.0.0.1:8732", UiOrigins.baseUrl(UiOrigins.GUEST_UI_PORT))
    }

    @Test
    fun theGuestsPortIsBoundedTheSameWay() {
        // The security boundary does not move because the port did. Compared on scheme, host and
        // port and not on a prefix, and the guest's origin is no more and no less bounded than the
        // app's own.
        val guest = GuestOrigin(guestBase, token)
        assertTrue(guest.permits("$guestBase/login?t=$token"))
        assertTrue(guest.permits("$guestBase/api/state"))
        assertTrue(guest.permits("$guestBase/app.js"))
        // The app's own port, a longer number that begins with it, and the neighbouring one.
        assertFalse(guest.permits("http://127.0.0.1:8731/api/state"))
        assertFalse(guest.permits("http://127.0.0.1:87320/api/state"))
        assertFalse(guest.permits("http://127.0.0.1:8733/api/state"))
        // And the two things a page from a Debian would most want.
        assertFalse(guest.permits("https://127.0.0.1:8732/"))
        assertFalse(guest.permits("file:///data/user/0/com.omp.terminal/files/agent/openai.key"))
    }

    @Test
    fun theTokenIsAppendedToTheGuestsUrlAndNowhereElse() {
        // The one mechanism for both origins: the app hands the WebView a URL with `?t=` and the page
        // exchanges it for a cookie. Nothing guest-side has to know this app's token for that to
        // work, and the guest's PHP is not taught anything about it.
        val guest = UiOrigins.choose(ProvisionState(true, true), guestServing = true, token, loopbackBase)
        assertEquals("$guestBase/login?t=$token", guest!!.url())
        // And the WebView adds nothing: no header, no injected script, no evaluateJavascript.
        assertFalse(guest.url().contains("omp"))
    }
}
