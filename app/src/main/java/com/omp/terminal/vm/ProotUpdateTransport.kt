package com.omp.terminal.vm

import omp.vm.guestapi.UpdateRun
import omp.vm.guestapi.UpdateTransport
import omp.vm.provision.ProotLauncher
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The boot's `omp update`, run in the guest, on a device.
 *
 * **This is the class [omp.vm.guestapi.AgentUpdate] was written to be handed and never was.** Its own
 * KDoc says so: no build of this app implements [UpdateTransport], `:app`'s [ProotProcessLauncher]
 * already has everything the step needs — a bound it destroys the process at, and a merged output
 * stream — and "the work of writing that one class is the app's, where the device is". This is it.
 *
 * ### Why a transport is the right shape and a direct call is not
 *
 * [omp.vm.guestapi.AgentUpdate] takes a **bound per call**, not one in its constructor, and says why:
 * the wait is a decision the step makes about a command it knows the shape of. [ProotProcessLauncher]
 * takes its wait in its constructor, because a process it starts once has one lifetime. So the two
 * are joined by constructing a launcher per call — two fields, no state, and the alternative, a
 * mutable timeout on a shared launcher, would be a second source of truth for one number that the
 * step's own test already pins.
 *
 * ### How "the launcher stopped it" is told apart from "the guest exited 124"
 *
 * **By the launcher's own sentence, not by its number.** [ProotProcessLauncher] writes
 * [STOPPED_MARKER] into the merged stream at the moment it destroys a process, and
 * [omp.vm.guestapi.UpdateRun.stopped] is a different claim from a non-zero status: it means there is
 * no real status at all, and [omp.vm.guestapi.UpdateOutcome.TIMED_OUT] is recorded instead of
 * [omp.vm.guestapi.UpdateOutcome.FAILED]. Reading `124` as "stopped" would mis-report a guest whose
 * `omp update` chose that status itself, and a guest that never ran at all would be reported as a
 * timeout rather than as a failure.
 *
 * **What is not covered, and is stated rather than hidden:** on the timeout path
 * [ProotProcessLauncher] returns while the reader thread may still be draining, so the tail of the
 * guest's diagnostics in [UpdateRun.output] can be a partial line. The marker this class looks for is
 * written before the launcher returns and is never truncated, so `stopped` itself is exact; only the
 * quoted last line of a stopped run can be short. Nothing downstream of that is load-bearing: the
 * outcome is a name, and the sentence that carries the guest's own words is the one a person reads.
 *
 * **Nothing in this repository has ever run this class.** It has been compiled and never called on a
 * device, an emulator or a test, for the reason every class under `vm/` shares and
 * [ProotProcessLauncher]'s KDoc lists in full.
 */
class ProotUpdateTransport(
    /**
     * How the launcher for one run is built, as a seam.
     *
     * **It exists so this class can be driven without a device, and the shipped answer is one line.**
     * [omp.vm.guestapi.AgentUpdate] hands a bound per call and [ProotProcessLauncher] takes one in
     * its constructor, so the join between them has to happen somewhere; putting it in a parameter
     * rather than inline means a test can watch the number that comes out of the join instead of
     * trusting that it is the number the step asked for. Nothing in this repository has ever run the
     * default.
     */
    private val launcherFor: (OutputStream, Long) -> ProotLauncher =
        { out, bound -> ProotProcessLauncher(out, timeoutMs = bound) },
) : UpdateTransport {

    override fun run(
        argv: List<String>,
        env: Map<String, String>,
        cwd: String,
        boundMs: Long,
    ): UpdateRun {
        val sink = ByteArrayOutputStream()
        val status = launcherFor(sink, boundMs).run(argv, env, cwd)
        val text = sink.toString(Charsets.UTF_8.name())
        return UpdateRun(status = status, output = text, stopped = text.contains(STOPPED_MARKER))
    }

    private companion object {
        /**
         * The sentence [ProotProcessLauncher] writes when it destroyed a process at its bound.
         *
         * **Matched on the words and not on the status number**, and that is the whole of this
         * class's one judgement. `ProotProcessLauncher.TIMED_OUT` is 124, which is also a status an
         * ordinary program can exit with, and reading the two together would call a guest's own exit
         * code a timeout this app invented.
         */
        const val STOPPED_MARKER = "proot: still running after"
    }
}
