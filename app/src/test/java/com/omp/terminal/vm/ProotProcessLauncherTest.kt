package com.omp.terminal.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The launcher's half that is checkable without a phone.
 *
 * **What this file can check is that the right process is described.** A `ProcessBuilder` starts
 * nothing when it is built, so the four decisions a caller can be wrong about — the argument
 * vector, the environment, the working directory, and where stderr goes — are all readable on the
 * JVM. That is the part [omp.vm.provision.ProotCommandTest] hands over and this class is given.
 *
 * **What it cannot check is that the process works.** Nothing in this repository has ever run
 * proot: not this build, not on a device, not in a test. Whether the binary is in the native
 * library directory, whether this proot build accepts `-r`/`-b`/`-w`/`-k`, whether the netboot
 * rootfs has the guest loader in it, and whether the OEM SELinux policy allows `ptrace` are all
 * open, and this class does not pretend otherwise. A test that "verified proot" would verify a
 * mock, and the mock would be this file.
 */
class ProotProcessLauncherTest {

    private val sink = ByteArrayOutputStream()
    private val launcher = ProotProcessLauncher(sink)

    private val argv = listOf(
        "/data/app/~~a==/com.omp.terminal-1/lib/arm64/libproot.so",
        "-r", "/data/user/0/com.omp.terminal/files/omp/rootfs",
        "-b", "/data/user/0/com.omp.terminal/files:/data/user/0/com.omp.terminal/files",
        "-w", "/root",
        "-k", "/data/user/0/com.omp.terminal/files/proot.pid",
        "/usr/local/bin/omp", "update",
    )

    /** The environment `ProotCommand.env()` produces: the guest's, complete. */
    private val env = linkedMapOf(
        "HOME" to "/root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PROOT_NO_SECCOMP" to "1",
    )

    private val cwd = System.getProperty("java.io.tmpdir")

    // ---- the argument vector ---------------------------------------------------------------------

    @Test
    fun theBuilderCarriesExactlyTheArgvItWasHanded() {
        // Verbatim, in order, argv[0] first. ProotCommandTest pins what the vector says; this pins
        // that nothing between here and the kernel changed it.
        val built = launcher.prepare(argv, env, cwd)
        assertEquals(argv, built.command())
    }

    @Test
    fun anEmptyArgvIsRefused() {
        // An empty vector names no program, and ProcessBuilder would fail with a message about
        // indexing rather than about proot.
        try {
            launcher.prepare(emptyList(), env, cwd)
            fail("an empty argument vector should have been refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty argument vector"))
        }
    }

    @Test
    fun aFlagWithASpaceInItIsOneArgument() {
        // The most ordinary way a hand-built process call goes wrong: the path with spaces in it
        // arrives as three arguments and proot is told to run a directory.
        val spaced = listOf("/data/app/lib native/libproot.so", "-r", "/root fs")
        assertEquals(spaced, launcher.prepare(spaced, env, cwd).command())
    }

    // ---- the environment ------------------------------------------------------------------------

    @Test
    fun theBuilderCarriesExactlyTheEnvItWasHanded() {
        assertEquals(env, launcher.prepare(argv, env, cwd).environment())
    }

    @Test
    fun theAndoidEnvironmentIsClearedRatherThanMerged() {
        // ProotCommand.env() is documented as the guest's WHOLE environment and not a delta. A
        // merge would hand the guest whatever this process happened to have: an LD_LIBRARY_PATH
        // pointing at Android's own libraries fails like a missing libc6, and ANDROID_ROOT and
        // BOOTCLASSPATH mean nothing in a Debian and cost an afternoon.
        val built = launcher.prepare(argv, mapOf("HOME" to "/root"), cwd)
        assertEquals(setOf("HOME"), built.environment().keys)
    }

    @Test
    fun theGuestsEnvironmentIsNotPollutedByTheHosts() {
        // The same property from the other side, as the map a real caller would build: whatever is
        // in the Android process, only the guest's six variables arrive.
        val built = launcher.prepare(argv, env, cwd)
        for (host in listOf("ANDROID_ROOT", "ANDROID_DATA", "BOOTCLASSPATH", "EXTERNAL_STORAGE")) {
            assertFalse(
                "the host's $host reached the guest",
                built.environment().containsKey(host),
            )
        }
    }

    @Test
    fun prootNoSeccompIsPresentBecauseSomeKernelsNeedIt() {
        // It is in ProotCommand.env() for a reason: proot's seccomp filter is refused by some
        // Android 10+ kernels, and the failure is an EACCES with no mention of seccomp in it.
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    // ---- the rest of the start ------------------------------------------------------------------

    @Test
    fun theBuilderRunsInTheCwdItWasHanded() {
        // The process's own working directory, which for proot is the phone's and not the guest's;
        // the guest's is the `-w` in the vector.
        assertEquals(File(cwd), launcher.prepare(argv, env, cwd).directory())
    }

    @Test
    fun stderrGoesIntoTheSameStreamAsStdout() {
        // Boot's output is a report a user reads, and a guest that put its diagnostics on a second
        // stream would have them either interleaved wrongly or dropped. Document order is the only
        // order a report can be printed in.
        assertTrue(launcher.prepare(argv, env, cwd).redirectErrorStream())
    }

    // ---- the waiting, which is bounded and says so -------------------------------------------------

    @Test
    fun theTimeoutIsBoundedAndItsStatusIsOneNoGuestProduces() {
        // Long by default because the last thing Boot runs is a REPL, and short for a caller with a
        // one-shot. It is a constructor parameter so both are one argument.
        assertTrue(
            "the default must outlast a REPL a user is talking to",
            ProotProcessLauncher.DEFAULT_TIMEOUT_MS >= 5 * 60 * 1000L,
        )
        val quick = ProotProcessLauncher(sink, timeoutMs = 1L)
        assertEquals(124, ProotProcessLauncher.TIMED_OUT)
        assertEquals(125, ProotProcessLauncher.NOT_STARTED)
        // A one millisecond bound on a program that does not exist is the timeout path, not a
        // failure path: the process never starts, so the status is NOT_STARTED and not TIMED_OUT.
        assertEquals(
            ProotProcessLauncher.NOT_STARTED,
            quick.run(listOf("/nonexistent/proot-that-is-not-here"), env, cwd),
        )
        assertTrue(
            "a process that could not be started must say which file",
            String(sink.toByteArray()).contains("could not be started"),
        )
    }
}
