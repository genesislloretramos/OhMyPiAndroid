package com.omp.terminal.vm


import omp.shell.fs.RealVfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateRun
import omp.vm.guestapi.UpdateTransport
import omp.vm.provision.GuestLaunch
import omp.vm.provision.GuestStartReport
import omp.vm.provision.GuestState
import omp.vm.provision.GuestWeb
import omp.vm.provision.ProotLauncher
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.readRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

    // ---- the install's bound, and the never-run line ---------------------------------------------------------

    @Test
    fun theInstallIsGivenTheBoundTheStepItselfDeclares() {
        // The number lives in `:core` because it is a fact about the step, and it is the *app* that
        // can enforce it, because only `ProotProcessLauncher` can destroy a process. A boot that
        // built its launcher with the default ten-minute wait would ignore a five-minute ceiling the
        // step's own report and its `STOPPED` outcome are both written against.
        assertEquals(300_000L, omp.vm.provision.GuestPackages.INSTALL_BOUND_MS)
        // And it is a different number from the boot's other bounded step, on purpose: `omp update` is
        // a check whose fast path is half a second, and this is 55 MB and 376.7 MiB of install.
        assertTrue(
            "the install is not a check and must not share the check's bound",
            omp.vm.provision.GuestPackages.INSTALL_BOUND_MS != AgentUpdate.BOUND_MS,
        )
    }

    @Test
    fun aLauncherThatDestroyedTheInstallIsTheSame124TheUpdateUses() {
        // One convention for "we stopped it" in this project, so a report never has to guess whether
        // a 124 came from this app or from the guest.
        assertEquals(omp.vm.guestapi.UpdateOutcome.TIMED_OUT.exitStatus, omp.vm.provision.GuestPackages.TIMED_OUT)
        assertEquals(ProotProcessLauncher.TIMED_OUT, omp.vm.provision.GuestPackages.TIMED_OUT)
    }

    // ---- the thread boundary, which is the one thing between a throw and a dead app ---------------

    @Test
    fun aGuestThreadThatThrowsComesBackAsAStateAndNotAsADeadProcess() {
        // This is the whole of the guard. There is no default uncaught-exception handler in this app,
        // so an exception on the `omp-guest-start` daemon thread reaches Android's and ends the
        // process — a user sees the window open and then the app die, with nothing to explain it.
        val paths = paths()
        val boom = IllegalStateException("mkdir /data/user/0/com.omp.terminal/files/omp: Read-only file system")

        val report = GuestRuntime.guard(paths, sink()) { throw boom }

        assertEquals(GuestState.START_FAILED, report.state)
        assertFalse("a throw is not a serving guest", report.serving)
        assertTrue("the reason names the file, not just the type", report.said!!.contains("Read-only file system"))
    }

    @Test
    fun aContainedThrowIsRecordedWhereTheDoctorReadsItAndNotOnlyInALog() {
        // "An exception swallowed into a log that nothing reads is a silence." The record is what
        // makes it a state: `omp doctor` runs in a second process and this is the only thing it can
        // read about a start that threw.
        val paths = paths()

        GuestRuntime.guard(paths, sink()) { throw IOException("nativeLibraryDir is not a directory") }

        val record = readRecord(RealVfs(), paths)!!
        assertEquals(GuestState.START_FAILED.name, record.stateName)
        assertEquals("IOException: nativeLibraryDir is not a directory", record.said)
    }

    @Test
    fun anErrorIsContainedAsWellAsAnException() {
        // A guest whose proot or its loader is not there produces a `NoClassDefFoundError` the first
        // time anything touches it, and an `Error` on a daemon thread kills the process exactly as
        // dead as an `IOException`. Catching `Exception` alone would not have saved this phone.
        val report = GuestRuntime.guard(paths(), sink()) {
            throw NoClassDefFoundError("libproot-loader.so")
        }

        assertEquals(GuestState.START_FAILED, report.state)
        assertTrue(report.said!!.contains("NoClassDefFoundError"))
        assertTrue(report.said!!.contains("libproot-loader.so"))
    }

    @Test
    fun aLogStreamThatItselfThrowsDoesNotTurnAContainedFailureBackIntoAnUncontainedOne() {
        // The one line the guard writes is a convenience, not the report. A stream that will not take
        // it must not be allowed to rethrow out of the catch and undo the whole point.
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("the log is closed")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("the log is closed")
            override fun flush() = throw IOException("the log is closed")
        }

        val report = GuestRuntime.guard(paths(), broken) { throw IllegalArgumentException("nope") }

        assertEquals(GuestState.START_FAILED, report.state)
        assertTrue(report.said!!.contains("IllegalArgumentException: nope"))
    }

    @Test
    fun aStartThatSucceedsIsUnaffectedByTheGuard() {
        // The happy path, checked because a guard that always returns a report would be a guard that
        // has thrown the feature away. The report the body produced is the report that comes back,
        // the same instance and not a copy.
        val wanted = GuestStartReport(
            state = GuestState.UP,
            port = GuestWeb.RESERVED_PORT,
            reserved = true,
            launched = true,
            answered = true,
            update = omp.vm.guestapi.UpdateOutcome.ALREADY_CURRENT,
            version = "18.3.5",
            said = null,
            at = "2026-09-28T10:00:00Z",
            lines = listOf("an HTTP server returned this build's own chat document"),
        )

        val report = GuestRuntime.guard(paths(), sink()) { wanted }

        assertTrue("the body's own report is returned, not a copy of it", wanted === report)
        assertEquals(GuestState.UP, report.state)
        assertTrue(report.serving)
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

    // ---- helpers -----------------------------------------------------------------------------------

    @get:Rule
    val folder = TemporaryFolder()

    /** A payload directory with nothing in it, which is what the guard's own report needs. */
    private fun paths() = ProvisionPaths(folder.root.path, null, folder.root.path)

    private fun sink(): ByteArrayOutputStream = ByteArrayOutputStream()


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
