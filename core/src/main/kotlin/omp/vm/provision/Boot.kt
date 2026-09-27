package omp.vm.provision

import omp.agent.Agent
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateOutcome
import omp.vm.guestapi.UpdateReport

/** Which of the two agents a user is talking to. Said out loud, every time, in [BootReport.lines]. */
enum class Answering {
    /** The real `omp` binary, downloaded, under proot, inside a Debian this app fetched. */
    REAL_AGENT,

    /** The Kotlin agent that is in this build, which needs no download and always works. */
    KOTLIN_AGENT,
}

/**
 * What a start of the app decided, and every line that says so.
 *
 * [answering] is the field a caller acts on and [lines] is the field a user reads, and they are
 * kept in step on purpose: the one thing this app must never do is let a user wonder which agent
 * they are talking to. A report that names an agent without saying why is worse than no report.
 *
 * [update] is a name and not a sentence for the reason [omp.vm.guestapi.UpdateOutcome] is a sealed
 * set: a caller that has to decide something about the update — a notification, a status line, a
 * number to exit with — must not have to read a paragraph to find out which of six things happened.
 * [version] is the version the guest reported for itself during that run, and null whenever
 * nothing ran or the guest printed no such line.
 */
data class BootReport(
    val answering: Answering,
    val lines: List<String>,
    /** What the boot's `omp update` did, by name. */
    val update: UpdateOutcome,
    /** The version the guest reported during the update, or null when there was none. */
    val version: String?,
    /** `omp update`'s exit status when it ran, null when it did not. */
    val updateStatus: Int?,
    /** `omp`'s exit status when it ran, null when it did not. */
    val runStatus: Int?,
)

/**
 * The sequence the app starts with: `omp update && omp` — or say, in a sentence, what it would cost
 * and what is answering in the meantime.
 *
 * **The rule this class exists to enforce: nothing is downloaded without being asked for.** On a
 * provisioned device the *Debian* has been asked for and downloaded already, so there is no
 * question left to ask; on a device that is not provisioned, the honest answer is the size of the
 * download and a statement that it is not being started, because 350,458,048 bytes of somebody's
 * mobile data is not a side effect an app is entitled to. The user says yes in a terminal, in a
 * prompt or on a screen, and [Provisioner.provision] is what runs after that.
 *
 * ### The agent, and the one line that draws the line
 *
 * **`omp update && omp` is a shell command and the shell's rule is kept exactly.** The update runs
 * first, and a failed one means the guest's own `omp` is not run at all — not run late, not run
 * anyway with a warning, not run "because it is probably fine". **The agent is the only thing on
 * this path the `&&` gates: the guest's Apache, its PHP API, its filesystem and its `web` command
 * come up whether or not the agent did, and they are what a user can debug when the agent is not
 * answering.** A boot that took the `&&` as a reason to leave the guest down would have turned a
 * failed update into a device with nothing on it, and this build's own Kotlin agent — which is in
 * the APK, needs no download and always works — is what answers instead.
 *
 * **The update is unattended, and that is a different thing from being asked.** Nobody is typing
 * during a start of the app, so [omp.vm.guestapi.AgentUpdate] does not ask: it runs inside the
 * guest, against the guest's own `/usr/local/bin/omp`, at a bound it states, and writes down one of
 * six named outcomes that `omp doctor` reads afterwards. A boot that printed nothing about a failed
 * update would be indistinguishable from a boot where the update did not exist.
 *
 * **The Kotlin agent is not a consolation prize and is not hidden.** It is in this build, it needs
 * no download, and on a 32-bit device it is the *only* agent that can ever answer, because the
 * upstream agent has no 32-bit Linux build and never will have one without a new architecture
 * decision from a project that has not made it. Every report here says which one answered and why,
 * including the case where the reason is "it already was".
 *
 * **The guest's own LAMP gets one line and no action.** The manifest's [GuestInstall] is
 * 57,211,704 bytes over the same radio and 376.7 MiB on the same disk, which makes it exactly
 * the kind of cost the rule above is about, so this class states it — [GuestInstall.costLine] —
 * and leaves the running to [GuestPackages], which a caller invokes after the user has agreed.
 * Whether it is already in the tree is read off the disk through [state], never remembered.
 *
 * @param update the step this sequence calls first. It is a parameter and not something built here
 *   because it needs a [omp.vm.guestapi.UpdateTransport] and a [omp.shell.fs.Vfs] to write its
 *   record through, and this class is handed a [ProvisionState] and nothing else; a caller that
 *   has a device builds it once and passes it in, exactly as it passes in the [launcher].
 */
class Boot(
    /** This device's ABI, or null when it is one this app does not know. */
    private val abi: Abi?,
    private val state: ProvisionState,
    private val manifest: ArtifactManifest?,
    private val proot: ProotCommand,
    private val launcher: ProotLauncher,
    /** The boot's `omp update`, run first and recorded. See the class KDoc for the `&&`. */
    private val update: AgentUpdate,
) {

    /**
     * The whole sequence, in the order the app runs it, as a report a caller can print.
     *
     * Nothing here can throw for a reason the user has to read: every failure is a line and an
     * [Answering], because the alternative — an app that will not open because a 55 MB image 404ed
     * — is the failure mode of a feature that has been given a stage at startup.
     */
    fun boot(): BootReport {
        val lines = ArrayList<String>()
        val device = abi
        val table = manifest
        // First, and on every branch: the update decides for itself whether there is a guest to
        // update, and it records why there was not. A device that has never been provisioned is a
        // real report line, not a silence.
        val updated = update.run(state)
        if (device == null || table == null) {
            lines += "this device reports an architecture this app does not know, so there is no " +
                "Debian to download for it and no agent to run here."
            lines += updated.line
            lines += kotlinAnswering()
            return report(Answering.KOTLIN_AGENT, lines, updated)
        }
        if (table.rootfs == null) {
            lines += "${device.abiName}: ${ArtifactManifest.NO_ROOTFS} Nothing was downloaded."
            lines += updated.line
            lines += kotlinAnswering()
            return report(Answering.KOTLIN_AGENT, lines, updated)
        }
        if (!state.rootfsInstalled) {
            lines += "not provisioned: ${table.describe()} It is not being started without you " +
                "asking — run the provisioning from a prompt, a screen or a terminal of your own."
            // The line a reader looks for, in the place they look: LAMP is not bundled in anything
            // this app ships, it is a first-boot `apt` inside the Debian, and it is therefore as
            // much a part of what a first run costs as the rootfs is. It is one line and it names
            // both figures, because this class is asked to be the place a cost is stated.
            lines += table.guest.costLine(installed = false)
            lines += updated.line
            lines += kotlinAnswering()
            return report(Answering.KOTLIN_AGENT, lines, updated)
        }
        lines += table.guest.costLine(installed = state.guestInstalled)

        if (!state.agentInstalled) {
            val wanted = table.agent
            lines += "the Debian at ${proot.rootfs} is here, and the agent is not: " +
                (wanted?.let { "${ArtifactManifest.humanBytes(it.sizeBytes)} for " + it.name } ?: Abi.NO_32_BIT_AGENT) +
                ", which is not being downloaded without you asking."
            lines += if (table.needsAgent) {
                "the real agent is the only one that can answer once it is here; until then:"
            } else {
                "and the agent will never be here: ${device.agentRefusal}."
            }
            lines += updated.line
            lines += kotlinAnswering()
            return report(Answering.KOTLIN_AGENT, lines, updated)
        }

        // The `&&`: the update has already run and named its outcome, and only a good one lets the
        // guest's own agent start. Everything else on the guest — Apache, the PHP API, the
        // filesystem, `web` — is none of this class's business and is not gated on it.
        lines += updated.line
        if (updated.outcome != UpdateOutcome.UPDATED && updated.outcome != UpdateOutcome.ALREADY_CURRENT) {
            lines += "the guest's own omp was not started, because `omp update && omp` stops at the " +
                "first half and the first half was '${updated.outcome}'. The guest is up: its Apache, " +
                "its PHP API, its filesystem and its 'web' command do not depend on the agent, and " +
                "they are what a user can look at when the agent is not answering."
            lines += kotlinAnswering()
            return report(Answering.KOTLIN_AGENT, lines, updated)
        }
        val run = launcher.run(proot.agentArgv(emptyList()), proot.env(), proot.workDir)
        lines += "the real omp ${ArtifactManifest.AGENT_RELEASE} is answering — the downloaded " +
            "binary, run under proot inside the Debian at ${proot.rootfs}."
        lines += "omp exited with status $run."
        return BootReport(
            answering = Answering.REAL_AGENT,
            lines = lines,
            update = updated.outcome,
            version = updated.version,
            updateStatus = updated.status,
            runStatus = run,
        )
    }

    /** The line that says which of the two agents a user is actually talking to, and that it works now. */
    private fun kotlinAnswering(): String =
        "answering now: the Kotlin agent ${Agent.VERSION} that is in this build — no download, and " +
            "it reads, writes, edits, lists and searches inside one conversation folder."

    /**
     * A boot that did not get as far as the agent: the same [Answering], the lines, and the update's
     * own outcome and version, so that a caller reading this report can tell a device that has never
     * been provisioned from one whose update failed — two boots that otherwise look the same.
     */
    private fun report(answering: Answering, lines: List<String>, updated: UpdateReport) =
        BootReport(answering, lines, updated.outcome, updated.version, updated.status, null)
}
