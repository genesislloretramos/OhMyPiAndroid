package omp.vm.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port, the command, and the retry around the probe — the three decisions that stand between
 * "this build named a port" and "a WebView is pointed at a Debian".
 *
 * **No socket is opened here and no clock is read.** The two that would make this test slow and
 * flaky are both parameters ([GuestWeb.PortBinder] and [GuestWeb.WebProbe]), which is the only reason
 * the interesting case — a port somebody else already holds — is reachable on a build machine at
 * all. What is under test is what the app *does* with each answer, and that is the half that decides
 * whether a user is told the truth about which program is answering.
 */
class GuestWebTest {

    // ---- the number ------------------------------------------------------------------------------

    @Test
    fun theReservedPortIsARealPortAndNotTheAppsOwn() {
        assertTrue(GuestWeb.RESERVED_PORT in 1024..65535)
        // The one collision that would be guaranteed rather than checked: an app whose own server and
        // whose guest's Apache both wanted the same number would fail at bind time on every device.
        // One apart from `ChatService.PREFERRED_PORT` makes that impossible by construction.
        assertTrue(
            "the guest's port must not be the app's own loopback port",
            GuestWeb.RESERVED_PORT != 8731,
        )
    }

    @Test
    fun theBaseUrlIsPlainHttpOnLoopbackWithNoPath() {
        // The shape `HttpLoopback.Base.parse` accepts and nothing else: a base with a trailing slash
        // or a path is refused at construction, and the URL predicate would then compare against a
        // base no request could ever match.
        assertEquals("http://127.0.0.1:8732", GuestWeb.baseUrl(GuestWeb.RESERVED_PORT))
    }

    // ---- the command -----------------------------------------------------------------------------

    @Test
    fun theApacheCommandIsTheGestsOwnControlProgramInTheForeground() {
        // `apache2ctl` rather than `apache2`, because Debian's own `envvars` is what sets the runtime
        // directory, the pid file and the user, and the drop-in this app links in with `a2enconf` is
        // read by the server that `envvars` configures. `-D FOREGROUND` because a proot guest has no
        // init and the start has to hold a process open rather than fork one it cannot keep.
        assertEquals(
            listOf("/usr/sbin/apache2ctl", "-D", "FOREGROUND"),
            GuestWeb.APACHE_COMMAND,
        )
    }

    @Test
    fun theCommandNamesTheProgramByItsGuestPathAndItsFlagsAsLiterals() {
        // The program is absolute and inside the Debian, so nothing here is resolved against the
        // phone: a relative name would be a path proot's working directory decides, and the working
        // directory is `/root`. The two flags are the guest's own words and not paths, pinned
        // literally because `-D` is a Debian Apache convention and not a proot one.
        assertEquals(GuestWeb.APACHE_CTL, GuestWeb.APACHE_COMMAND.first())
        assertTrue(
            "'${GuestWeb.APACHE_CTL}' is not a guest path",
            GuestWeb.APACHE_CTL.startsWith("/usr/sbin/"),
        )
        assertEquals(listOf("-D", "FOREGROUND"), GuestWeb.APACHE_COMMAND.drop(1))
    }

    // ---- the probe, and the retry around it --------------------------------------------------------

    @Test
    fun theProbeAsksOnceWhenTheFirstAnswerIsYes() {
        var asked = 0
        val probe = GuestWeb.RetryingProbe({ asked++; true }, attempts = 6, wait = { })

        assertTrue(probe.answers(GuestWeb.RESERVED_PORT))
        assertEquals("a server that answers must not be asked again", 1, asked)
    }

    @Test
    fun theProbeGivesUpAfterItsOwnNumberOfAttemptsAndNotOneMore() {
        var asked = 0
        var pauses = 0
        val probe = GuestWeb.RetryingProbe(
            { asked++; false },
            attempts = GuestWeb.ATTEMPTS,
            pauseMs = GuestWeb.PAUSE_MS,
            wait = { pauses++ },
        )

        assertFalse(probe.answers(GuestWeb.RESERVED_PORT))
        // Six attempts and five pauses: a wait after the last one would add half a second to every
        // start on a device where nothing is ever coming, and the answer is already known by then.
        assertEquals(GuestWeb.ATTEMPTS, asked)
        assertEquals(GuestWeb.ATTEMPTS - 1, pauses)
    }

    @Test
    fun theProbeStopsAtTheFirstYesSoASlowBootIsNotReportedAsADeadOne() {
        // Apache binds its port after `apache2ctl` has read its configuration and forked its
        // workers, and under a path emulator that is not instant. A probe that asked once would turn
        // a working guest into a `APACHE_NOT_ANSWERING` report on a phone that is merely slow.
        val answers = ArrayDeque(listOf(false, false, true))
        var asked = 0
        val probe = GuestWeb.RetryingProbe({ asked++; answers.removeFirst() }, attempts = 6, wait = { })

        assertTrue(probe.answers(GuestWeb.RESERVED_PORT))
        assertEquals(3, asked)
    }

    @Test
    fun theProbeAsksThePortItWasGivenAndNotTheReservedOne() {
        // The port is a parameter everywhere it can be, so a caller that reserved a different number
        // — or a test that wants to watch a collision — is not quietly re-pointed at the constant.
        val asked = ArrayList<Int>()
        val probe = GuestWeb.RetryingProbe({ asked += it; true }, wait = { })

        probe.answers(9999)
        probe.answers(GuestWeb.RESERVED_PORT)

        assertEquals(listOf(9999, GuestWeb.RESERVED_PORT), asked)
    }

    // ---- the bind check, and what it is for -----------------------------------------------------------

    @Test
    fun aPortSomebodyElseHoldsIsReportedAsNotFreeAndNothingElseIsInvented() {
        // This is the whole of the seam, and the reason it exists: the collision case is the one
        // that must be reachable in a test, and on a build machine the only way to reach it is to be
        // told the answer. A refusal has to be a plain `false` with no exception, because every
        // failure of the real binder — no permission, a port the kernel will not give an app — is the
        // same sentence to the caller.
        val held = GuestWeb.PortBinder { false }

        assertFalse(held.isFree(GuestWeb.RESERVED_PORT))
    }
}
