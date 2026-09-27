package com.omp.terminal.vm

import omp.vm.provision.GuestLaunch
import omp.vm.provision.GuestServer
import java.io.IOException
import java.io.OutputStream

/**
 * The line of the whole guest path that can only ever run on a phone.
 *
 * **It is [launch]'s `builder.start()` and it is on its own line for exactly that reason.** Every
 * other statement reachable from here — the argument vector, the environment, the port check, the
 * probe, the record, every sentence of every report — is plain JVM and is exercised by tests in
 * `:core`. This one statement starts a process, and a build machine has no Android kernel, no
 * `nativeLibraryDir` and no proot, so it has never run and cannot be run by anything in this
 * repository. `ProotProcessLauncherTest` pins the vector and the environment this class hands it;
 * nothing pins that the kernel honours them.
 *
 * ### Why a new class and not [ProotProcessLauncher]
 *
 * **[ProotProcessLauncher] returns an exit status, and a server never returns one.** It waits, and
 * when the bound passes it destroys the process — which for `apt-get` is the contract and for Apache
 * would be a kill. So this class reuses exactly the half that is already tested
 * ([ProotProcessLauncher.prepare]: the argv, the environment, the working directory, one merged
 * stream) and owns the half that is not, which is starting something and walking away from it.
 * That is why [launch] takes a `ProcessBuilder` rather than an argument vector: the tested part is
 * done before the untested part begins, and the untested part is one statement wide.
 *
 * ### What "started" means here, and what it does not
 *
 * **[started] is "a process exists" and not "a server is serving".** The two are different claims and
 * the whole of [omp.vm.provision.GuestWeb.WebProbe] exists to settle the second, because the origin
 * of the chat is decided on the second. A guest whose Apache was launched and then died on a
 * configuration error answers a connect with nothing, and the state that reaches the user says the
 * guest was started and is not answering, which is the truth and is not the same claim.
 *
 * **The settle is short and it is there for the report, not for the start.** [SETTLE_MS] is long
 * enough for a proot that is about to reject its own flags to say so, and short enough that a
 * working guest is not held up for it. What is still running afterwards is what "started" means; a
 * process that has already exited is reported with the guest's own last line, which is the only
 * evidence there will ever be about why.
 */
class ProotForegroundServer(

    /** Where the proot is, and the two variables its linker and its own build read. */
    private val helper: ProotHelper,

    /** Where the guest's merged output goes. The app's log, and nothing a user has to read. */
    private val out: OutputStream,
) : GuestServer {

    override fun start(argv: List<String>, env: Map<String, String>, cwd: String): GuestLaunch {
        val tail = TailStream(out)
        // The tested half. `helper.env` is the same two variables `NativeProot` adds for a one-shot
        // command, and the environment handed in is the guest's own, untouched.
        val builder = ProotProcessLauncher(tail).prepare(argv, helper.env(env), cwd)
        return launch(builder, tail)
    }

    private fun launch(builder: ProcessBuilder, tail: TailStream): GuestLaunch {
        val process = try {
            // >>> THE ONLY LINE OF THE GUEST START PATH THAT CAN RUN ONLY ON A PHONE. <<<
            // Nothing in this repository has ever executed it, and no test ever will: a build
            // machine has no `nativeLibraryDir` for the package manager to have filled, no proot
            // and no kernel policy to `ptrace` it under. Everything above it is data.
            builder.start()
        } catch (e: IOException) {
            // No proot in the exec directory, or no rootfs to point it at. The message is the file,
            // which is the whole of what can honestly be said about it from here — and it is the
            // same sentence `ProotProcessLauncher` writes for the same failure, so one cause has one
            // wording wherever a user meets it.
            return GuestLaunch(false, "proot: it could not be started: ${e.message}")
        }
        Thread({ drain(process, tail) }, "omp-guest-apache").apply {
            isDaemon = true
            start()
        }
        Thread.sleep(SETTLE_MS)
        if (stillRunning(process)) return GuestLaunch(true)
        // Exited inside the settle, so nothing is serving and the guest's own words are the reason.
        return GuestLaunch(
            started = false,
            said = tail.last()
                ?: "apache2 exited with status ${statusOf(process)} before it was asked anything",
        )
    }

    /**
     * Whether the process is still there.
     *
     * **`exitValue()` in a try and not [Process.isAlive]**, because the thrown
     * `IllegalThreadStateException` is the JVM's own answer to exactly this question and has been
     * since Java 1.0, and this class must not be the thing that decides which platform features a
     * phone running this app is allowed to have.
     */
    private fun stillRunning(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }

    private fun statusOf(process: Process): Int = try {
        process.exitValue()
    } catch (e: IllegalThreadStateException) {
        -1
    }

    /**
     * Copies the guest's output into [tail] for as long as it has any, on its own thread.
     *
     * **A thread, and not a read on this one**, because a server that fills its pipe blocks forever
     * and a drain that blocked would take the start down with it. It is a daemon, so a process this
     * class does not manage cannot keep a JVM — or an app process — alive by holding this open.
     */
    private fun drain(process: Process, tail: TailStream) {
        try {
            process.inputStream.use { stream ->
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) return
                    tail.write(chunk, 0, n)
                }
            }
        } catch (e: IOException) {
            // The process was destroyed or the pipe closed under us. There is nothing to report and
            // nobody left to report it to: the outcome of the start was already decided above.
        }
    }

    private companion object {
        /**
         * How long to wait before asking whether the process is still there: 300 ms.
         *
         * **For the report, not for the start.** What it buys is proot's own refusal — a bad flag, a
         * missing loader, a `ptrace` the policy refused — written into the stream before the process
         * ends, so a guest that was never going to run is reported with its reason rather than as a
         * silence. What it costs is 300 ms of a start, once, and it does not decide anything: a
         * process still running after it is "started" and a process that has not is "not started",
         * and [omp.vm.provision.GuestWeb.WebProbe] decides the only question that matters.
         */
        const val SETTLE_MS = 300L

        private const val CHUNK = 8 * 1024
    }
}

/**
 * A stream that keeps the last few kilobytes and forwards everything.
 *
 * **It exists because Apache runs for ever and the only thing wanted from it is its own first
 * words.** [ProotProcessLauncher] was given a stream and wrote everything into it for the lifetime
 * of a command that ends; here the process outlives the call, so an unbounded buffer in this app's
 * own heap is a slow leak and a bounded one is a report. [KEEP] is far more than a proot refusal
 * and far less than a log.
 *
 * **Synchronised, and not because the bytes are precious.** Two threads write into this: the drain
 * above, and [ProotProcessLauncher]'s own reader. A [StringBuilder] is not safe across them and the
 * failure would be a corrupted last line in a bug report — a small lie, and the kind this project
 * does not tell.
 */
private class TailStream(private val forward: OutputStream) : OutputStream() {

    private val kept = StringBuilder()
    private val lock = Any()

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            kept.append(String(b, off, len, Charsets.UTF_8))
            // Trim from the front once the whole buffer is worth keeping: dropping the head is what
            // makes this a tail, and doing it only when the cap is reached keeps the common case to
            // one comparison per write.
            if (kept.length > KEEP) kept.delete(0, kept.length - KEEP)
        }
        forward.write(b, off, len)
        forward.flush()
    }

    /** The guest's own last non-empty line, or null when it wrote none. */
    fun last(): String? = synchronized(lock) {
        kept.toString().lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    }

    private companion object {
        /** Two kilobytes: a proot refusal is one line and an Apache greeting is a handful. */
        const val KEEP = 2048
    }
}
