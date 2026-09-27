package com.omp.terminal.vm

import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateRun
import omp.vm.guestapi.UpdateTransport
import omp.vm.provision.GuestLaunch
import omp.vm.provision.GuestWeb
import omp.vm.provision.ProotLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream

/**
 * The two halves of the guest path that live in `:app` and can be reached without a phone.
 *
 * **Nothing here starts a process.** [ProotUpdateTransport] is driven over a seam that stands in
 * for the launcher, so what is under test is the two decisions it makes — which bound the process is
 * given, and whether "the guest exited 124" means "we stopped it" — both of which are decisions about
 * a *report* and neither of which is the report of anything real. The one line of the whole path
 * that needs a kernel is the `builder.start()` in [ProotForegroundServer], called out in that
 * class's own source; nothing here reaches it, because reaching it would mean running proot.
 */
class GuestStartDeviceTest {

    /** What a fake launcher saw, and what it was told to say. */
    private class Recording : ProotLauncher {
        var argv: List<String>? = null
        var env: Map<String, String>? = null
        var cwd: String? = null
        var bound = -1L
        var says: String? = null
        var status = 0

        override fun run(argv: List<String>, env: Map<String, String>, cwd: String): Int {
            this.argv = argv
            this.env = env
            this.cwd = cwd
            return status
        }
    }

    private fun transport(rec: Recording, sink: OutputStream) =
        ProotUpdateTransport { out, bound ->
            rec.bound = bound
            out.write((rec.says ?: "").toByteArray(Charsets.UTF_8))
            rec
        }

    // ---- the boot's omp update, as a transport -----------------------------------------------------------

    @Test
    fun theTransportHandsTheVectorTheEnvironmentAndTheDirectoryItWasGivenToTheLauncherUnchanged() {
        // The step that builds the vector is [omp.vm.guestapi.AgentUpdate] and its own test pins it.
        // What is here is the other end: that this class does not touch either on the way to a device,
        // so a test in the middle cannot be hiding a rewritten argv or a merged Android environment.
        val rec = Recording()
        val env = mapOf("TERM" to "xterm-256color", "PROOT_NO_SECCOMP" to "1")

        val run = transport(rec, System.out).run(
            listOf("/usr/local/bin/omp", "update"),
            env,
            "/data/files",
            AgentUpdate.BOUND_MS,
        )

        assertEquals(listOf("/usr/local/bin/omp", "update"), rec.argv)
        assertEquals(env, rec.env)
        assertEquals("/data/files", rec.cwd)
        assertEquals(0, run.status)
        assertFalse("nothing stopped it", run.stopped)
    }

    @Test
    fun theBoundTheStepAsksForIsTheBoundTheLauncherIsBuiltWith() {
        // [omp.vm.guestapi.AgentUpdate] hands a bound per call and [ProotProcessLauncher] takes one in
        // its constructor, so the join between the two is a decision somebody has to make. A transport
        // that built a launcher with its own default would hang a boot for ten minutes on a step the
        // step itself bounded at one.
        val rec = Recording()

        transport(rec, System.out).run(listOf("/usr/local/bin/omp", "update"), emptyMap(), "/d", 12_345L)

        assertEquals(12_345L, rec.bound)
        assertEquals("the step's own bound is 60 s", 60_000L, AgentUpdate.BOUND_MS)
    }

    @Test
    fun aGuestThatNeverStartedIsAFailureAndNotATimeout() {
        // What a phone with no proot in its exec directory actually produces: the launcher's own
        // refusal, its own status, and no timeout marker anywhere in the output. Reading that as a
        // timeout would tell `omp doctor` the update was stopped at 60 s on a device where it was
        // never launched.
        val rec = Recording().apply {
            says = "proot: it could not be started: /data/app/.../libproot.so (Permission denied)\n"
            status = 125
        }

        val run = transport(rec, System.out).run(listOf("/usr/local/bin/omp", "update"), emptyMap(), "/d", 1L)

        assertFalse(run.stopped)
        assertEquals(125, run.status)
        assertTrue(run.output.contains("Permission denied"))
    }

    @Test
    fun aLauncherThatDestroyedTheProcessIsRecognisedByItsOwnSentenceAndNotByItsNumber() {
        // `ProotProcessLauncher.TIMED_OUT` is 124, which is also a status an ordinary program can exit
        // with. The sentence is the only thing that separates "we stopped it" from "it chose that", and
        // a transport that guessed from the number would record a guest's own exit code as a timeout
        // this app invented — which `omp vm.guestapi.UpdateOutcome` then reports as the guest being
        // uncertain about what it had done.
        val rec = Recording().apply {
            says = "Current version: 18.3.5\nproot: still running after 60000ms and was stopped\n"
            status = 124
        }

        val run = transport(rec, System.out).run(listOf("/usr/local/bin/omp", "update"), emptyMap(), "/d", 1L)

        assertTrue("the launcher destroyed it, so there is no real status", run.stopped)
        assertTrue(run.output.contains("Current version: 18.3.5"))
    }

    @Test
    fun aGuestThatExited124OnItsOwnIsNotRecordedAsStopped() {
        val rec = Recording().apply { status = 124 }

        val run = transport(rec, System.out).run(listOf("/usr/local/bin/omp", "update"), emptyMap(), "/d", 1L)

        assertFalse("124 with no marker is the guest's own status, not ours", run.stopped)
        assertEquals(124, run.status)
    }

    @Test
    fun theUpdateStepIsBoundedAndAsksNobodyAndIsGivenTheVectorWithNoFlags() {
        // Re-read from the class the boot contract is written in, rather than copied here: the bound,
        // the one-token vector and the unattended nature of the step are
        // [omp.vm.guestapi.AgentUpdate]'s properties and this build must not have moved any of them.
        assertEquals(60_000L, AgentUpdate.BOUND_MS)
        assertEquals("update", AgentUpdate.UPDATE)
        // The transport is a one-method seam and a fake of it is a whole `UpdateReport`, which is what
        // makes every branch of that class reachable on a JVM.
        assertEquals(1, UpdateTransport::class.java.methods.count { it.name == "run" })
    }

    // ---- the marker the probe asks for -----------------------------------------------------------------------

    @Test
    fun theProbeMarkerIsALineOfTheAppsOwnChatDocument() {
        // [omp.vm.provision.GuestWeb.PAGE_MARKER] is a copy of a line in
        // `app/src/main/assets/web/index.html`, and a copy is only honest while it matches. A marker
        // that had drifted would make the probe refuse a guest that is serving perfectly well, and the
        // report would then say Apache is not answering on a page that is.
        assertTrue(
            "the probe's marker is not in app/src/main/assets/web/index.html any more",
            indexHtml().readText().contains(GuestWeb.PAGE_MARKER),
        )
    }

    @Test
    fun aGuestThatLaunchedIsNotTheSameAsAGuestThatServes() {
        // The distinction the origin is decided on, in the two values a [omp.vm.provision.GuestLaunch]
        // carries. `started = true` is a process; it is [omp.vm.provision.GuestWeb.WebProbe] that turns
        // one into a server, and a start that skipped that would hand a WebView an address nothing is
        // listening on — which is the last remaining way the guest branch could be reached without the
        // Debian answering.
        assertTrue(GuestLaunch(started = true).started)
        assertEquals(null, GuestLaunch(started = true).said)
        val refused = GuestLaunch(started = false, said = "proot: it could not be started: ENOENT")
        assertFalse(refused.started)
        assertTrue(refused.said!!.contains("ENOENT"))
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** `app/src/main/assets/web/index.html`, found by walking up from wherever the test was started. */
    private fun indexHtml(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val page = File(dir, "app/src/main/assets/web/index.html")
            if (page.isFile) return page
            dir = dir.parentFile
        }
        throw AssertionError(
            "app/src/main/assets/web/index.html was not found above ${File(".").absolutePath}: the " +
                "test that says the probe's marker is a line of the page it asks for cannot be run " +
                "without it",
        )
    }
}
