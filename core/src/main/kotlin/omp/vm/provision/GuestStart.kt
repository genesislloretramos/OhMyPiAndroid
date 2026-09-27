package omp.vm.provision

import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateOutcome
import omp.vm.guestapi.UpdateReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The six things this build can be in after it has tried to start the guest, and every line that
 * says which one it is.
 *
 * **A closed set of names because a caller has to branch on it and a person has to read it.** The
 * same two reasons [omp.vm.guestapi.UpdateOutcome] is: a value a decision is made from must not be a
 * paragraph, and a state a report can only describe after the fact is a state nothing can be done
 * about. [serving] is the one boolean that changes what the app shows.
 *
 * | state | what happened | [serving] |
 * |---|---|---|
 * | [NO_DEBIAN] | there is no unpacked Debian, so nothing was launched | no |
 * | [NOT_STARTED] | there is a Debian, and this build did not start it, and it says why | no |
 * | [PORT_TAKEN] | the reserved port was not free, so Apache was not started | no |
 * | [APACHE_NOT_ANSWERING] | the guest was started and no page came back | no |
 * | [AGENT_UPDATE_FAILED] | Apache is answering, and the boot's `omp update` did not land | **yes** |
 * | [UP] | Apache is answering and the boot's `omp update` landed | **yes** |
 *
 * **The last two are both "the Debian is answering", and the difference is the agent in it.** That
 * split is the whole of [omp.vm.guestapi.AgentUpdate]'s contract surviving into the origin: a failed
 * update stops the guest's own `omp` and stops nothing else, so a guest whose agent is out of date is
 * **still a guest serving a page**, and showing this app's Kotlin server instead would be trading a
 * real page from a Debian for a page from the app on the strength of a version number.
 */
enum class GuestState(val serving: Boolean) {

    /** There is no unpacked Debian on this device, so nothing was launched and nothing could be. */
    NO_DEBIAN(false),

    /** A Debian is on the device and this build did not start it. [GuestStartReport.lines] says which of the reasons. */
    NOT_STARTED(false),

    /**
     * The port this build reserves for the guest was already held.
     *
     * **A named state and never a fall back.** A collision that quietly showed the app's own
     * loopback server would tell a user the real agent was answering while the Kotlin one was — see
     * [GuestWeb] for why that is the one lie this path must not tell.
     */
    PORT_TAKEN(false),

    /** The guest was started and no page came back on the reserved port. */
    APACHE_NOT_ANSWERING(false),

    /** Apache is answering and the boot's `omp update` did not land, so the agent in it is unchanged. */
    AGENT_UPDATE_FAILED(true),

    /** Apache is answering and the boot's `omp update` landed. */
    UP(true),
    ;

    /**
     * What this state means, in the words a user is reading.
     *
     * **The name leads the line and this is the sentence after it**, for the reason
     * [omp.vm.doctor.Doctor]'s `outcome:` line gives: the name is what a `grep` finds and what two
     * reports diff on, and the sentence is what a person has to be able to act on. Every one of them
     * says what the app is showing as well as what the guest is doing, because "is the guest up" and
     * "which agent is answering" are one question to the person holding the phone.
     */
    fun meaning(port: Int): String = when (this) {
        NO_DEBIAN ->
            "there is no unpacked Debian on this device, so nothing was started, and the chat the " +
                "WebView was handed is this app's own loopback server"
        NOT_STARTED ->
            "a Debian is on this device and this build has not started the guest, so the chat the " +
                "WebView was handed is this app's own loopback server; a guest that was only launched " +
                "is never shown as though it were answering"
        PORT_TAKEN ->
            "something on this phone already holds ${GuestWeb.baseUrl(port)}, so Apache was not " +
                "started at all, and the Debian's origin was refused rather than replaced by this " +
                "app's own server"
        APACHE_NOT_ANSWERING ->
            "the guest was started and nothing answered a request for this build's chat page on " +
                "${GuestWeb.baseUrl(port)}, so the Debian's origin was refused rather than replaced " +
                "by this app's own server"
        AGENT_UPDATE_FAILED ->
            "Apache inside the Debian is answering on ${GuestWeb.baseUrl(port)} and the boot's " +
                "'omp update' did not land, so the page is the Debian's and the agent inside it is " +
                "the one that was already there"
        UP ->
            "Apache inside the Debian is answering on ${GuestWeb.baseUrl(port)} and the boot's " +
                "'omp update' landed, so the page the WebView was handed is the Debian's"
    }
}

/** What happened when the guest's server was asked to start, and what it said on the way. */
data class GuestLaunch(

    /** False when the process could not be started at all — see [omp.vm.provision.GuestStart]. */
    val started: Boolean,

    /**
     * The guest's own last line, or null when it wrote none.
     *
     * **Quoted into the report and never paraphrased**, for the reason
     * [omp.vm.guestapi.AgentUpdate] quotes the same thing: the reason Apache did not come up is the
     * guest's to give. A `proot: it could not be started: …` from
     * [com.omp.terminal.vm.ProotProcessLauncher] is a better sentence than anything this build could
     * write about it, and on a phone it is the only evidence there will be.
     */
    val said: String? = null,
)

/**
 * The half of starting the guest that needs a device, behind one method.
 *
 * **A separate seam from [ProotLauncher] because the job is different.** [ProotLauncher] returns an
 * exit status for a command that is *supposed* to finish — `apt-get`, `a2enconf`, `omp update` — and
 * its caller waits for it. Apache is not that: it is started and left running for as long as the app
 * is, and a method that returned a status would have returned a number describing a server that is
 * still serving. So this one answers whether the process exists, and [GuestWeb.WebProbe] answers
 * whether it is serving.
 *
 * **The shipped implementation is in `:app`, and its `ProcessBuilder.start()` is the one line of the
 * whole guest path that can only ever run on a phone.** Everything above it — the argument vectors,
 * the state machine, the port check, the record and every line of the report — is plain JVM and is
 * exercised by tests in this repository.
 */
fun interface GuestServer {

    /** Starts the guest command and leaves it running, or says why it could not be started. */
    fun start(argv: List<String>, env: Map<String, String>, cwd: String): GuestLaunch
}

/**
 * What one attempt to bring the guest up ended as, and the lines that say so.
 *
 * [state] is the field a caller acts on, [lines] is the field a person reads, and they are kept in
 * step for the reason [omp.vm.provision.BootReport] keeps its own two in step: the one thing this
 * app must never do is let a user wonder which program they are talking to.
 */
data class GuestStartReport(

    /** Which of the six this is. */
    val state: GuestState,

    /** The port this build reserved, whether or not it was free. */
    val port: Int,

    /** Whether the port was free when it was checked, immediately before anything was launched. */
    val reserved: Boolean,

    /** Whether the Apache process was started at all. */
    val launched: Boolean,

    /** Whether an HTTP server on [port] served this build's chat document. */
    val answered: Boolean,

    /** The boot's `omp update` by name, and null when it never ran — there is no Debian to run it in. */
    val update: UpdateOutcome?,

    /** The version the guest reported for itself, when it reported one. */
    val version: String?,

    /** The guest's own last line about the start, when it wrote one. */
    val said: String?,

    /** When this run happened, UTC. */
    val at: String,

    /** Every line, in the order it happened. */
    val lines: List<String>,
) {

    /** Whether the Debian is serving, and so whether its origin may be shown. See [GuestState.serving]. */
    val serving: Boolean get() = state.serving
}

/**
 * The guest start: reserve the port, link the API configuration in, start Apache, ask whether a page
 * comes back, run the boot's `omp update`, and record which of the [GuestState]s that was.
 *
 * ### The order, and the one rule it enforces
 *
 * ```
 * no Debian?                 -> NO_DEBIAN, nothing launched
 * port already held?         -> PORT_TAKEN, nothing launched
 * a2enconf omp-guest         -> the drop-in omp.vm.guestapi.GuestApiTree wrote is linked into Apache
 * apache2ctl -D FOREGROUND   -> the server, left running
 * GET / on 127.0.0.1:PORT    -> is it actually serving
 * omp update                 -> bounded, unattended, recorded, and last
 * ```
 *
 * **Apache is started before `omp update` runs, and that ordering is the rule rather than an
 * accident.** [omp.vm.guestapi.AgentUpdate] is bounded at 60 seconds and may be stopped at it, and
 * the WebView is waiting the whole time. If the update came first, a phone on a radio where the check
 * takes the full minute would show nothing for a minute and then a page; and a start that took the
 * `&&` seriously enough to leave the guest down on a failed update would be a failed update turned
 * into a device with nothing on it. **So the agent is the last thing here and gates nothing**:
 * Apache, its PHP API, its filesystem and its `web` command come up whether or not the update did,
 * which is what [omp.vm.guestapi.AgentUpdate]'s own KDoc promises about `omp update && omp` and what
 * this class makes structurally true rather than merely stating.
 *
 * **The `&&` itself is not re-implemented here.** Whether the guest's *own* `omp` starts is
 * [omp.vm.provision.Boot]'s business and this class does not touch it; this one starts the web half
 * and records the update's own named outcome, and the six states above are what the two add up to.
 *
 * ### What is not verified here, and cannot be off a device
 *
 * Every step below this class's own arithmetic is a question a phone has to answer, and none of them
 * is answered by a test in this repository:
 *
 * 1. **Whether proot runs at all.** [omp.vm.provision.ProotCommand] and
 *    [com.omp.terminal.vm.ProotProcessLauncher] each list the open questions, and a refusal from
 *    either reaches here as [GuestStartReport.said] and becomes [GuestState.APACHE_NOT_ANSWERING].
 * 2. **Whether `apache2ctl -D FOREGROUND` binds** under proot's `ptrace` — see
 *    [GuestWeb.APACHE_COMMAND].
 * 3. **Whether a page comes back**, which is why [GuestWeb.WebProbe] is a question and not an
 *    assumption, and why [GuestState.APACHE_NOT_ANSWERING] is a state a user can be in.
 * 4. **Whether the guest's PHP answers the six routes** its contract test pins. This class never
 *    asks the API anything: the probe is a `GET /` for the document, because a probe that asked the
 *    API would need this app's token inside a guest that is not supposed to know about it.
 */
class GuestStart(

    /** Where the payload is and what is in it. Read, never remembered. */
    private val paths: ProvisionPaths,
    private val vfs: Vfs,
    private val proot: ProotCommand,

    /** The guest's own `a2enconf`, and the only step here that runs a command to completion. */
    private val packages: GuestPackages,

    /** The device half: start the server and leave it running. */
    private val server: GuestServer,

    /** Whether the port is free, and the only thing standing between a guest and a collision. */
    private val binder: GuestWeb.PortBinder,

    /** Whether a page is actually coming back. */
    private val probe: GuestWeb.WebProbe,

    /** The boot's `omp update`. Handed in because it needs a transport this module does not have. */
    private val update: AgentUpdate,

    private val port: Int = GuestWeb.RESERVED_PORT,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Bring the guest up, or say which of the six ways it is not up.
     *
     * **Never throws and never blocks for longer than the update's own bound.** Every failure is a
     * state and a line, because the alternative — an app that will not open because proot is not on
     * this device — is the failure mode of a feature that has been given a stage at start-up.
     */
    fun start(): GuestStartReport {
        val lines = ArrayList<String>()
        val state = paths.state(vfs)
        if (!state.rootfsInstalled) {
            lines += "there is no unpacked Debian at ${proot.rootfs} " +
                "(${ProvisionPaths.ROOTFS_MARKER} is not in it), so there is no guest to start. " +
                "Nothing was launched and nothing was downloaded."
            return report(GuestState.NO_DEBIAN, lines, reserved = false, launched = false)
        }
        if (!binder.isFree(port)) {
            // Named here, in the boot's own report, before anything is launched. The whole of the
            // decision is that this is a state a user can read rather than a server they were never
            // told about.
            lines += "${GuestWeb.baseUrl(port)} is already held by something else on this phone, so " +
                "Apache was not started and no guest origin was named. This build does not show its " +
                "own loopback server in its place: the two are different agents, and the report says " +
                "which one is answering."
            return report(GuestState.PORT_TAKEN, lines, reserved = false, launched = false)
        }
        lines += "reserved ${GuestWeb.baseUrl(port)}: it was free, and it has been given back so " +
            "Apache can take it."

        // The API configuration, before the server that reads it. One command, no download, and not
        // gated on the agent.
        val api = packages.enableApi()
        lines += api.lines
        if (!state.guestInstalled) {
            lines += "the guest's own ${api.guest.name} is not in the Debian, so there is no apache2 " +
                "to start: ${ProvisionPaths.GUEST_MARKER} is not in ${paths.rootfsDir}. Nothing was " +
                "launched, and this build does not install it at start-up — 'omp provision' prints " +
                "the cost and asks before a byte moves."
            return report(
                GuestState.APACHE_NOT_ANSWERING,
                lines,
                reserved = true,
                launched = false,
                said = api.lines.lastOrNull(),
            )
        }

        val launch = server.start(proot.argv(GuestWeb.APACHE_COMMAND), proot.env(), proot.workDir)
        launch.said?.let { lines += "the guest's Apache: $it" }
        if (!launch.started) {
            lines += "apache2 was not started, so nothing is listening on ${GuestWeb.baseUrl(port)} " +
                "and the guest's origin was refused."
            return report(
                GuestState.APACHE_NOT_ANSWERING,
                lines,
                reserved = true,
                launched = false,
                said = launch.said,
            )
        }
        lines += "apache2 was started inside the Debian and left running; this build does not know " +
            "yet whether it is answering."

        val answered = probe.answers(port)
        lines += if (answered) {
            "an HTTP server on ${GuestWeb.baseUrl(port)} returned this build's own chat document, " +
                "so Apache inside the Debian is serving it."
        } else {
            "nothing answered a request for this build's chat document on ${GuestWeb.baseUrl(port)} " +
                "inside ${GuestWeb.ATTEMPTS * GuestWeb.PAUSE_MS}ms, so the guest's origin was refused."
        }

        // Last, and gating nothing above. See the class KDoc: the agent is the one thing on this
        // path that the `&&` in `omp update && omp` stops, and it is deliberately not what the page
        // depends on.
        val updated = update.run(state)
        lines += updated.line

        val settled = when {
            !answered -> GuestState.APACHE_NOT_ANSWERING
            updated.outcome == UpdateOutcome.UPDATED || updated.outcome == UpdateOutcome.ALREADY_CURRENT ->
                GuestState.UP
            else -> GuestState.AGENT_UPDATE_FAILED
        }
        if (answered && settled == GuestState.AGENT_UPDATE_FAILED) {
            lines += "the guest's own omp was not started, because `omp update && omp` stops at the " +
                "first half and the first half was '${updated.outcome}'. The guest is up: its Apache, " +
                "its PHP API, its filesystem and its 'web' command do not depend on the agent."
        }
        return report(
            settled,
            lines,
            reserved = true,
            launched = true,
            answered = answered,
            update = updated,
            said = launch.said,
        )
    }


    private fun report(
        state: GuestState,
        lines: List<String>,
        reserved: Boolean,
        launched: Boolean,
        answered: Boolean = false,
        update: UpdateReport? = null,
        said: String? = null,
    ): GuestStartReport {
        val finished = GuestStartReport(
            state = state,
            port = port,
            reserved = reserved,
            launched = launched,
            answered = answered,
            update = update?.outcome,
            version = update?.version,
            said = said,
            at = stamp(now()),
            lines = lines,
        )
        // The record is written from the report rather than beside it, so there is exactly one
        // place that decides what a run looks like. A write that failed is a line on the report
        // itself, carried by a second report — never a silent half-record.
        if (!record(vfs, paths, finished)) {
            return finished.copy(
                lines = finished.lines + (
                    "this report could not be written to ${recordFile(paths)}, so 'omp doctor' " +
                        "will have nothing to read about the guest's origin."
                    ),
            )
        }
        return finished
    }

    /**
     * The keys of the record on the disk, in the order they are written.
     *
     * **Public because `omp doctor` reads them, and a writer and a reader that spelled the same
     * words twice would drift** — the reason every other record in this layer
     * ([omp.vm.guestapi.AgentUpdate], [omp.vm.provision.ProvisionPaths.records]) names its keys in a
     * companion rather than inline.
     */
    companion object {

        /** [GuestState]'s own name, so a `grep` for `PORT_TAKEN` finds the collision. */
        const val KEY_STATE: String = "state"

        const val KEY_PORT: String = "port"

        /** `yes` or `no`: whether the port was free when it was checked. */
        const val KEY_RESERVED: String = "reserved"

        /** `yes` or `no`: whether the Apache process was started at all. */
        const val KEY_LAUNCHED: String = "launched"

        /**
         * `yes` or `no`: whether a page came back.
         *
         * **The one field the origin is decided on**, and it is a field rather than a derivation
         * from [KEY_LAUNCHED] because the two are different claims: a process was started, or a page
         * arrived. Only the second is a server.
         */
        const val KEY_ANSWERED: String = "answered"

        /** The boot's `omp update` by name, as [omp.vm.guestapi.UpdateOutcome] spells it. */
        const val KEY_AGENT: String = "agent"

        const val KEY_VERSION: String = "version"

        /** The guest's own last line, on one line. See [GuestLaunch.said]. */
        const val KEY_SAID: String = "said"

        const val KEY_AT: String = "at"

        /** UTC, ISO-8601: the stamp [omp.vm.guestapi.AgentUpdate] writes into its own record. */
        fun stamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }
}

/**
 * A start that was not even attempted, and the reason, recorded the same way every other one is.
 *
 * **A function and not a method, for one reason: it needs no guest to say anything about.** A
 * precondition that fails before there is anything to launch — a device whose architecture this
 * build does not know, or one where there is no conversations directory to bind at
 * [omp.vm.provision.ProotCommand.VISIBLE_MOUNT], which is what a missing "All files access" grant
 * means — has no `ProotCommand`, no `GuestServer` and no `AgentUpdate` to be constructed first, and
 * building six objects in order to say "this device is not one I can start" is ceremony that would
 * read as work.
 *
 * **It writes the same record as every other start**, and that is the point: a refusal that only
 * existed in memory would be gone by the time `omp doctor` was asked, and the report that names
 * which precondition stopped the guest is the only way a user learns it.
 */
fun notStarted(
    vfs: Vfs,
    paths: ProvisionPaths,
    reason: String,
    port: Int = GuestWeb.RESERVED_PORT,
    at: String = GuestStart.stamp(System.currentTimeMillis()),
): GuestStartReport {
    val report = GuestStartReport(
        state = GuestState.NOT_STARTED,
        port = port,
        reserved = false,
        launched = false,
        answered = false,
        update = null,
        version = null,
        said = null,
        at = at,
        lines = listOf(reason),
    )
    return if (record(vfs, paths, report)) {
        report
    } else {
        report.copy(
            lines = report.lines + (
                "this report could not be written to ${recordFile(paths)}, so 'omp doctor' will " +
                    "have nothing to read about the guest's origin."
                ),
        )
    }
}


/**
 * The record [omp.vm.provision.GuestStart] leaves on the disk, and the answers a reader needs.
 *
 * ### Why it is a file and not a field
 *
 * **`omp doctor` runs in a different process from the one that started the guest**, on a device that
 * may have been rebooted since, and it is read-only: it may not ask Apache anything and it may not
 * start anything to find out. So the one fact that answers "is the guest up, and is it the one
 * answering" has to have survived, and the same `key value` line format
 * [omp.vm.guestapi.AgentUpdate] uses is what survives: one thing a person can open in a text editor,
 * written here and parsed here.
 *
 * **It is in the app's own storage beside the provisioning record, not in the rootfs**, for that
 * class's reason: the guest's `/root` is a directory inside the payload that a `vm reset` throws
 * away, and a report that goes with the thing it reports is not a report.
 */
data class GuestOriginRecord(val fields: Map<String, String>) {

    /** The state, or null when the record names one this build does not have. */
    val state: GuestState?
        get() = fields[GuestStart.KEY_STATE]?.let { name -> GuestState.entries.firstOrNull { it.name == name } }

    /** What the record says, exactly — the whole of why an unknown name is never read as a failure. */
    val stateName: String? get() = fields[GuestStart.KEY_STATE]

    val port: Int? get() = fields[GuestStart.KEY_PORT]?.toIntOrNull()

    val reserved: Boolean? get() = fields[GuestStart.KEY_RESERVED]?.let { it == "yes" }

    val launched: Boolean? get() = fields[GuestStart.KEY_LAUNCHED]?.let { it == "yes" }

    val answered: Boolean? get() = fields[GuestStart.KEY_ANSWERED]?.let { it == "yes" }

    /** The boot's `omp update` by name, as [omp.vm.guestapi.UpdateOutcome] spells it, or null. */
    val update: String? get() = fields[GuestStart.KEY_AGENT]

    val version: String? get() = fields[GuestStart.KEY_VERSION]

    val said: String? get() = fields[GuestStart.KEY_SAID]

    val at: String? get() = fields[GuestStart.KEY_AT]
}

/** The file name inside [omp.vm.provision.ProvisionPaths.downloadDir]. */
const val GUEST_ORIGIN_RECORD_NAME = "guest-origin"

private const val RECORD_HEADER =
    "#omp-guest-origin/v1 — written by omp.vm.provision.GuestStart, one run per start of the app"

/** Where the record lives, which is beside [omp.vm.guestapi.AgentUpdate]'s own and not in the rootfs. */
fun recordFile(paths: ProvisionPaths): String = "${paths.downloadDir}/$GUEST_ORIGIN_RECORD_NAME"

/**
 * The record as it is on the disk, or null when there is none.
 *
 * **Null and never an exception**, and null is the ordinary case: a device that has never started the
 * guest is every device before its first run of it, and `omp doctor` has to be able to say so.
 */
fun readRecord(vfs: Vfs, paths: ProvisionPaths): GuestOriginRecord? {
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
    return if (fields.isEmpty()) null else GuestOriginRecord(fields)
}

/**
 * Write the record, or say on the caller's own lines that it did not land.
 *
 * **A refusal to write is a sentence and not an exception**, for the reason the whole of this path
 * exists: the app is starting, and an app that will not open because a 60-byte text file in its own
 * storage could not be written is the failure mode of a feature given a stage at start-up.
 */
private fun record(vfs: Vfs, paths: ProvisionPaths, report: GuestStartReport): Boolean = try {
    val file = recordFile(paths)
    mkdirs(vfs, file.substringBeforeLast('/'))
    val text = StringBuilder(RECORD_HEADER).append('\n')
    fun line(key: String, value: String?) {
        if (value != null) text.append(key).append(' ').append(value).append('\n')
    }
    line(GuestStart.KEY_STATE, report.state.name)
    line(GuestStart.KEY_PORT, report.port.toString())
    line(GuestStart.KEY_RESERVED, yesNo(report.reserved))
    line(GuestStart.KEY_LAUNCHED, yesNo(report.launched))
    line(GuestStart.KEY_ANSWERED, yesNo(report.answered))
    line(GuestStart.KEY_AGENT, report.update?.name)
    line(GuestStart.KEY_VERSION, report.version)
    // One line, always: a `said` with a newline in it would be two keys, and a record a person
    // cannot read line by line is not the thing this format is for.
    line(GuestStart.KEY_SAID, report.said?.replace('\n', ' ')?.trim()?.ifEmpty { null })
    line(GuestStart.KEY_AT, report.at)
    vfs.writeBytes(file, text.toString().toByteArray(Charsets.UTF_8))
    true
} catch (e: FsException) {
    false
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

