package omp.vm.launcher

import omp.agent.Agent
import omp.shell.PlatformServices
import omp.shell.exec.ExecContext
import omp.shell.exec.printUsage
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.vm.provision.Abi
import omp.vm.provision.ArtifactManifest
import omp.vm.provision.Phase
import omp.vm.provision.Progress
import omp.vm.provision.ProvisionOutcome
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionStatusHolder
import omp.vm.provision.Provisioner
import omp.vm.provision.Transport

/**
 * `omp provision`: the one command in this project that spends a third of a gigabyte of somebody's
 * mobile data, and everything it does to make sure that happens on purpose.
 *
 * ### The rule this class exists to keep
 *
 * **No byte moves without an answer given in this run.** Not a flag remembered from last time, not
 * an app that decided on its own at start-up, not a background job that found a terminal attached
 * because it asked the wrong question of [omp.shell.InputChannel.owns]. The answer is one keystroke
 * on the terminal this session owns, and every path that cannot have one — a pipe, a script, a `&`
 * job, a `vm exec` line — stops with the cost printed and the refusal printed, which is the whole
 * of what a caller who cannot answer deserves. `--yes` is the exception, it is *this run's own
 * argument*, and it says in the output that it skipped the question, so an automated run can never
 * be read afterwards as one a person looked at.
 *
 * ### The cost is the manifest's sentence and not a rounding of it
 *
 * [ArtifactManifest.describe] is called and printed whole: the per-ABI wire bytes, the LAMP share
 * that arrives through `apt` inside the Debian afterwards, and the room the whole thing occupies on
 * the device. It is not re-derived here, because the number a user agrees to and the number they
 * pay are the same number and the only way that stays true is one place computing it. `--yes` does
 * not skip it either: a flag that answers the question does not get to hide the question.
 *
 * ### Why it refuses inside the namespace
 *
 * The guest is a **host-side** thing. proot executes from the directory the package manager
 * extracted this APK's own native libraries into, and the rootfs it emulates is a directory in the
 * app's private storage — a namespace is a mount table with no path to any of it, and `/mnt/omp` is
 * the one bind that exists, which is conversations and not a Debian. So a run inside the VM would
 * be a run against a filesystem that does not contain the thing being provisioned, and the refusal
 * is one line saying where it is done from: no flag is named and nothing is promised. The same gate
 * is a conversation on the phone, because `omp` inside one is the coding agent and an agent is not
 * what fetches a Debian.
 *
 * ### What a caller gets back
 *
 * **The same five outcomes for every path, and the report's own lines.** Refused in the namespace,
 * refused on the manifest's gap, declined at the question, cancelled at the question, cancelled
 * mid-download, failed, provisioned, already provisioned: every one of them is a
 * [omp.vm.provision.ProvisionOutcome] named on a line of its own, the report lines in the order the
 * report collected them, and the same exit-status mapping in one place. Nothing here has to be read
 * out of prose, and the last line of the command always says which of the two agents is answering —
 * the real `omp` in the guest, or the Kotlin agent that is in this app either way.
 *
 * @param transport the one seam a download goes through, so a test drives the real [Provisioner]
 *   over a body it wrote rather than over a mock of the layer. The shipped one is
 *   [omp.vm.provision.HttpTransport].
 * @param manifest where the table of per-ABI artifacts comes from, and the same reason: the shipped
 *   table names 293,246,344 bytes of artifacts whose sha256 this build pins, so a test that wanted
 *   to run a whole provision through a shell would have to hold 293 MB of body it cannot make match
 *   those digests. Handed a small table this class walks the identical path — the same cost
 *   sentence, the same question, the same progress, the same outcomes — and the tests that are about
 *   the *real* table pass the shipped one and assert the measured figures are in the output.
 */
class ProvisionCommand(
    private val transport: Transport = omp.vm.provision.HttpTransport(),
    private val manifest: (Abi) -> ArtifactManifest = ArtifactManifest::of,
) {

    /**
     * The whole command, in the order a person experiences it.
     *
     * Every exit from this method goes through [finish], so there is exactly one place that prints
     * the outcome, the report and the answer about the agent — and no outcome can be reached by a
     * path that skipped it.
     */
    fun run(ctx: ExecContext, operands: List<String>): Int {
        val yes = flags(ctx, operands) ?: return ExecContext.EXIT_USAGE
        val paths = ProvisionPaths.inAppStorage(ctx.services)
        // The phone's own filesystem, and not the session's: the question this command answers is
        // "is the real Debian on this device", and inside the namespace the session's filesystem is
        // a mount table that cannot see it. Reading the device directly is what lets the refusal
        // below still say which agent is answering.
        val vfs = RealVfs()

        if (Containers.locate(ctx).kernel != null) return refuse(ctx, paths, vfs, IN_NAMESPACE)
        if (OmpCommand.inConversation(ctx)) return refuse(ctx, paths, vfs, IN_CONVERSATION)
        val abi = Abi.detect(System.getProperty("os.arch"), abilistOf(ctx.services))
            ?: return refuse(ctx, paths, vfs, UNKNOWN_ABI)
        val table = manifest(abi)
        // The manifest's own gap, in its own words: Debian publishes no netboot image for i386 at
        // all, and `omp` publishes no 32-bit Linux agent, so both of those devices are told what
        // upstream is missing before a question is asked about 39 MB of a Debian that could never
        // run the agent on this one.
        table.gap?.let { return refuse(ctx, paths, vfs, it) }

        // Asked here and not left to the Provisioner, because the Provisioner only finds out after
        // the question has been answered. A user must never be asked to agree to a download that is
        // already on the phone.
        if (paths.state(vfs).realAgentInstalled) {
            return finish(
                ctx, paths, vfs, ProvisionOutcome.ALREADY_PROVISIONED,
                listOf(
                    "$USAGE: ${abi.abiName} is already provisioned: the Debian is at " +
                        "${paths.rootfsDir} and the agent at ${paths.agentBinary}. Nothing was " +
                        "downloaded and nothing was unpacked.",
                ),
            )
        }

        for (line in cost(table, paths, vfs)) ctx.outLine(line)
        ctx.flush()
        if (!yes && !Agent.mayPrompt(ctx)) {
            return finish(
                ctx, paths, vfs, ProvisionOutcome.REFUSED,
                listOf(
                    "$USAGE: stdin is not the terminal this command owns, so nothing is asked and " +
                        "nothing is downloaded",
                    "$USAGE: '$YES' is this run's own answer, for a run that means it",
                ),
            )
        }
        if (yes) {
            // Said out loud, every time, because the alternative is a provisioning run in a log with
            // no sign that nobody was asked — which is exactly the thing this whole command is
            // built so that it can never be.
            ctx.outLine(
                "$USAGE: $YES: the question was not asked. This run started the download without a " +
                    "keystroke from you, from a terminal or not, and the cost above is what it is.",
            )
        } else {
            ctx.outLine()
            ctx.outLine("$USAGE: type y to download that now, or anything else to stop:")
            ctx.flush()
            val answer = askOnTerminal(ctx)
            if (answer is TerminalAnswer.Cancelled) {
                return finish(
                    ctx, paths, vfs, ProvisionOutcome.CANCELLED,
                    listOf(
                        "$USAGE: the question was cancelled; nothing was downloaded, nothing was " +
                            "created and no directory was made",
                    ),
                )
            }
            // **Only a `y` goes on.** An empty Enter, a `yes`, a space and a number are all
            // something else, and this is the one keystroke in this app where nearly right is
            // wrong: the cost above is three hundred megabytes of somebody's data, and no keystroke
            // other than that one is the agreement to spend it. The comparison ignores case,
            // because the key does not and a capital `Y` is the same letter on a phone keyboard.
            val typed = (answer as? TerminalAnswer.Typed)?.text
            if (typed?.equals("y", ignoreCase = true) != true) {
                return finish(
                    ctx, paths, vfs, ProvisionOutcome.REFUSED,
                    listOf(
                        "$USAGE: stopped at the question; nothing was downloaded, nothing was " +
                            "created and no directory was made",
                    ),
                )
            }
        }

        val meter = Meter(ctx, paths, vfs, abi)
        ProvisionStatusHolder.begin(abi.abiName)
        val report = try {
            Provisioner(paths, vfs, transport, meter::event, { ctx.cancelled.get() }).provision(table)
        } finally {
            meter.flush()
        }
        return finish(ctx, paths, vfs, report.outcome, report.lines)
    }

    // ---- the cost, and the refusals that come before a question -------------------------------------

    /**
     * The cost, in [ArtifactManifest]'s own words and its own figures.
     *
     * **Two sentences about the money and one about the disk, and no fourth.** [describe] is the
     * sentence with the wire bytes, the LAMP share and the room on the device;
     * [omp.vm.provision.GuestInstall.costLine] is the one that says where the LAMP share comes from
     * and that it is not part of the bytes above. The third line is where the bytes land, because a
     * cost with no path next to it is a cost a user cannot go and look at afterwards — and the
     * download directory is named in it, which is what makes a cancellation resumable rather than a
     * promise.
     */
    private fun cost(manifest: ArtifactManifest, paths: ProvisionPaths, vfs: Vfs): List<String> = listOf(
        "$USAGE: ${manifest.describe()}",
        "$USAGE: ${manifest.guest.costLine(installed = paths.state(vfs).guestInstalled)}",
        "$USAGE: into ${paths.installDir}, with a download in progress kept in ${paths.downloadDir} " +
            "so a cancelled run continues instead of starting again",
    )

    /** A refusal before anything was asked, which is also the shape of every early exit. */
    private fun refuse(ctx: ExecContext, paths: ProvisionPaths, vfs: Vfs, reason: String): Int =
        finish(ctx, paths, vfs, ProvisionOutcome.REFUSED, listOf("$USAGE: $reason"))

    /**
     * The tail every run ends with, in this order and no other: the report's own lines, the outcome
     * as a word, what a cancellation left on the phone, and which of the two agents is answering.
     *
     * **The outcome is its own line and is the machine-readable one.** A caller that wants to know
     * what happened reads [omp.vm.provision.ProvisionOutcome] and not the prose around it, and the
     * five ways out of this command all come through here, so there is no path that prints a report
     * without saying which kind it is.
     *
     * **The exit status is decided once, by [statusOf].** A run that provisioned and a device that
     * already had it are `0`; a cancellation is `^C`; everything else is `1`, which is what `omp rm`
     * answers for a refusal and therefore what `$?` is here.
     */
    private fun finish(
        ctx: ExecContext,
        paths: ProvisionPaths,
        vfs: Vfs,
        outcome: ProvisionOutcome,
        lines: List<String>,
    ): Int {
        ProvisionStatusHolder.finish(outcome)
        for (line in lines) ctx.outLine(line)
        ctx.outLine("$USAGE: outcome: $outcome")
        if (outcome == ProvisionOutcome.CANCELLED) ctx.outLine("$USAGE: ${kept(paths, vfs)}")
        ctx.outLine(answering(paths, vfs))
        ctx.flush()
        return statusOf(outcome)
    }

    /**
     * What a cancelled run left, said as a fact about the disk rather than as a reassurance.
     *
     * **"cancelled" and "lost" are different words and a user will read the difference**, so this
     * asks the filesystem which of them it was: a `.part` file that is still there is what the next
     * run will send a `Range` from, and the state file beside it is the record of how much that is.
     * A download cancelled before its first byte has neither, and says so about *that download* —
     * not about the run, which may already have unpacked a Debian that stays exactly where it is.
     */
    private fun kept(paths: ProvisionPaths, vfs: Vfs): String {
        val artifact = ProvisionStatusHolder.current.artifact ?: return NOTHING_KEPT
        val partial = paths.partial(artifact)
        val size = try {
            vfs.stat(partial).size
        } catch (e: FsException) {
            return NOTHING_KEPT
        }
        return "what arrived is kept: $partial (${bytes(size)}) with the record at " +
            "${paths.stateFile}. Nothing of this download was thrown away: the next '$USAGE' asks " +
            "the server for the rest from that offset and keeps going."
    }

    /**
     * Which of the two agents is answering now, in one line, out loud, on every single outcome.
     *
     * **The numbers are the ones the code already has** — [Agent.VERSION] for the Kotlin agent and
     * [ArtifactManifest.AGENT_RELEASE] for the guest's — so the sentence cannot name a version that
     * is not in the build. The choice is made by the disk, through
     * [omp.vm.provision.ProvisionState.realAgentInstalled]: the agent binary on its own is not an
     * answer, because a glibc binary with no Debian to find a loader in runs nothing, which is why
     * that field is defined that way and not as `agentInstalled`.
     */
    private fun answering(paths: ProvisionPaths, vfs: Vfs): String =
        if (paths.state(vfs).realAgentInstalled) {
            "answering now: the real omp ${ArtifactManifest.AGENT_RELEASE} in the guest — the " +
                "downloaded binary, run under proot inside the Debian at ${paths.rootfsDir} — and " +
                "the Kotlin agent ${Agent.VERSION} in this app still answers every conversation here."
        } else {
            "answering now: the Kotlin agent ${Agent.VERSION} that is in this build — no download, " +
                "and it reads, writes, edits, lists and searches inside one conversation folder."
        }

    // ---- the progress a download makes, printed as it happens ---------------------------------------

    /**
     * The live progress: a line per step, a byte count per line, and one line per artifact when it
     * is finished.
     *
     * **Bytes and never a percentage.** A percentage needs a denominator, and the only denominator
     * that is not a guess here is the size the manifest pins — so the line prints the received count
     * and the expected count and lets the reader do it, and a figure this class cannot derive from
     * two integers it was handed is not one it will print.
     *
     * **Throttled by bytes and not by time**, every [STEP] of them, with the first event and every
     * phase change always printed. A time-based throttle is right for a bar being redrawn and wrong
     * for a log: it makes the number of lines depend on how fast the radio was, so the same download
     * prints a different history on two devices. Eight mebibytes is 28 lines for the arm64 agent and
     * one for a 300 KB test body, which is the right number in both cases.
     *
     * **A resume says so before the first byte of the new range arrives**, and it says it by asking
     * the `.part` file how long it is rather than by reading a flag: the length of that file *is*
     * the offset, which is the property the resumable download is built on.
     */
    private inner class Meter(
        private val ctx: ExecContext,
        private val paths: ProvisionPaths,
        private val vfs: Vfs,
        private val abi: Abi,
    ) {
        private var artifact: String? = null
        private var announced = 0L
        private var last = 0L

        fun event(progress: Progress) {
            ProvisionStatusHolder.progress(abi.abiName, progress)
            when (progress.phase) {
                Phase.DOWNLOADING -> downloading(progress)
                Phase.VERIFYING -> {
                    if (artifact == progress.artifact) {
                        // The download is over, and the phase change is the only event that says so.
                        // [last] is the count of bytes that actually arrived for it.
                        line(
                            "$USAGE: ${progress.artifact}: ${bytes(last)} of " +
                                "${bytes(progress.totalBytes)} — downloaded",
                        )
                    }
                    line("$USAGE: ${progress.artifact}: verifying")
                    forget()
                }
                Phase.UNPACKING -> {
                    line("$USAGE: ${progress.artifact}: unpacking into ${paths.rootfsDir}")
                    forget()
                }
                Phase.INSTALLED -> {
                    line("$USAGE: ${progress.artifact}: installing into ${paths.agentBinary}")
                    forget()
                }
            }
            ctx.flush()
        }

        fun flush() {
            ctx.flush()
        }

        private fun downloading(progress: Progress) {
            if (artifact != progress.artifact) {
                artifact = progress.artifact
                announced = 0L
                val had = sizeOf(paths.partial(progress.artifact))
                // A `.part` that is already the whole artifact is not a resume: the Provisioner
                // verifies it without asking the server for anything, and saying "resuming" here
                // would be claiming a request that is never made.
                if (had in 1 until progress.totalBytes) {
                    line(
                        "$USAGE: ${progress.artifact}: resuming at ${bytes(had)} of " +
                            bytes(progress.totalBytes),
                    )
                }
                // The first event carries a received count of zero whatever happened, because it
                // opens the phase rather than reporting it — so the count a reader sees first is
                // what is already on the device, which after a resume is not nothing.
                val at = maxOf(progress.receivedBytes, had)
                last = at
                line("$USAGE: ${progress.artifact}: ${bytes(at)} of ${bytes(progress.totalBytes)}")
                return
            }
            last = progress.receivedBytes
            if (last - announced < STEP) return
            announced = last
            line("$USAGE: ${progress.artifact}: ${bytes(last)} of ${bytes(progress.totalBytes)}")
        }

        private fun forget() {
            artifact = null
            announced = 0L
            last = 0L
        }

        private fun line(text: String) {
            ctx.outLine(text)
        }

        private fun sizeOf(path: String): Long = try {
            vfs.stat(path).size
        } catch (e: FsException) {
            0L
        }
    }

    // ---- what a device is, and the options this verb has -----------------------------------------------

    /**
     * The ABI list Android ordered this process, recovered from the one property the platform
     * publishes it in.
     *
     * `ro.product.cpu.abilist` is `Build.SUPPORTED_ABIS` joined with commas, in the same order, and
     * [Abi.detect] wants that order and not `os.arch`: a translated x86_64 running arm64 code
     * executes arm64 libraries, and the wrong end of that disagreement is 55 MB of the wrong Debian.
     */
    private fun abilistOf(services: PlatformServices): List<String> =
        services.buildProperties()[ABILIST]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * The one option this verb has, and the refusal of everything else.
     *
     * **`--yes` is the only one, and an argument this shell did not recognise is a usage error
     * rather than something quietly ignored**: `--resume`, `--force` and a bare path would all read
     * as though they meant something, and the point of this command is that it does not take an
     * argument that changes what is downloaded or where it goes.
     */
    private fun flags(ctx: ExecContext, operands: List<String>): Boolean? {
        val unknown = operands.firstOrNull { it != YES }
        if (unknown != null) {
            ctx.errLine(
                if (unknown.startsWith("-")) "$USAGE: unknown option '$unknown'"
                else "$USAGE: it takes no arguments",
            )
            printUsage(ctx.stderr, "$USAGE [$YES]")
            return null
        }
        return operands.contains(YES)
    }

    companion object {
        private const val USAGE = "omp provision"
        private const val YES = "--yes"
        private const val ABILIST = "ro.product.cpu.abilist"

        /** How many bytes go by between two progress lines. See [Meter]. */
        private const val STEP = 8L * 1024 * 1024

        private const val IN_NAMESPACE =
            "this session is inside the VM, and the guest is a host-side thing: proot runs from " +
                "the directory this APK's own native libraries were extracted into, and the Debian " +
                "it emulates is a directory in the app's own storage. Neither of those is anywhere " +
                "in the namespace. The guest is provisioned from the phone's own shell."

        private const val IN_CONVERSATION =
            "inside a conversation 'omp' is the coding agent, and a coding agent does not fetch a " +
                "Debian. The guest is provisioned from the phone's own shell."

        private const val UNKNOWN_ABI =
            "this device reports an architecture this app does not know, so there is no Debian to " +
                "download for it and no agent to run here."

        private const val NOTHING_KEPT =
            "this download never started, so there is nothing half-finished to keep. What is " +
                "already on the phone is untouched, and the next '$USAGE' picks up from there."
    }
}

/** The exit status for an outcome, in one place so that no path invents one of its own. */
private fun statusOf(outcome: ProvisionOutcome): Int = when (outcome) {
    ProvisionOutcome.PROVISIONED, ProvisionOutcome.ALREADY_PROVISIONED -> ExecContext.EXIT_OK
    ProvisionOutcome.CANCELLED -> ExecContext.EXIT_INTERRUPTED
    ProvisionOutcome.REFUSED, ProvisionOutcome.FAILED -> ExecContext.EXIT_GENERAL_ERROR
}

/** A byte count as a person reads it, spelled in exactly one place: the manifest's own. */
private fun bytes(count: Long): String = ArtifactManifest.humanBytes(count)
