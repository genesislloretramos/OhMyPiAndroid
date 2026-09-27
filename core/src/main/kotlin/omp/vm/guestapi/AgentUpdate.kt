package omp.vm.guestapi

import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.provision.ProotCommand
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the boot's `omp update` did, as a closed set of names and a fixed number for each of them.
 *
 * **Every one of these is a line a user reads, which is why they are names and not sentences.**
 * [omp.vm.provision.ProvisionOutcome] is the model and this is the same shape: a value a caller
 * branches on, a line a person reads, and [exitStatus] as the one place a number is attached to a
 * name. A boot that says nothing about a failed update is indistinguishable from a boot where the
 * update did not exist, so there is no outcome here that is "nothing happened" — the two ways of
 * not having an update, [NOT_PROVISIONED] and [NO_AGENT], say which of them it was.
 *
 * | outcome | what happened | [exitStatus] |
 * |---|---|---|
 * | [NOT_PROVISIONED] | there is no unpacked Debian, so nothing was started | 0 |
 * | [NO_AGENT] | the Debian is here and this ABI's agent is not, and never will be | 0 |
 * | [UPDATED] | it ran, exited 0, and did not say it was already current | 0 |
 * | [ALREADY_CURRENT] | it ran, exited 0, and said it was already current | 0 |
 * | [FAILED] | it ran and exited non-zero; the guest's own words are in the record | 1 |
 * | [TIMED_OUT] | it was still running at [AgentUpdate.BOUND_MS] and was stopped | 124 |
 *
 * **The two zeros are not a shrug.** "The update did not run" is not a failure of the update and
 * must not read as one, so a caller that turns this into a notification shows nothing to apologise
 * for. **124 is the number [TIMED_OUT] carries** and it is the one a shell already uses for a
 * command that had to be killed: a status no `omp` exits with, so "we stopped it" can never be
 * read as "it failed", and the two are told apart in the report by the outcome's own name.
 */
enum class UpdateOutcome(val exitStatus: Int) {

    /** There is no Debian on this device, so there is no guest to update and nothing was started. */
    NOT_PROVISIONED(0),

    /** The Debian is here and this ABI has no agent at all, so there is nothing to update. */
    NO_AGENT(0),

    /** It ran, exited 0, and did not say it was already current — so it installed something. */
    UPDATED(0),

    /** It ran, exited 0, and printed "Already up to date" (measured — see [AgentCommand.UPDATE_HELP]). */
    ALREADY_CURRENT(0),

    /** It ran and exited non-zero. The guest's own last line is the reason and is recorded. */
    FAILED(1),

    /** It was still running at the bound and the launcher stopped it. What it had done is not known. */
    TIMED_OUT(124),
}

/**
 * What one run of the update in the guest came back with: the status, everything the guest wrote,
 * and whether the launcher gave up on it.
 *
 * **[output] is the guest's stdout and stderr merged**, because that is the only stream a child of
 * the app can be given here: `com.omp.terminal.vm.ProotProcessLauncher.prepare` calls
 * `redirectErrorStream(true)`, and its own KDoc gives the reason — a guest that put its diagnostics
 * on a second stream would have them either interleaved wrongly or dropped.
 *
 * **What the merge costs, measured.** On this machine `omp update` writes "Current version: 18.3.5"
 * to **stdout** and "Failed to check for updates: …" to **stderr**, so a merged stream on a device
 * carries both in the order the guest wrote them, and this class reads both out of one string.
 * **That the order on a device is identical is not established**, and nothing here depends on it:
 * the two facts this class reads are found by what they say, not by where they arrived.
 */
data class UpdateRun(
    /** The status the guest exited with. Meaningless when [stopped] is true. */
    val status: Int,
    val output: String,
    /** True when the launcher stopped the process at its bound and there is no real status. */
    val stopped: Boolean = false,
)

/**
 * How the boot gets the update out of the guest and back, which is the one seam this class does not
 * implement — the same seam [omp.vm.provision.ProotLauncher] is, and for the same reason.
 *
 * **It differs from [omp.vm.provision.ProotLauncher] in two ways and both of them are needed.**
 * A status alone cannot answer the two questions this step exists to answer — which version the
 * guest has, and what the guest said when it failed — and both are on a stream, not in a number. So
 * this seam also returns [UpdateRun.output], and it takes a [boundMs] rather than carrying one,
 * because the wait is a decision *this* class makes about a command it knows the shape of, and a
 * ten-minute default that suits a REPL a person is talking to is the wrong bound for a step that
 * runs while the app is starting.
 *
 * **Nothing in this repository implements it, and that is the established position, not a gap in
 * this file.** No build of this app has ever run proot on any device (see
 * [omp.vm.provision.ProotCommand] for the four questions that are still unmeasured), and
 * `omp.vm.provision.Boot` itself has no caller yet either. `:app`'s
 * `ProotProcessLauncher` already has everything this needs — a bound it destroys the process at, and
 * a merged output stream — and the work of writing that one class is the app's, where the device
 * is. A test in [omp.vm.guestapi.AgentUpdateTest] answers every case here with a fake, so the
 * decisions below are all checkable on a JVM.
 */
fun interface UpdateTransport {
    /**
     * Runs [argv] with [env] and [cwd], waits at most [boundMs], and returns what came back.
     *
     * @param argv the vector from [omp.vm.provision.ProotCommand.argv], `argv[0]` included.
     * @param env the guest's whole environment, from [omp.vm.provision.ProotCommand.env].
     * @param cwd the process's own working directory, which for proot is the phone's and not the
     *   guest's; the guest's is the `-w` in [argv].
     * @param boundMs the wait, in milliseconds, and it is this caller's: a transport that waits for
     *   ever whatever it is told has made a bounded step unbounded.
     */
    fun run(argv: List<String>, env: Map<String, String>, cwd: String, boundMs: Long): UpdateRun
}

/**
 * What one run did, everything `omp doctor` prints about it, and the record that survived it.
 *
 * [line] is the sentence a boot prints, and it is built here rather than by the caller so that the
 * boot report and the record on the disk cannot say different things about the same run.
 */
data class UpdateReport(
    val outcome: UpdateOutcome,
    /** The version the guest printed as its own, or null when it printed no such line. */
    val version: String?,
    /** The version the run before this one reported, when this one reported a different one. */
    val was: String?,
    /** The status the guest exited with, or null when nothing ran or nothing came back. */
    val status: Int?,
    /** The wait this run was given, whether it used it or not. */
    val boundMs: Long,
    /** The guest's own last line, cut to [AgentUpdate.MAX_SAID] characters, or null when it wrote none. */
    val said: String?,
    /** Whether the record reached the disk. A refusal to write it is said in [line], never swallowed. */
    val recorded: Boolean,
    /** The one line a boot prints. Its first word is always [outcome]'s name. */
    val line: String,
)

/**
 * `omp update` in the guest, at every start of the app, unattended, bounded, and recorded.
 *
 * ### What this class is for
 *
 * **It is the opposite of [omp.vm.provision.Provisioner] in every way that matters, and the
 * difference is deliberate.** Provision is 350,458,048 bytes of somebody's mobile data, so it asks
 * a person first, every time. This is a step that runs while the app is starting, with nobody
 * typing: the WebView is about to load and the guest is not answering yet, so it cannot ask. What
 * it does instead is **bound the wait, keep the failure off the guest's back, and leave a record
 * `omp doctor` reads afterwards** — the rule this project already follows about everything that
 * moves without a keystroke, and the reason a guest that could not be updated is a line in a report
 * rather than a silence.
 *
 * **It runs inside the guest, on the guest's own filesystem, against the guest's own
 * `/usr/local/bin/omp`** — which is the reason it exists at all and the reason
 * [omp.vm.launcher.OmpCommand]'s refusal of `omp provision` inside the namespace does not apply
 * here. That refusal is about proot executing from a directory on the **host**: the rootfs is a
 * host directory, which a mount namespace cannot see. This runs with the same proot, in the same
 * namespace, over the same bind, and what it writes is inside that namespace. The two commands sit
 * in different places for different reasons, and a boot that copied provision's sentence into this
 * one would be refusing the only thing this step is for.
 *
 * ### The command, and where every word of it came from
 *
 * ```
 * /usr/local/bin/omp update
 * ```
 *
 * **No flags, and that is a decision made from a help text rather than a preference.**
 * [AgentCommand.UPDATE_HELP] is `omp update --help` as it was read on this machine from
 * `omp/18.3.5` at `/home/genesis/.local/bin/omp`, and it is what `-c, --check`, `-l, --plugins`,
 * `--canary` and `--stable` would have to be taken from. None of them is what a boot wants: the
 * user wrote `omp update && omp`, so the agent is to *become* current, not to be asked whether it
 * is. **A flag this build cannot quote from a help text is a flag that silently does nothing on a
 * phone**, and [omp.vm.guestapi.GuestApiContractTest]'s discipline is what holds that line — so the
 * vector is exactly one token and the test pins it.
 *
 * ### What was measured, and how, on `omp/18.3.5`
 *
 * | run | stdout | stderr | status | wall clock |
 * |---|---|---|---|---|
 * | `omp update` on this machine | `Current version: 18.3.5` then `✔ Already up to date` | *(empty)* | 0 | 0.48–0.58 s |
 * | `omp update` again, immediately | identical | *(empty)* | 0 | 0.48 s |
 * | `omp update` with no network (`unshare -rn`) | `Current version: 18.3.5` | `Failed to check for updates: TypeError: Unable to connect. Is the computer able to access the url?` | **1** | 0.23 s |
 * | `omp update < /dev/null` | as the first row | *(empty)* | 0 | 0.49 s |
 *
 * Four things follow from that table and the class is built on all four:
 *
 * 1. **It asks nobody.** Measured with standard input closed, which is what a child of the app gets
 *    and not a terminal: the same two lines and the same status, with no keypress and no prompt.
 *    That is the whole of "unattended" for this step, and it is a measurement rather than an
 *    assumption about a REPL.
 * 2. **`Current version:` is printed before anything else, on both paths.** It is on stdout in the
 *    success case and in the no-network case, which is why [versionOf] can answer even when the
 *    update failed, and why [UpdateReport.version] is a fact rather than a hope.
 * 3. **A failure is exit status 1 with the reason on stderr** — and because a device merges the two
 *    streams, [UpdateRun.output] has the reason in it. The status a *usage error* produces is also
 *    1 (`error: Unknown option '--nope'…`, measured), which is worth knowing: it is not the 2 that
 *    the main command uses for a bad flag, as [AgentCommand] records, so a 1 from here is "the
 *    command did not succeed" and never "the command was wrong".
 * 4. **Running it twice in a row is safe, and it leaves nothing behind.** Two runs back to back
 *    printed the same two lines and exited 0 both times, and `pgrep -f /home/genesis/.local/bin/omp`
 *    gave the same three pids before and after, all of them started hours or a day earlier by
 *    something else. So a boot that runs it on every start is not a boot that accumulates
 *    processes, which was a real question about a step with no caller to ask.
 *
 * ### What was **not** established, and is not guessed anywhere below
 *
 * - **What a successful *install* prints.** The machine was already current, so the "✔ Already up to
 *   date" path is the only success this build has seen; a line naming a newly installed version has
 *   never been observed and nothing here reads one. That is why [UPDATED] takes its version from
 *   what the guest reported *before* it ran and why the record's `was` line exists: the honest
 *   before-and-after is assembled from two boots' own reports rather than invented from one.
 * - **How long a real install takes.** A 224 MB binary over a phone radio is minutes, not the half
 *   second the check takes, and [BOUND_MS] is chosen knowing it does not cover one.
 * - **Whether an update interrupted at [BOUND_MS] leaves the agent binary usable.** The updater is
 *   upstream's and its own atomicity is its own business; this build has never interrupted one and
 *   does not claim to know. [TIMED_OUT] says the guest was stopped and stops there.
 * - **Whether the agent's updater resumes a partial download or starts again.** Nothing measured
 *   here answers that, and the record never says it will.
 *
 * ### The bound, and why it is 60 seconds
 *
 * **A boot must not hang on a network that never answers, and that is the whole of the number.**
 * The measured fast path is 0.48–0.58 s and the measured dead-network path is 0.23 s, so a minute is
 * a hundredfold over everything this build has ever seen and a bound short enough that a start of
 * the app is a start of the app. **It is deliberately not long enough to cover a real install**, and
 * the consequence is stated rather than hidden: on a phone radio an update that is genuinely
 * downloading is stopped at the bound, recorded as [TIMED_OUT] with whatever the guest last said,
 * and the next boot runs it again. The alternative — a bound long enough for 224 MB — is minutes of
 * a WebView waiting on a radio, which is the hang this class exists to prevent.
 *
 * ### The record
 *
 * **Beside the provisioning state file, outside the rootfs, in the app's own storage**, because
 * [omp.vm.doctor.Doctor] reads host paths and the guest's `/root` is a directory inside the payload
 * that a `vm reset` throws away. It is a `key value` file for the same reason
 * [omp.vm.provision.ProvisionPaths.records] is one: one thing somebody has to be able to read in a
 * text editor, written by this class and parsed by this class.
 */
class AgentUpdate(
    val paths: ProvisionPaths,
    private val vfs: Vfs,
    private val proot: ProotCommand,
    private val transport: UpdateTransport,
    /**
     * The wait, in milliseconds, handed to [transport] on every run. A parameter because a caller
     * that has measured this device's radio has a better number than this build's guess, and
     * because a test asserts the number that was passed rather than the field that holds it.
     */
    private val boundMs: Long = BOUND_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Run the update in the guest, once, and record what happened. Never throws and never blocks for
     * longer than the bound.
     *
     * @param state what is on this device, read off the disk by
     *   [omp.vm.provision.ProvisionPaths.state] and never remembered. **A device that is not
     *   provisioned is not updated**, and that is decided here rather than by a caller, because the
     *   rule belongs to the step that moves the bytes.
     */
    fun run(state: ProvisionState): UpdateReport {
        val previous = read(vfs, paths)
        if (!state.rootfsInstalled) {
            return finish(
                UpdateOutcome.NOT_PROVISIONED,
                version = null,
                was = null,
                status = null,
                said = null,
                "omp update in the guest: not-provisioned — there is no unpacked Debian at " +
                    "${paths.rootfsDir} (${ProvisionPaths.ROOTFS_MARKER} is not in it), so there is no " +
                    "guest to update. Nothing was started and nothing was downloaded.",
            )
        }
        if (!state.agentInstalled) {
            return finish(
                UpdateOutcome.NO_AGENT,
                version = null,
                was = null,
                status = null,
                said = null,
                "omp update in the guest: no-agent — the Debian is at ${paths.rootfsDir} and there is " +
                    "no agent at ${paths.agentBinary} to update. Nothing was started.",
            )
        }
        val run = transport.run(proot.agentArgv(listOf(UPDATE)), proot.env(), proot.workDir, boundMs)
        val version = versionOf(run.output)
        val said = saidOf(run.output)
        val outcome = when {
            run.stopped -> UpdateOutcome.TIMED_OUT
            run.status != 0 -> UpdateOutcome.FAILED
            run.output.contains(ALREADY_CURRENT) -> UpdateOutcome.ALREADY_CURRENT
            else -> UpdateOutcome.UPDATED
        }
        // The before-and-after, assembled out of two boots' own reports. A record whose version is
        // the same as the last one carries no `was` line, because nothing changed and a number that
        // did not move is not news.
        val was = previous?.version?.takeIf { version != null && it != version }
        val line = when (outcome) {
            UpdateOutcome.TIMED_OUT ->
                "omp update in the guest: timed-out — the guest was still running after ${boundMs}ms and " +
                    "was stopped. ${saidClause(said)} What it had done by then is not established, and the " +
                    "next boot runs it again."
            UpdateOutcome.FAILED ->
                "omp update in the guest: failed — the guest exited ${run.status}. ${saidClause(said)} " +
                    "The agent on this device is unchanged" + (version?.let { " at $it" } ?: "") +
                    ", and `omp update && omp` means the guest's own agent is not started either."
            UpdateOutcome.ALREADY_CURRENT ->
                "omp update in the guest: already-current — " + saidClause(said) + ", so the agent in the " +
                    "guest is ${version ?: "of a version this build could not read"} and nothing was downloaded."
            else ->
                "omp update in the guest: updated — " + saidClause(said) + ". The guest reported " +
                    "${version ?: "no version"} before it ran, and what a run that installs leaves " +
                    "behind is not established by this build; the next boot's own report is what names it."
        }
        return finish(
            outcome,
            version = version,
            was = was,
            status = run.status.takeIf { !run.stopped },
            said = said,
            line = line,
        )
    }

    /**
     * The guest's own last line, quoted, or the fact that it wrote none.
     *
     * **A guest that wrote nothing is said so rather than left out.** Silence where a reason should
     * be is the one shape a reader cannot act on, and this is the line that says why the guest's own
     * agent is not answering.
     */
    private fun saidClause(said: String?): String = if (said == null) {
        "The guest wrote nothing before it stopped."
    } else {
        // The full stop is outside the quotes, because the guest did not write one and a quotation
        // with this build's punctuation inside it is a misquotation.
        "The guest's own last line was \"$said\"."
    }

    /**
     * Write the record and hand back the report, saying so on the line if the disk would not take it.
     *
     * **A record that cannot be written is a sentence and not an exception**, for the reason the
     * whole class exists: the boot is starting, and an app that will not open because a 60-byte text
     * file in its own storage could not be written is the failure mode of a feature that has been
     * given a stage at startup.
     */
    private fun finish(
        outcome: UpdateOutcome,
        version: String?,
        was: String?,
        status: Int?,
        said: String?,
        line: String,
    ): UpdateReport {
        val written = try {
            write(vfs, paths, outcome, version, was, status, said, boundMs, now())
            true
        } catch (e: FsException) {
            false
        }
        return UpdateReport(
            outcome = outcome,
            version = version,
            was = was,
            status = status,
            boundMs = boundMs,
            said = said,
            recorded = written,
            line = if (written) {
                line
            } else {
                "$line This record could not be written to ${recordFile(paths)}: ${UNREADABLE} the " +
                    "filesystem refused it, so 'omp doctor' will have nothing to read."
            },
        )
    }

    companion object {

        /** The subcommand, and the only token in the vector after the guest's binary. */
        const val UPDATE = "update"

        /**
         * The wait, in milliseconds. **See the class KDoc for the measurements it is between and
         * the consequence of choosing it**: 0.48–0.58 s for a check that finds nothing and 0.23 s
         * for a refused connect, both measured on `omp/18.3.5`, and a bound that does not cover a
         * 224 MB install on a phone radio.
         */
        const val BOUND_MS = 60_000L

        /** The guest's own version line, exactly as it was measured on 18.3.5. */
        const val VERSION_LINE = "Current version:"

        /**
         * The sentence that means "there was nothing to install", exactly as measured.
         *
         * **Matched on the words and not on the tick in front of them.** `omp` prints
         * `✔ Already up to date`; the tick is a decoration of a terminal that may not be there, and a
         * step that keys on it would stop recognising its own success the first time upstream drew
         * it differently.
         */
        const val ALREADY_CURRENT = "Already up to date"

        /** How much of the guest's own last line is kept, so one runaway line cannot become a report. */
        const val MAX_SAID = 200

        /** The word used wherever a fact could not be read, so it reads the same as everywhere else. */
        const val UNREADABLE = "unreadable"

        // ---- the record ---------------------------------------------------------------------------

        /** The file name inside [omp.vm.provision.ProvisionPaths.downloadDir]. */
        const val RECORD_NAME = "update"

        /** The first line, which is a comment and says which build wrote the rest. */
        const val RECORD_HEADER = "#omp-update/v1 — written by omp.vm.guestapi.AgentUpdate, one run per boot"

        const val KEY_OUTCOME = "outcome"
        const val KEY_AT = "at"
        const val KEY_VERSION = "version"
        const val KEY_WAS = "was"
        const val KEY_SAID = "said"
        const val KEY_STATUS = "status"
        const val KEY_BOUND = "bound"

        /** Where the record lives, which is beside the provisioning state file and not in the rootfs. */
        fun recordFile(paths: ProvisionPaths): String = "${paths.downloadDir}/$RECORD_NAME"

        /**
         * The record as it is on the disk, or null when there is none.
         *
         * **A record that is not there is null and never an exception**: a device that has never run
         * an update is the ordinary case and `omp doctor` has to be able to say so. A record whose
         * `outcome` is a name this build does not know is returned whole, with
         * [UpdateRecord.outcome] null and [UpdateRecord.outcomeName] holding what was written — so
         * the report can name the value instead of reading a newer build's record as a failure.
         */
        fun read(vfs: Vfs, paths: ProvisionPaths): UpdateRecord? {
            val text = try {
                String(vfs.readBytes(recordFile(paths)), Charsets.UTF_8)
            } catch (e: FsException) {
                return null
            }
            val fields = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split(' ', limit = 2)
                if (parts.size == 2) fields[parts[0]] = parts[1]
            }
            return if (fields.isEmpty()) null else UpdateRecord(fields)
        }

        private fun write(
            vfs: Vfs,
            paths: ProvisionPaths,
            outcome: UpdateOutcome,
            version: String?,
            was: String?,
            status: Int?,
            said: String?,
            boundMs: Long,
            atMillis: Long,
        ) {
            val file = recordFile(paths)
            mkdirs(vfs, file.substringBeforeLast('/'))
            val text = StringBuilder(RECORD_HEADER).append('\n')
            fun line(key: String, value: String?) {
                if (value != null) text.append(key).append(' ').append(value).append('\n')
            }
            line(KEY_OUTCOME, outcome.name)
            line(KEY_AT, stamp(atMillis))
            line(KEY_VERSION, version)
            line(KEY_WAS, was)
            line(KEY_SAID, said)
            // The guest's own status when it returned one, and the outcome's fixed number when it
            // did not — a run that was stopped has no status of its own, and a record with a hole
            // where the number should be is a record that cannot be diffed between two boots.
            line(KEY_STATUS, (status ?: outcome.exitStatus).toString())
            line(KEY_BOUND, boundMs.toString())
            vfs.writeBytes(file, text.toString().toByteArray(Charsets.UTF_8))
        }

        /**
         * The version the guest printed, or null when it printed no such line.
         *
         * **The first one, not the last.** Upstream prints the version before it does anything else,
         * and if it ever printed a second one this would take the first on purpose: the fact this
         * step needs is the version the guest *had* when the update ran.
         */
        fun versionOf(output: String): String? = VERSION.find(output)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

        /**
         * The guest's own last non-empty line, cut to [MAX_SAID] characters, or null when it wrote
         * none at all.
         *
         * **The last line, because that is where the reason is.** Measured: the success path ends
         * with "✔ Already up to date" and the no-network path ends with "Failed to check for
         * updates: TypeError: Unable to connect…", both after the "Current version:" line. A cut is
         * marked with an ellipsis so a half-sentence is never printed as if it were the whole one.
         */
        fun saidOf(output: String): String? {
            val last = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
            return if (last.length <= MAX_SAID) last else last.take(MAX_SAID) + "… (cut)"
        }

        /** UTC, ISO-8601, and the same stamp the journal uses — see `omp.vm.service.ServiceManager`. */
        private fun stamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))

        /** `^Current version: <something>$`, one line, and nothing else. */
        private val VERSION = Regex("""^\s*""" + Regex.escape(VERSION_LINE) + """\s*(.+)$""", RegexOption.MULTILINE)

        private fun mkdirs(vfs: Vfs, dir: String) {
            var path = ""
            for (part in dir.split('/')) {
                if (part.isEmpty()) continue
                path += "/$part"
                try {
                    vfs.mkdir(path)
                } catch (e: FsException) {
                    // FILE_EXISTS is the ordinary answer for every level above the first, because
                    // `provision/` is created by the download that wrote the state file beside this
                    // one. Anything else is a real refusal and is left to the caller's catch.
                    if (e.errno != omp.shell.fs.FsErrno.FILE_EXISTS) throw e
                }
            }
        }
    }
}

/**
 * The record, as it was read: the fields as written, and the two answers a reader needs from them.
 *
 * **[outcome] is null for a name this build does not have, and [outcomeName] is the name either
 * way.** That is the whole of why both exist: a record written by a newer build must be reported as
 * "a name this build does not know" and never as one of this build's six, which would be a lie
 * about a value nobody chose.
 */
data class UpdateRecord(val fields: Map<String, String>) {

    /** The named outcome, or null when the record names one this build does not have. */
    val outcome: UpdateOutcome?
        get() = fields[AgentUpdate.KEY_OUTCOME]?.let { name -> UpdateOutcome.entries.firstOrNull { it.name == name } }

    /** What the record says, exactly. */
    val outcomeName: String?
        get() = fields[AgentUpdate.KEY_OUTCOME]

    val version: String?
        get() = fields[AgentUpdate.KEY_VERSION]

    /** The version the run before this one reported, when this one reported a different one. */
    val was: String?
        get() = fields[AgentUpdate.KEY_WAS]

    val said: String?
        get() = fields[AgentUpdate.KEY_SAID]

    /** The status, as written: the guest's own, or the outcome's fixed number for a stopped run. */
    val status: String?
        get() = fields[AgentUpdate.KEY_STATUS]

    val at: String?
        get() = fields[AgentUpdate.KEY_AT]

    val bound: String?
        get() = fields[AgentUpdate.KEY_BOUND]
}
