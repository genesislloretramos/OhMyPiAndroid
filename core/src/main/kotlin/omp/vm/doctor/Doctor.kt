package omp.vm.doctor

import omp.shell.PlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateOutcome
import omp.vm.guestapi.UpdateRecord
import omp.vm.provision.Abi
import omp.vm.provision.Artifact
import omp.vm.provision.ArtifactManifest
import omp.vm.provision.GuestOriginRecord
import omp.vm.provision.GuestState
import omp.vm.provision.GuestWeb
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionStatus
import omp.vm.provision.ProvisionStatusHolder
import omp.vm.provision.WebRoot
import omp.vm.provision.readRecord
import omp.vm.provision.recordFile
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Everything this device can be asked about before it is asked, in the order a person needs it.
 *
 * ### Why this command exists
 *
 * The provisioning layer downloads a Debian and a 224 MB agent, a native helper is packaged into
 * the APK for four ABIs, and **no build of this app has ever run that helper on any device**. A
 * user who installs this on a real phone and finds that it does not work has, until this command,
 * no way at all to find out why — and the failure modes are specific and numerous: the wrong ABI,
 * a missing all-files grant, a helper the package manager never extracted, an exec directory that
 * does not exist, a full disk, a half-finished download, a rootfs that unpacked without a loader in
 * it, a token that does not match. This is the thing that turns "it does not work" into a bug
 * report, and a developer holding a phone will run it before anything else.
 *
 * ### The four promises this class makes
 *
 * 1. **Read-only.** No download is started, no file is created, written, renamed or deleted, and
 *    the only socket is **one** connect attempt against loopback with a 400 ms timeout, made once
 *    and read by two sections. A connect and not a request: a connect that succeeds means a
 *    listener is there whatever it is, which is the whole of the question.
 * 2. **Every value is read, not remembered.** The uid comes from the platform, the paths from
 *    [Vfs], the byte counts from `stat`, the cost from the manifest this build carries. No default
 *    is ever printed as if it had been measured.
 * 3. **A fact that could not be read is named.** It appears as `unreadable: <reason>` in place and
 *    again in the gaps section — never as silence and never as a value that looks healthy. A
 *    missing line and a healthy line must not be the same shape on a screen.
 * 4. **The advice follows from the state that was just read.** Each line is traceable to a line
 *    above it, and a suggestion that does not follow from a measurement is not printed.
 *
 * ### What it deliberately does not do
 *
 * **It does not execute the helper, and the closing line says so.** Everything above is a
 * measurement about a filesystem and a platform; the failure that comes next, on a device where
 * every line reads healthy, is the kernel refusing to `execve` a file out of the exec directory,
 * or a proot build that will not accept the flags [omp.vm.provision.ProotCommand] builds. "The
 * helper is there" is not "the guest will boot", and a diagnostic that implied otherwise would
 * cost more than it saves.
 *
 * ### Why it reads host paths
 *
 * Every path here is a **host** path — the payload under `appFilesDir()` and the exec directory
 * under `/data/app` — and the payload is not in the VM's mount table at all. A `doctor` run inside
 * the namespace therefore answers `unreadable: No such file or directory` on every line, which is
 * the truth about that namespace and the reason the command is worth running on the phone.
 *
 * @param guestPort the port Apache inside the Debian is told to answer on — [omp.vm.provision.GuestWeb.RESERVED_PORT],
 *   which is the same number [omp.vm.provision.GuestStart] checks, records and hands to
 *   [com.omp.terminal.web.UiOrigins]. **It is a parameter and not a constant read here because
 *   `:core` cannot see `:app`, and the two must not be able to drift**: it is one number, named in
 *   one place, and this report prints it on a line of its own so a reader can check that what the
 *   screen was handed and what the record says are the same port.
 * @param probe the connect check, as a parameter so a JVM test can answer both ways without a
 *   socket and so the shape of the question is data rather than a call buried in a method. **It
 *   asks about the app's own loopback port and nothing else**: whether the guest is answering is
 *   read from the record [omp.vm.provision.GuestStart] left, because this command is read-only and
 *   a second socket would break the promise above.
 */
class Doctor(
    private val services: PlatformServices,
    private val vfs: Vfs,
    private val paths: ProvisionPaths = ProvisionPaths.inAppStorage(services),
    private val guestPort: Int = GuestWeb.RESERVED_PORT,
    private val probe: Probe = Probe.LOOPBACK,
    /**
     * `System.getProperty("os.arch")`, which [omp.vm.provision.Abi.detect] falls back to when the
     * platform named no list. A parameter because it is a JVM property and a test cannot change one:
     * a device whose architecture this build does not know is a real case worth a report, and it is
     * the only way to reach it from a test.
     */
    private val osArch: String? = System.getProperty("os.arch"),
) {

    /** What one [report] read, and every line that says so. */
    data class Report(
        val lines: List<String>,
        /** The gaps alone, so a test can pin them without pinning the whole report. */
        val gaps: List<String>,
    ) {
        /** The report as one string, which is the shape a bug report is pasted in. */
        fun text(): String = lines.joinToString("\n") + "\n"
    }

    /**
     * Whether something is accepting connections on a loopback port.
     *
     * **A connect, not a request**, and the same 400 ms `com.omp.terminal.android.WebCommand`
     * uses. `:core` cannot call a private function in `:app`, and a `GET /` would prove the server
     * is alive *and* that it is the server, at the price of a route call for a question a shell
     * should answer with one syscall.
     */
    fun interface Probe {

        /** Whether a connect to `127.0.0.1:port` completed inside [PROBE_MS]. */
        fun accepts(port: Int): Boolean

        companion object {
            /** The one socket this command is allowed to open, and it opens it once. */
            val LOOPBACK = Probe { port ->
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_MS) }
                    true
                } catch (e: IOException) {
                    // A refused connect, a timed-out connect and a connect to a port nothing holds
                    // are one answer to this question, and nothing else is worth distinguishing.
                    false
                }
            }
        }
    }

    /**
     * The whole report: the ten sections in a fixed order, then the closing line.
     *
     * The order is the order the questions are asked in — what this device is, where the bytes
     * would go, whether the helper is there, what has been fetched, what the guest holds, what the
     * boot's `omp update` did to the agent in it, whether the guest is serving anything, what the
     * screen is showing, what could not be read, what to type — and it never varies, because a
     * report that reorders itself cannot be diffed between two runs.
     */
    fun report(): Report {
        val out = Out()
        val chat = Chat.of(probeResult(), loopbackBase())
        out.say("$PREFIX: read-only — nothing below downloads, writes, starts or stops anything")
        out.say("")
        identity(out)
        out.say("")
        guest(out)
        out.say("")
        helper(out)
        out.say("")
        provisioning(out)
        out.say("")
        guestState(out)
        out.say("")
        agentUpdate(out)
        out.say("")
        guestOrigin(out)
        out.say("")
        chat(out, chat)
        out.say("")
        gaps(out)
        out.say("")
        next(out, chat)
        out.say("")
        // Indented, because an unindented line here would be indistinguishable from a section head
        // and this report's whole contract is that a reader can tell what kind of line it is on.
        for (line in CANNOT) out.say("  $line")
        return Report(out.lines, out.gaps)
    }

    // ---- identity -----------------------------------------------------------------------------

    /**
     * Who is asking, and on what.
     *
     * **The version is read out of the installed package's own path**, because that is where
     * Android puts it: `/data/app/~~<hash>/<package>-<versionCode>==/base.apk`. A build constant
     * would be a number this file made up about the app in the user's hand, and the whole point of
     * this command is that it does not do that. A path that does not carry one says so.
     */
    private fun identity(out: Out) {
        out.section("identity")
        val installed = services.packagePaths(services.processName()).firstOrNull()
        out.pair("app", services.processName())
        out.pair(
            "version",
            when {
                installed == null -> out.gap("the platform has named no installed package for ${services.processName()}")
                else -> versionOf(installed) ?: out.gap("$installed does not carry a version code in its name")
            },
        )
        out.pair("installed at", installed ?: out.gap("the platform has named no installed package"))
        out.pair(
            "build",
            services.buildProperties()[FINGERPRINT]?.takeIf { it.isNotBlank() }
                ?: out.gap("no $FINGERPRINT in the build properties this build can read"),
        )
        out.pair("uid", services.appUid().toString())
        out.pair("gid", services.appGid().toString())
        val abi = abi()
        if (abi == null) {
            val said = services.buildProperties()[ABILIST] ?: "no $ABILIST at all"
            out.pair("abi", out.gap("no ABI this app knows in $said"))
            out.pair("debian arch", out.gap("no ABI, so no Debian architecture name to derive"))
        } else {
            out.pair("abi", abi.abiName)
            out.pair("debian arch", abi.debianArch)
        }
        val manifest = abi?.let { ArtifactManifest.of(it) }
        out.pair(
            "real agent",
            when {
                abi == null || manifest == null -> out.gap("no manifest without a known ABI, so nothing to offer")
                manifest.agent != null ->
                    "obtainable on ${abi.abiName}: ${manifest.agent.name}, " +
                        ArtifactManifest.humanBytes(manifest.agent.sizeBytes)
                else -> "not obtainable on ${abi.abiName}: ${abi.agentRefusal}"
            },
        )
    }

    // ---- the two directories -------------------------------------------------------------------

    /**
     * Where the bytes go, and whether there is room for them.
     *
     * **The space check is answered here, live, with the manifest's own two numbers** — the wire
     * bytes and the room the install needs — rather than being deferred to whoever asks to
     * provision. A disk that is short now is a common reason a 293 MB download stops half way, and
     * a check made only at provisioning time says so after the data has been spent.
     */
    private fun guest(out: Out) {
        out.section("guest")
        out.pair("payload", directory(out, paths.targetDir))
        out.pair(
            "exec dir",
            paths.execDirectory?.let { directory(out, it) }
                ?: out.gap("the platform named no native library directory, so there is no exec directory to look in"),
        )
        val manifest = abi()?.let { ArtifactManifest.of(it) }
        if (manifest == null) {
            out.pair("wire", out.gap("no manifest without a known ABI, so no download cost to quote"))
            out.pair("room", out.gap("no manifest without a known ABI, so no install cost to quote"))
            out.pair("space check", out.gap("nothing to check the free space against"))
            return
        }
        out.pair("wire", ArtifactManifest.humanBytes(manifest.totalBytes))
        out.pair("room", ArtifactManifest.humanBytes(manifest.requiredBytes))
        val free = try {
            vfs.diskUsage(paths.targetDir).freeBytes
        } catch (e: FsException) {
            null
        }
        out.pair(
            "space check",
            when {
                free == null -> out.gap(
                    "the filesystem holding ${paths.targetDir} would not report its free space, so the " +
                        "check a provisioner makes before its first byte cannot be answered here",
                )
                free >= manifest.requiredBytes ->
                    "would pass: ${ArtifactManifest.humanBytes(free)} is free and the manifest asks for " +
                        ArtifactManifest.humanBytes(manifest.requiredBytes)
                else ->
                    "would refuse: ${ArtifactManifest.humanBytes(free)} is free and the manifest asks for " +
                        ArtifactManifest.humanBytes(manifest.requiredBytes)
            },
        )
    }

    // ---- the helper ----------------------------------------------------------------------------

    /**
     * Which of the packaged files are in the exec directory, and how big each one is.
     *
     * **This is the most useful section in the command, because it is the thing most likely to be
     * wrong on a first install.** `android:extractNativeLibs="true"` is what makes the package
     * manager copy `lib/<abi>/…` out of the APK into `nativeLibraryDir` at install time, and when
     * it has not — a build that forgot it, an OEM that ignores it, a directory a cleaner emptied —
     * the symptom is a directory that exists and is empty, which from a shell looks exactly like a
     * device with no proot in it at all.
     *
     * The names are [EXPECTED]'s, keyed by ABI, and they are the names in
     * `app/src/main/jniLibs/<abi>/`. `libproot-loader32.so` is in the two 64-bit directories only,
     * because the 32-bit ports exec a 32-bit loader for a 32-bit guest and this app never has one.
     */
    private fun helper(out: Out) {
        out.section("helper")
        val abi = abi()
        if (abi == null) {
            out.pair("expected", out.gap("no ABI, so no packaged set of helper files to look for"))
            return
        }
        val exec = paths.execDirectory
        if (exec == null) {
            out.pair("expected", out.gap("no exec directory: the platform named no native library directory"))
            return
        }
        val expected = EXPECTED.getValue(abi)
        out.pair("expected", expected.joinToString(", "))
        var found = 0
        for (name in expected) {
            val size = try {
                vfs.stat("$exec/$name").size
            } catch (e: FsException) {
                out.pair(name, out.gap("not in $exec (${e.errno.text})"))
                continue
            }
            found++
            out.pair(name, ArtifactManifest.humanBytes(size))
        }
        out.pair(
            "verdict",
            when {
                found == 0 ->
                    "none of the ${expected.size} packaged files is in $exec: the package manager did not " +
                        "extract them, and android:extractNativeLibs=\"true\" is the attribute that makes it"
                found < expected.size ->
                    "$found of ${expected.size} are there; the kernel execs proot and the loader, so one of " +
                        "those two being absent is what stops the guest"
                else -> "all ${expected.size} are there, which is as far as this build can check"
            },
        )
    }

    // ---- provisioning --------------------------------------------------------------------------

    /**
     * What has been fetched, off the state file and off the real `.part` files.
     *
     * **The `.part` file's own length is the resume offset** — it is what
     * [omp.vm.provision.Provisioner] asks the server for a `Range` from — so it is also the only
     * honest answer to "how far did it get". The state file beside it records what the last
     * attempt said; it is a record of what happened and not the thing that makes resuming
     * possible, and losing it costs a re-stat.
     *
     * **The phase is derived, never invented.** A short `.part` means the download stopped where it
     * was. A full one with nothing installed means the bytes arrived and the unpack has not left
     * its mark, and the report says those two steps are not distinguishable rather than picking one.
     */
    private fun provisioning(out: Out) {
        out.section("provisioning")
        val manifest = abi()?.let { ArtifactManifest.of(it) }
        val records = paths.records(vfs)
        if (manifest == null) {
            out.pair("state file", describe(paths.stateFile))
            out.pair("artifacts", out.gap("no manifest without a known ABI, so nothing this build would download"))
            out.pair("last attempt", describeAttempt(records))
            out.pair("phase", out.gap("no artifacts, so no phase"))
            return
        }
        out.pair("state file", describe(paths.stateFile))
        val state = paths.state(vfs)
        val artifacts = listOfNotNull(manifest.rootfs, manifest.agent)
        var partials = 0
        for (artifact in artifacts) {
            val rootfs = artifact === manifest.rootfs
            val installed = if (rootfs) state.rootfsInstalled else state.agentInstalled
            val partial = partialOf(artifact)
            if (partial != null && partial > 0L && !installed) partials++
            val at = if (rootfs) paths.rootfsDir else paths.agentBinary
            out.pair(artifact.name, describeArtifact(artifact, installed, partial, at))
        }
        if (artifacts.isEmpty()) {
            out.pair("artifacts", "none: ${manifest.gap}")
        }
        out.pair("last attempt", describeAttempt(records))
        val status = ProvisionStatusHolder.current
        out.pair("last run", describeRun(status))
        out.pair(
            "phase",
            when {
                status.running -> status.line() ?: "a run is in progress and has not reported a step yet"
                partials > 0 ->
                    "downloading: $partials artifact(s) have a .part file, and the next 'omp provision' " +
                        "resumes each from that file's own length"
                state.realAgentInstalled -> "installed: nothing in progress"
                else -> "nothing in progress, and nothing is installed"
            },
        )
    }

    /**
     * One artifact's real state: absent, partial with a byte count, or complete.
     *
     * "Complete" is read off a marker inside the tree or the installed binary — the same two facts
     * [omp.vm.provision.Provisioner.run] decides by — and never out of the state file, which a
     * user, a backup or a `vm reset` can take away without touching 293 MB of anybody's data.
     */
    private fun describeArtifact(artifact: Artifact, installed: Boolean, partial: Long?, at: String): String {
        if (installed) return "complete, unpacked at $at"
        if (partial != null && partial > 0L) {
            return if (partial < artifact.sizeBytes) {
                "partial, ${ArtifactManifest.humanBytes(partial)} of ${ArtifactManifest.humanBytes(artifact.sizeBytes)}; the next run continues from there"
            } else {
                "the whole ${ArtifactManifest.humanBytes(artifact.sizeBytes)} is on disk and nothing is " +
                    "unpacked, so the last attempt stopped at the verify or the unpack step and this " +
                    "build cannot tell you which"
            }
        }
        return "absent: no ${paths.partial(artifact.name)} and nothing installed"
    }

    /** The `.part` file's length, or null when there is no such file. */
    private fun partialOf(artifact: Artifact): Long? = try {
        vfs.stat(paths.partial(artifact.name)).size
    } catch (e: FsException) {
        null
    }

    /** The last recorded attempt, or the absence of one, named. */
    private fun describeAttempt(records: Map<String, String>): String = when (records.size) {
        0 -> "no record at ${paths.stateFile}: nothing has been downloaded by this build"
        1 -> records.entries.first().let { "${it.key}, recorded as '${it.value}'" }
        else -> records.entries.joinToString("; ") { "${it.key} '${it.value}'" } + " (${records.size} records)"
    }

    /**
     * The last `omp provision` run this process saw, or the absence of one.
     *
     * **It is in memory and does not survive a restart**, which is why the line says so. A device
     * that rebooted mid-download has no recorded outcome here, and the disk-derived line above is
     * then the only evidence there is. Saying which of the two is which is the difference between a
     * fact and a guess about a fact.
     */
    private fun describeRun(status: ProvisionStatus): String {
        if (!status.running && status.outcome == null) {
            return "none recorded in this app's memory, which does not survive a restart; the state " +
                "file and the .part files above are the only evidence there is"
        }
        val where = if (status.running) "running now" else "ended as ${status.outcome}"
        val artifact = status.artifact?.let { ", on $it" }.orEmpty()
        val bytes = if (status.totalBytes > 0L) {
            ": ${ArtifactManifest.humanBytes(status.receivedBytes)} of " +
                ArtifactManifest.humanBytes(status.totalBytes)
        } else {
            ""
        }
        return "$where$artifact$bytes"
    }

    // ---- the guest's own state -----------------------------------------------------------------

    /**
     * What is inside the Debian, which is a different question from whether it was downloaded.
     *
     * **The three page files are compared against this build's own bytes**, read out of the
     * installed APK, because "the document root exists" and "the document root is this build's
     * chat UI" are different facts and only the second is worth anything to a user looking at a
     * blank page. A file whose bytes differ prints both lengths, so the difference is visible
     * rather than asserted.
     */
    private fun guestState(out: Out) {
        out.section("guest state")
        val state = paths.state(vfs)
        out.pair(
            "rootfs",
            if (state.rootfsInstalled) {
                "unpacked, ${ProvisionPaths.ROOTFS_MARKER} is in ${paths.rootfsDir}"
            } else {
                "not unpacked: no ${ProvisionPaths.ROOTFS_MARKER} in ${paths.rootfsDir}"
            },
        )
        out.pair(
            "agent",
            if (state.agentInstalled) "installed at ${paths.agentBinary}" else "not installed at ${paths.agentBinary}",
        )
        if (!state.rootfsInstalled) {
            out.pair("web root", out.gap("there is no unpacked Debian, so ${paths.webRoot} is not a document root yet"))
            out.pair("lamp", out.gap("there is no unpacked Debian, so nothing can have been installed into it"))
            return
        }
        val pages = BuildPages.of(vfs, services.packagePaths(services.processName()).firstOrNull())
        out.pair("web root", "installed at ${paths.webRoot}")
        if (pages.refusal != null) {
            out.pair("web bytes", out.gap("this build's own three pages could not be read: ${pages.refusal}"))
        } else {
            out.pair("web bytes", comparePages(pages))
        }
        out.pair(
            "lamp",
            if (state.guestInstalled) {
                "installed, ${ProvisionPaths.GUEST_MARKER} is in ${paths.rootfsDir}"
            } else {
                "not installed: no ${ProvisionPaths.GUEST_MARKER} in ${paths.rootfsDir}"
            },
        )
    }

    /**
     * How many of the three pages are this build's, and what is wrong with the ones that are not.
     *
     * Every verdict names the file, so a user with one stale page is told which one — the failure
     * a user actually hits is Debian's own `apache2` package overwriting `index.html` at `apt`
     * time, and that is one file out of three.
     */
    private fun comparePages(pages: BuildPages): String {
        var same = 0
        val details = ArrayList<String>()
        for (name in WebRoot.FILES) {
            val here = try {
                vfs.readBytes("${paths.webRoot}/$name")
            } catch (e: FsException) {
                null
            }
            val mine = pages.read(name)
            when {
                here == null -> details += "$name is not there"
                mine == null -> details += "$name is ${ArtifactManifest.humanBytes(here.size.toLong())} here and this build has no copy of it to compare"
                here.contentEquals(mine) -> {
                    same++
                    details += "$name is this build's own copy"
                }
                else -> details += "$name is ${ArtifactManifest.humanBytes(here.size.toLong())} here and " +
                    "${ArtifactManifest.humanBytes(mine.size.toLong())} in this build: different bytes"
            }
        }
        return "$same of ${WebRoot.FILES.size} are this build's own copy — ${details.joinToString("; ")}"
    }

    // ---- the agent's own version, and the boot's attempt at it ------------------------------------

    /**
     * What the boot's `omp update` did to the agent in the guest, which is the answer to "why is the
     * agent in my guest old?" asked the same way as "why did it not work?".
     *
     * **It reads one file and nothing else.** [omp.vm.guestapi.AgentUpdate] writes that file at
     * every start of the app — bounded, unattended, with nobody there to be asked — so a boot that
     * could not update the agent leaves a record instead of a silence, and this is where the record
     * is read. The read-only promise holds: this section starts nothing, downloads nothing and
     * writes nothing, exactly like every other one.
     *
     * **The four situations a user actually has, and the line each one gets.** A device that has
     * never started the app, a device whose update never ran because there is no guest to update,
     * a device whose update ran and succeeded, and a device whose update ran and failed. They are
     * four different sentences and not one with the middle cases blanked out, because the whole
     * point of this command is that a person reads it and can tell which of the four they are
     * looking at without knowing which one to expect.
     */
    private fun agentUpdate(out: Out) {
        out.section(SECTION_UPDATE)
        val at = AgentUpdate.recordFile(paths)
        out.pair("record", describe(at))
        val record = AgentUpdate.read(vfs, paths)
        if (record == null) {
            out.pair("outcome", "never: no record at $at, so this build has not run 'omp update' in a guest on this device")
            out.pair("version", "none: nothing has run in a guest, so no version of the agent in one has been measured")
            out.pair("last run", "none: the update runs at every start of the app, and only when the Debian and its agent are both on this device")
            return
        }
        val named = record.outcomeName
        val outcome = record.outcome
        out.pair(
            "outcome",
            when {
                outcome != null -> "${outcome.name}: ${outcomeSentence(outcome, record)}"
                named != null -> out.gap(
                    "the record at $at names an outcome '$named', which is not one of this build's " +
                        "UpdateOutcome (${UpdateOutcome.entries.joinToString(", ") { it.name }}), so this " +
                        "build cannot say what happened",
                )
                else -> out.gap("the record at $at has no '${AgentUpdate.KEY_OUTCOME}' line, so what it did is not in it")
            },
        )
        out.pair("version", versionSentence(outcome, record))
        out.pair("last run", lastRunSentence(outcome, record))
    }

    /**
     * The outcome's own sentence, after its name.
     *
     * **The name leads because it is the named set, in the enum's own spelling**, which is what the
     * record file holds and therefore what a grep for `FAILED` finds; the sentence after it is what
     * a person reads, and the guest's own last line is quoted into it verbatim because the reason
     * the agent did not move is the guest's to give and not this build's to paraphrase.
     */
    private fun outcomeSentence(outcome: UpdateOutcome, record: UpdateRecord): String = when (outcome) {
        UpdateOutcome.NOT_PROVISIONED ->
            "'omp update' did not run, because there is no unpacked Debian on this device to run it in"
        UpdateOutcome.NO_AGENT ->
            "'omp update' did not run, because there is no agent in the Debian on this device to update"
        UpdateOutcome.ALREADY_CURRENT ->
            "'omp update' ran and said the agent was already current, so nothing was downloaded"
        UpdateOutcome.UPDATED ->
            "'omp update' ran, exited ${record.status} and did not say the agent was already current, " +
                "so it installed something"
        UpdateOutcome.FAILED ->
            "'omp update' ran and exited ${record.status}, and the agent on this device is unchanged"
        UpdateOutcome.TIMED_OUT ->
            "'omp update' was still running at the bound and was stopped, and what it had done by then " +
                "is not established"
    } + saidOf(record)

    /**
     * The version line, and the honest limit of it.
     *
     * **The number is the guest's own report of itself, not this build's guess at it**, and the
     * `was` line is the before-and-after a user is asking for: it is what the run before this one
     * reported, so a device whose agent moved between two boots shows both numbers without this
     * build ever having run an install to find out. What a run that installs leaves behind is not
     * established by this build, and the line says so rather than implying the newer number is
 * *after* the update.
     */
    private fun versionSentence(outcome: UpdateOutcome?, record: UpdateRecord): String {
        val version = record.version
        val was = record.was
        if (version == null) {
            return "none: the guest printed no '${AgentUpdate.VERSION_LINE}' line" +
                if (outcome == null || outcome == UpdateOutcome.NOT_PROVISIONED || outcome == UpdateOutcome.NO_AGENT) {
                    ", because nothing ran"
                } else {
                    ", and this build cannot say what version the agent in the guest is"
                }
        }
        val moved = if (was != null) ", and $was is what the run before it reported" else ""
        val tail = if (outcome == UpdateOutcome.UPDATED) {
            "; what a run that installs leaves behind is not established by this build"
        } else {
            ""
        }
        return "$version is what the guest reported for itself$moved$tail"
    }

    /** The guest's own last line from that run, quoted, and never a line this build wrote. */
    private fun saidOf(record: UpdateRecord): String =
        record.said?.let { "; its own last line was \"$it\"" } ?: "; it wrote no line of its own"

    /**
     * When, what status and what bound — the three numbers that make two reports diffable.
     *
     * **The bound is printed even when it was never reached**, because a reader comparing two boots
     * is looking at whether the wait changed and not only at what came back.
     */
    private fun lastRunSentence(outcome: UpdateOutcome?, record: UpdateRecord): String {
        val at = record.at
        val when_ = if (at == null) "at no recorded time" else "at $at"
        // Only a stopped run is *expected* to come back with no status. A record without one that
        // says it never ran was written by something other than this build, and saying so is the
        // whole point of the line.
        val status = record.status?.let { "status $it" } ?: when (outcome) {
            UpdateOutcome.TIMED_OUT -> "no status, which is what a run that was stopped leaves"
            else -> "no status recorded, and this build cannot say what it was"
        }
        val bound = record.bound?.let { "bound ${it}ms" } ?: "no bound recorded, which this build cannot explain"
        return "$when_, $status, $bound"
    }

    // ---- the guest's own origin, and whether it is answering --------------------------------------

    /**
     * "Is the guest up, and is it the one answering" — on one screen, from the record and nothing
     * else.
     *
     * **It reads a file, and it opens no socket.** The one connect in this command is against the
     * app's own loopback port and is made once, for the `chat` section below; a second probe of the
     * guest's port would break the read-only promise this class opens with, and it would also be the
     * wrong question — the fact that matters is not "is something listening" but "is the thing this
     * app started listening", and only the record knows the second.
     *
     * ### Why this is its own section and not three more lines in `chat`
     *
     * Because the question spans both ends. `chat` answers "what is the screen being handed", which
     * is one line; this answers "what happened when the guest was started", which is a state, a port
     * and an agent outcome, and a user asking "why am I looking at the Kotlin agent" needs all three
     * together. The section sits between `agent update` and `chat` for the reason the `agent update`
     * section sits where it does: it is about the guest's runtime, which comes after what the boot
     * did to the agent and before what the screen is showing.
     *
     * ### The six states, and the five lines a user can be in
     *
     * | `state:` | what the person holding the phone has |
     * |---|---|
     * | `NO_DEBIAN` | nothing to start; the app's own server is the chat |
     * | `NOT_STARTED` | a Debian is on the device and this build has not started it, and it says which precondition stopped it |
     * | `PORT_TAKEN` | something else on the phone holds the guest's port, so the guest's origin was **refused**, not replaced |
     * | `APACHE_NOT_ANSWERING` | it was started and no page came back, and the guest's last line is quoted |
     * | `AGENT_UPDATE_FAILED` | the Debian is serving and its agent is the one that was already there |
     * | `UP` | the Debian is serving and the boot's `omp update` landed |
     *
     * **A device with no record is not a gap and not a silence.** It is every device before its
     * first start of the guest, and the state it reports is derived from the disk rather than
     * invented: no unpacked Debian is [GuestState.NO_DEBIAN] and a Debian with no start record is
     * [GuestState.NOT_STARTED]. Those are answers about the device, and a gap is a fact this run
     * failed to read.
     */
    private fun guestOrigin(out: Out) {
        out.section(SECTION_ORIGIN)
        val state = paths.state(vfs)
        val record = readRecord(vfs, paths)
        val port = record?.port ?: guestPort
        val named = record?.stateName
        val known = record?.state
        if (record != null && known == null) {
            out.pair(
                "state",
                out.gap(
                    "the record at ${recordFile(paths)} names a state '$named', which is not one of " +
                        "this build's GuestState " +
                        "(${GuestState.entries.joinToString(", ") { it.name }}), so this build cannot " +
                        "say what the last start of the guest did",
                ),
            )
        } else {
            val effective = known
                ?: if (state.rootfsInstalled) GuestState.NOT_STARTED else GuestState.NO_DEBIAN
            out.pair("state", "${effective.name}: ${effective.meaning(port)}")
        }
        out.pair("port", originPortLine(record, port))
        out.pair("apache", originApacheLine(record, port))
        out.pair("agent", originAgentLine(record))
    }

    /** The port, and what the run that checked it found. Never the port alone. */
    private fun originPortLine(record: GuestOriginRecord?, port: Int): String = when {
        record == null ->
            "$port, the port this build reserves for Apache inside the Debian, and nothing has " +
                "tried to reserve it"
        record.reserved == true ->
            "$port, reserved by the run that started the guest: it was free, and it was given back " +
                "so Apache could take it"
        record.reserved == false ->
            "$port, and the run that started the guest could not reserve it: something on this phone " +
                "already held it, so Apache was never started on it"
        else ->
            "$port, named by the record at ${recordFile(paths)}, which records no answer about " +
                "whether it was free"
    }

    /**
     * What the *start* found on the guest's port.
     *
     * **Every clause is in the past tense on purpose.** This command asked nothing: a line reading
     * "something is listening on 8732" in a section about a read-only report would be a claim about
     * the moment the report was printed, and the only moment anything established is the one the
     * record was written in.
     */
    private fun originApacheLine(record: GuestOriginRecord?, port: Int): String {
        if (record == null) {
            return "not asked: this build has not started the guest on this device, and this command " +
                "starts nothing and asks nothing to find out"
        }
        val said = record.said?.let { "; the guest's own last line was \"$it\"" } ?: ""
        return when {
            record.answered == true ->
                "answering: the start asked ${GuestWeb.baseUrl(port)} for this build's own chat " +
                    "document and got it back$said"
            record.launched == true ->
                "not answering: the start launched apache2 inside the Debian and nothing returned " +
                    "this build's chat document from ${GuestWeb.baseUrl(port)}$said"
            else -> "not started: the start launched nothing inside the Debian$said"
        }
    }

    /**
     * The agent in the guest, as the run that started it left it, and a pointer to the section that
     * has what the guest printed.
     *
     * **A pointer and not a second copy.** The `agent update` section above is the one place the
     * guest's own words and the record's six fields are rendered, and a second rendering of the same
     * run is a second thing that can be wrong about it. What this line adds is the one fact the
     * other cannot have: whether that update and the start of the web server were the same run.
     */
    private fun originAgentLine(record: GuestOriginRecord?): String {
        if (record == null) {
            return "not run: this build has not started the guest on this device, so there is no " +
                "boot's 'omp update' from it to report; the 'agent update' section above is where " +
                "any other run of it is read"
        }
        val name = record.update
            ?: return "not run: the run that started the guest did not reach 'omp update', and the " +
                "'agent update' section above is where any other run of it is read"
        val outcome = UpdateOutcome.entries.firstOrNull { it.name == name }
            ?: return "unreadable: the record at ${recordFile(paths)} names an agent outcome " +
                "'$name', which is not one of this build's UpdateOutcome " +
                "(${UpdateOutcome.entries.joinToString(", ") { it.name }}), so this build cannot say " +
                "what the agent in the guest is"
        return if (outcome == UpdateOutcome.UPDATED || outcome == UpdateOutcome.ALREADY_CURRENT) {
            "$name: the boot's 'omp update' landed at the same start, so the agent the Debian's page " +
                "is talking to is the one it was brought up to; the 'agent update' section above has " +
                "what it printed"
        } else {
            "$name: the agent the Debian's page is talking to is the one that was already there, " +
                "because `omp update && omp` stopped at the first half — the page is the Debian's all " +
                "the same; the 'agent update' section above has what it printed"
        }
    }

    // ---- the chat ------------------------------------------------------------------------------

    /**
     * The one probe, read once and shared by [chat] and [next].
     *
     * **The port is read off the file the service wrote**, not from a constant, because a service
     * that could not bind its preferred port takes an ephemeral one and a port printed from a
     * constant would be a port that might not be the one listening. The token's *size* is reported
     * and its bytes never are: a doctor report gets pasted into a bug, and a bug is somewhere a
     * credential should not be.
     */
    private class Chat private constructor(
        /** The probed port and the answer, or null when no port could be read at all. */
        val probed: Pair<Int, Boolean>?,
        /** Why there is no port, when there is none. Never null when [probed] is null. */
        val noPort: String?,
    ) {
        companion object {
            /**
             * One probe, one result, and the reason when there was nothing to probe.
             *
             * **Both sections read this one object**, so the chat section and the advice below can
             * never disagree about whether the server is up — and the socket is opened once, which
             * is what the read-only promise is about.
             */
            fun of(probed: Pair<Int, Boolean>?, base: String?): Chat {
                if (probed != null) return Chat(probed, null)
                val why = when {
                    base == null -> "the chat service has published no url at all, so there is no port to probe"
                    else -> "the published url '$base' names no port"
                }
                return Chat(null, why)
            }
        }
    }

    private fun probeResult(): Pair<Int, Boolean>? {
        val port = loopbackBase()?.let { portOf(it) } ?: return null
        return port to probe.accepts(port)
    }

    /**
     * Which origin the screen is being handed, by name, and whether anything is listening on it.
     *
     * **The rule is the app's, not this class's**: `UiOrigin.choose` shows the guest's Apache when a
     * whole rootfs and the real agent are both on the device *and* the guest has been seen to serve
     * a page, and shows the app's own loopback server otherwise. Both branches are real and neither
     * is a fallback in the sense of being embarrassing, so the line names the one in force and the
     * reason in the same breath.
     *
     * **The guest branch is gated on `answered`, and that is the whole of what stops the one lie this
     * report must not tell.** A build that named a port for Apache and launched the guest would
     * otherwise print "the guest's Apache" on a phone where nothing had ever answered on that port —
     * telling a user the real agent is serving them when the Kotlin one is. The `guest origin`
     * section above says which of the named states it is in; this line only says which origin that
     * puts on the screen.
     *
     * **It also needs a state this build *has*, and that is deliberately the conservative reading.**
     * A record written by a newer build that names a state this one does not have is reported as
     * unreadable above, and a WebView is not handed a port on the strength of a key from a record
     * whose meaning this build cannot vouch for: the app's own server is shown and the line names
     * the record. The other half of the rule, the one this whole path exists for, is that the guest
     * is never quietly replaced by the app's own server — the reason is always on this line.
     */
    private fun chat(out: Out, chat: Chat) {
        out.section("chat")
        val state = paths.state(vfs)
        val record = readRecord(vfs, paths)
        val guest = state.realAgentInstalled && record?.state?.serving == true
        out.pair(
            "origin",
            if (guest) {
                "the guest's Apache inside the Debian: a whole rootfs and the real agent are both on " +
                    "this device, and the run that started the guest saw this build's own chat " +
                    "document come back from ${GuestWeb.baseUrl(record?.port ?: guestPort)}"
            } else {
                "this app's own loopback server: " + when {
                    !state.realAgentInstalled ->
                        "a whole rootfs and the real agent are not both on this device"
                    record == null ->
                        "this build has not started the guest on this device, and a guest that was " +
                            "only launched is never shown as though it were answering"
                    else -> "Apache inside the Debian is not answering: " + (
                        record.state?.name
                            ?: "the run that started the guest recorded a state this build does not have"
                        )
                }
            },
        )
        val answered = chat.probed
        if (answered == null) {
            out.pair("port", out.gap(chat.noPort!!))
            out.pair("listening", out.gap("there is no port to probe, so nothing was asked of the network"))
            return
        }
        val (port, up) = answered
        out.pair("port", "$port, from ${serviceFile(URL_FILE)}")
        out.pair(
            "listening",
            if (up) {
                "yes, something accepted a connection on 127.0.0.1:$port inside ${PROBE_MS}ms"
            } else {
                "no, nothing accepted a connection on 127.0.0.1:$port inside ${PROBE_MS}ms"
            },
        )
        out.pair("token", describe(serviceFile(TOKEN_FILE)) + " — read, never printed by this command")
    }

    // ---- gaps ----------------------------------------------------------------------------------

    /**
     * Everything this run could not establish, named, and nothing that merely went badly.
     *
     * **A gap is a fact this run failed to read**, not a fault in the device: a directory the
     * filesystem will not open, a page this build does not have, an ABI nothing here knows. A
     * device in a bad state says so in the section it belongs to — `would refuse` for a full disk,
     * `not installed` for a missing agent — and those are answers, not gaps. The distinction is
     * the whole point: a user must never read an absent line as a healthy one.
     */
    private fun gaps(out: Out) {
        out.section("gaps")
        if (out.gaps.isEmpty()) {
            out.say("  $NONE_READ every fact above was read from this device")
            return
        }
        for (gap in out.gaps) out.say("  $gap")
    }

    // ---- what to do next -----------------------------------------------------------------------

    /**
     * At most three lines, and every one of them a command that exists and follows from a line
     * above it.
     *
     * **`omp provision` appears only when it would do something**: the guest is absent, this ABI
     * has artifacts at all, and the space check above says the room is there. A device that is
     * already provisioned, or one that would be refused on room, is not told to run a 334 MB
     * download — and advice that does not follow from what was just read is not advice.
     */
    private fun next(out: Out, chat: Chat) {
        out.section("next")
        val said = ArrayList<String>()
        if (!services.isExternalStorageManager()) {
            said += "$GRANT this app does not hold \"All files access\", which Documents/omp needs"
        }
        if (chat.probed?.second == true) {
            said += "$WEB the agent's web front end is up on 127.0.0.1:${chat.probed.first}; " +
                "'web' prints that url and this install's token"
        }
        provisionAdvice()?.let { said += it }
        if (said.isEmpty()) {
            out.say("  $NOTHING_TO_RUN nothing in the state just read is waiting on a command")
            return
        }
        for (line in said) out.say("  $line")
    }

    /**
     * What to do about the guest, or null when there is nothing to do.
     *
     * **The three cases are three different sentences and not one with the middle case blanked
     * out.** No artifact for this ABI is a permanent upstream gap and `omp provision` refuses it;
     * a full disk is a refusal for now; only the third is a command that would do something. A
     * device that is already provisioned gets nothing here at all, because there is no work.
     */
    private fun provisionAdvice(): String? {
        val manifest = abi()?.let { ArtifactManifest.of(it) } ?: return null
        if (paths.state(vfs).realAgentInstalled) return null
        manifest.gap?.let { return "$NOTHING_TO_RUN nothing here can be started: $it" }
        return when (roomFor(manifest)) {
            true -> "$PROVISION the guest is not on this device and there is room for it; " +
                "'omp provision' prints the exact cost and asks before a byte moves"
            false -> "$NOTHING_TO_RUN the guest is absent and the free space above is short of what " +
                "the manifest asks for, so 'omp provision' would refuse"
            // The filesystem would not answer, which the space check above already said. Recommending
            // a download whose own room check cannot be evaluated would be recommending a coin flip.
            null -> "$NOTHING_TO_RUN the guest is absent, and this command could not read enough free " +
                "space to say whether 'omp provision' would fit it"
        }
    }

    /**
     * Whether the free space on the payload's filesystem covers what a first run needs.
     *
     * Null when the filesystem would not answer, which is the same "cannot be established" the
     * space check reports and is deliberately not read as room being there.
     */
    private fun roomFor(manifest: ArtifactManifest): Boolean? = try {
        vfs.diskUsage(paths.targetDir).freeBytes >= manifest.requiredBytes
    } catch (e: FsException) {
        null
    }

    // ---- the pieces the sections are made of ---------------------------------------------------

    /**
     * The lines, the gaps, and the one rule that keeps the two in step.
     *
     * [gap] records **and returns** the text, so a section that cannot read something prints the
     * reason in place and collects it for the gaps section in the same expression. A separate
     * bookkeeping call is a second thing to forget, and forgetting it is a fact silently dropped
     * from the report whose whole job is not to drop facts.
     */
    private class Out {
        val lines = ArrayList<String>()
        val gaps = ArrayList<String>()

        fun say(text: String) {
            lines += text
        }

        fun section(name: String) {
            lines += name
        }

        /**
         * One `key: value`, the key padded so the report is a column and not a list.
         *
         * **A key longer than [WIDTH] still gets a space**, because the longest keys here are the
         * helper file names and `libproot-loader.so:18136 bytes` is a line nobody can scan. A
         * column that runs out is a column that has stopped being one.
         */
        fun pair(key: String, value: String) {
            val head = key + ":"
            val gap = if (head.length >= WIDTH) " " else ""
            lines += "  " + head.padEnd(WIDTH) + gap + value
        }

        /** Names a fact this run could not read and hands the caller the text to print. */
        fun gap(reason: String): String {
            val text = "$UNREADABLE: $reason"
            gaps += text
            return text
        }
    }

    /** This device's ABI, from the platform's own ordered list, which is what [Abi.detect] wants. */
    private fun abi(): Abi? = Abi.detect(
        osArch,
        services.buildProperties()[ABILIST]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty(),
    )

    /**
     * A directory as a person reads it: where it is and what is in it, or why it could not be read.
     *
     * **Absent and unreadable are two different sentences.** `No such file or directory` is a fact
     * about the device and belongs in the line; anything else is a fact about this run not getting
     * to look, and says so. Reporting a permission failure as "is not there" would send a user off
     * to look for a directory that is exactly where it should be.
     */
    private fun directory(out: Out, path: String): String = try {
        when (vfs.stat(path).type) {
            VNodeType.DIRECTORY -> {
                val entries = vfs.readDir(path)
                if (entries.isEmpty()) "$path — exists and is empty" else "$path — exists, ${entries.size} entries"
            }
            else -> out.gap("$path is not a directory")
        }
    } catch (e: FsException) {
        if (e.errno == FsErrno.NO_SUCH_FILE) "$path — not there" else out.gap("$path could not be read: ${e.errno.text}")
    }

    /** A file as a person reads it: its size, or why it could not be read. */
    private fun describe(path: String): String = try {
        val stat = vfs.stat(path)
        if (stat.type == VNodeType.DIRECTORY) {
            "$path — a directory, ${vfs.readDir(path).size} entries"
        } else {
            "$path — ${ArtifactManifest.humanBytes(stat.size)}"
        }
    } catch (e: FsException) {
        if (e.errno == FsErrno.NO_SUCH_FILE) "$path — not there" else "$path — ${UNREADABLE}: ${e.errno.text}"
    }

    /** A file the chat service publishes, under the app's own files directory. */
    private fun serviceFile(name: String): String = "${paths.workDir}/$name"

    /** The published loopback host and port, or null when the service has published none. */
    private fun loopbackBase(): String? = try {
        String(vfs.readBytes(serviceFile(URL_FILE)), Charsets.UTF_8)
            .trim()
            .substringAfter("://", "")
            .substringBefore('/')
            .ifEmpty { null }
    } catch (e: FsException) {
        null
    }

    private fun portOf(base: String): Int? = base.substringAfter(':', "").substringBefore('/').toIntOrNull()

    companion object {

        /** The word every line of this command's output starts with, so a paste is findable. */
        const val PREFIX = "omp doctor"

        /** The prefix for every fact this run could not read. Never a value that looks healthy. */
        const val UNREADABLE = "unreadable"

        /** Long enough for a loopback connect on a busy phone, short enough not to look like a hang. */
        const val PROBE_MS = 400

        /** The width the `key:` column is padded to. */
        const val WIDTH = 15

        /** `ro.product.cpu.abilist`, which `:app` fills with `Build.SUPPORTED_ABIS`, in order. */
        const val ABILIST = "ro.product.cpu.abilist"

        /** The build property whose value names the exact image this app is running on. */
        const val FINGERPRINT = "ro.build.fingerprint"

        /**
         * The section for the guest's own runtime, printed between `agent update` and `chat`.
         *
         * **Between those two because of what it is a fact about.** `agent update` is what this build
         * last did to the agent *in* the guest; this is whether the guest is serving anything at all;
         * `chat` is what the screen is being handed. A user's "the Debian is installed, so why am I
         * still talking to the Kotlin one?" is a question about the middle one and is read there.
         */
        const val SECTION_ORIGIN = "guest origin"

        /**
         * The section for the boot's `omp update`, printed between `guest state` and `chat`.
         *
         * **Between those two because of what it is a fact about**: `guest state` is what the Debian
         * holds, this is what this build last did to the agent inside it, and `chat` is what the
         * screen is being handed. A user's "why is the agent in my guest old?" is a question about
         * the second and is read between the two.
         */
        const val SECTION_UPDATE = "agent update"

        /** The gaps section when nothing in it failed to be read. */
        const val NONE_READ = "none:"

        /** What the next section says when no state above is waiting on a command. */
        const val NOTHING_TO_RUN = "nothing to run:"

        /** The url the chat service publishes once it has bound, relative to the files directory. */
        const val URL_FILE = "web/url"

        /** The credential itself. Its size is reported; its bytes never are. */
        const val TOKEN_FILE = "web/token"

        /** The two commands this build can actually be told to run. */
        const val GRANT = "grant-storage:"

        const val WEB = "web:"

        /** The one verb in this build that downloads, and so the only advice about a 334 MB spend. */
        const val PROVISION = "omp provision:"

        /**
         * What this command cannot tell you, in the words a bug report needs.
         *
         * **It is the last line on purpose.** Every line above can read healthy on a device where
         * the guest will not start, because every one of them is a fact about a filesystem and a
         * platform and none of them is an `execve`. The next failure after a green report is the
         * kernel refusing to exec a file out of the exec directory, a proot build that will not
         * accept these flags, or a Debian that unpacked without a loader in it — and a diagnostic
         * that stopped at "all 5 are there" would have implied the device was ready.
         */
        val CANNOT = listOf(
            "what this cannot tell you: nothing above executed the native helper. \"the helper is",
            "there\" is not \"the guest will boot\", and no build of this app has ever run proot on any",
            "device. The next failure after a report that reads healthy is the kernel refusing to",
            "exec a file out of the exec directory, a proot that will not accept these flags, or a",
            "Debian that unpacked without a loader in it.",
        )

        /**
         * The `lib/<abi>/` entries this APK packages, per ABI.
         *
         * **A copy of what is in `app/src/main/jniLibs/`, and it has to be one**: this report names
         * the files it expects to find in the exec directory, and a list that had drifted from the
         * package would report a healthy device as missing files and a broken one as complete.
         * `libproot-loader32.so` is in the two 64-bit directories only, because the 32-bit ports
         * exec a 32-bit loader for a 32-bit guest and this app never has one.
         */
        val EXPECTED: Map<Abi, List<String>> = mapOf(
            Abi.ARM64 to listOf(
                "libproot.so",
                "libproot-loader.so",
                "libproot-loader32.so",
                "libtalloc.so",
                "libandroid-shmem.so",
            ),
            Abi.X86_64 to listOf(
                "libproot.so",
                "libproot-loader.so",
                "libproot-loader32.so",
                "libtalloc.so",
                "libandroid-shmem.so",
            ),
            Abi.ARMEABI_V7A to listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"),
            Abi.X86 to listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"),
        )

        /**
         * The version code Android puts in the directory it installs a package into:
         * `/data/app/~~<hash>/<package>-<versionCode>==/base.apk`.
         *
         * Null when the path does not have that shape, which is a real answer on a device whose
         * package manager lays its directories out differently, and a guess would be worse.
         */
        private val VERSION_IN_PATH = Regex(""".*-(\d+)==$""")

        private fun versionOf(path: String): String? =
            VERSION_IN_PATH.find(path.trimEnd('/').substringBeforeLast('/'))?.groupValues?.get(1)
    }
}
