package com.omp.terminal.vm

import omp.vm.provision.ProotLauncher
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The half of starting proot that needs a device, and nothing else.
 *
 * **Nothing in this repository has ever executed these bytes.** Not on a phone, not on an
 * emulator, not in a test. `omp.vm.provision` builds the argument vector, the environment and the
 * guest paths as pure data and `ProotCommandTest` pins them exactly; this class turns that data
 * into a `ProcessBuilder` and waits for a status. The gap between those two things is real and it
 * is the whole reason this file's KDoc is longer than its code.
 *
 * ### What is covered by a test, and what is not
 *
 * **Covered, by [ProotProcessLauncherTest] on the JVM:** that the `ProcessBuilder` this class
 * builds has exactly the argv it was handed, in order; exactly the environment it was handed, with
 * **nothing** inherited from the Android process; the working directory it was handed; and stderr
 * merged into stdout. Those are the four decisions a caller can be wrong about, and all four are
 * checkable without running a process, because building a `ProcessBuilder` starts nothing.
 *
 * **Not covered, and not checkable without a phone:** everything below. These are the questions
 * [omp.vm.provision.Provisioner] published, and this class does not pretend to have answered any
 * of them:
 *
 *  1. **Whether proot runs from the native library directory at all.** This app packages no
 *     `jniLibs` yet, so [omp.shell.PlatformServices.nativeLibraryDir] may be null and
 *     `ProotCommand.prootPath` may name a file that is not there. A `FileNotFoundException` here
 *     means the APK has not been given proot, not that the argument vector is wrong.
 *  2. **Whether the proot build accepts these flags.** `-r`, `-b`, `-w` and `-k` are its documented
 *     options, but a proot from a different source may spell one differently, and the first run on
 *     a device is where that shows.
 *  3. **Whether the netboot rootfs has the guest loader in it.** A glibc binary is started through
 *     `/lib/ld-linux-<arch>.so.1` *inside* the rootfs, so an image that unpacked without
 *     `libc6` cannot run the agent, and nothing above this class can tell.
 *  4. **Whether the binds work on this kernel.** proot is ptrace-based; some Android 10+ kernels
 *     need `PROOT_NO_SECCOMP=1`, which is in `ProotCommand.env()` for that reason and not as a
 *     flag a caller might forget.
 *  5. **Whether the OEM SELinux policy allows `ptrace` at all.**
 *
 * **None of those is given a fix here, because none of them can be observed from this code.** A
 * guess at a failure nobody has seen would be a comment in a launcher that reads like a diagnosis.
 * When one of them bites, it is reported the way the rest of this app reports things: the exit
 * status, and whatever the process wrote before it stopped.
 */
class ProotProcessLauncher(
    /**
     * Where the guest's merged stdout and stderr go.
     *
     * **One stream, because the caller already has one.** `omp.vm.provision.Boot` runs commands
     * whose output is a report for a user, and a guest that put its diagnostics on a second stream
     * would have them either interleaved wrongly or dropped. `ProcessBuilder.redirectErrorStream`
     * puts them in document order, which is the only order a report can be printed in.
     */
    private val out: OutputStream,
    /**
     * How long to wait, in milliseconds, before the process is destroyed and the call gives up.
     *
     * **Bounded because a wedged process must not hold a thread for ever, and long by default
     * because the last thing `Boot` runs is bare `omp` inside the guest — a REPL that runs until
     * the user stops it.** A caller running a one-shot such as `omp update` should pass something
     * short; the constructor is public so that a caller with a different command can be.
     *
     * When the bound passes the process is destroyed forcibly and [TIMED_OUT] is returned, which
     * is a status no `omp` exits with, so a caller can tell a timeout from a failure.
     */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ProotLauncher {

    override fun run(argv: List<String>, env: Map<String, String>, cwd: String): Int =
        execute(prepare(argv, env, cwd))

    /**
     * The process, built and not started.
     *
     * **The Android environment is cleared, not merged.** `ProotCommand.env()` is documented as the
     * guest's *whole* environment and not a delta, and a merge would hand the guest whatever this
     * process happened to have: a `LD_LIBRARY_PATH` pointing at Android's own libraries would make
     * a glibc binary fail in a way that looks like a missing `libc6`, and `ANDROID_ROOT` and
     * `BOOTCLASSPATH` mean nothing to anything in a Debian and are a way to waste an afternoon.
     * This is also the one part of the start-up that a JVM test can read, and it reads as an empty
     * map before [env] is put in — which is the state a test asserts.
     */
    fun prepare(argv: List<String>, env: Map<String, String>, cwd: String): ProcessBuilder {
        require(argv.isNotEmpty()) { "proot: an empty argument vector names no program to run" }
        val builder = ProcessBuilder(argv)
        builder.directory(File(cwd))
        builder.redirectErrorStream(true)
        val environment = builder.environment()
        environment.clear()
        environment.putAll(env)
        return builder
    }

    /**
     * Starts it, copies what it says into the stream it was given, and waits for a status.
     *
     * **The output is drained on its own thread, because a process that fills its pipe blocks
     * forever and a `waitFor` that ignores that is a hang with a number attached to it.** The
     * reader is a daemon so a process this class gives up on cannot keep a JVM alive, and the
     * reader's own interruption is how the wait ends.
     */
    private fun execute(builder: ProcessBuilder): Int {
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            // No proot in the native library directory, or no rootfs to point it at. The message
            // is the file, which is the whole of what this class can honestly say about it.
            out.write(("proot: it could not be started: ${e.message}\n").toByteArray())
            out.flush()
            return NOT_STARTED
        }
        val drain = Thread({ pump(process) }, "omp-proot-output")
        drain.isDaemon = true
        drain.start()
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return INTERRUPTED
        }
        if (!finished) {
            process.destroyForcibly()
            out.write(("proot: still running after ${timeoutMs}ms and was stopped\n").toByteArray())
            out.flush()
            return TIMED_OUT
        }
        // The reader is finished as soon as the process is, but joining it is what makes the
        // output land before this returns: without the join, the caller can print a report that
        // does not yet contain the lines the guest just wrote.
        drain.join(JOIN_MS)
        return process.exitValue()
    }

    private fun pump(process: Process) {
        try {
            process.inputStream.use { stream ->
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) return
                    out.write(chunk, 0, n)
                    // Every write, because the caller is a screen: a line that arrives at the end
                    // of a conversation is a line the user never saw being thought about.
                    out.flush()
                }
            }
        } catch (e: java.io.IOException) {
            // The process was destroyed under us, which is what a timeout does. There is nothing
            // to report and nobody left to report it to.
        }
    }

    companion object {
        /**
         * Ten minutes, the default wait.
         *
         * Long enough for a full `omp update` against a slow link inside an emulated root, and long
         * enough that a REPL a user is talking to is not killed under them. A one-shot command
         * should pass something shorter.
         */
        const val DEFAULT_TIMEOUT_MS = 10L * 60 * 1000

        /** How long the output reader is given to finish after the process has. */
        const val JOIN_MS = 2_000L

        private const val CHUNK = 8 * 1024

        /**
         * Returned when the process was still running at the bound. A status no guest command
         * produces, so "stopped" is never mistaken for "failed".
         */
        const val TIMED_OUT = 124

        /** Returned when the process could not be started at all; see the KDoc on why. */
        const val NOT_STARTED = 125

        /** Returned when this thread was interrupted while waiting. */
        const val INTERRUPTED = 130
    }
}
