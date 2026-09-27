package omp.vm.provision

import omp.shell.fs.FsException
import omp.shell.fs.Vfs

enum class GuestOutcome {
    /** The mark was already in the tree, so nothing was run and no bytes crossed the connection. */
    ALREADY_INSTALLED,

    /** Every step exited zero and the mark was written. */
    INSTALLED,

    /**
     * A step was still running at [INSTALL_BOUND_MS] and the launcher stopped it.
     *
     * **Its own outcome and not a failure, for the same reason [omp.vm.guestapi.UpdateOutcome.TIMED_OUT]
     * is one.** It carries the same number — 124, the one a shell already uses for a command that had
     * to be killed — and the two are told apart by the name and not by the status. A step stopped at
     * the bound has usually done most of its work: `apt` has already unpacked what it fetched, so the
     * next run continues rather than starting again, which is exactly what the next run's `apt-get
     * install` decides.
     */
    STOPPED,

    /**
     * A step exited non-zero, the mark could not be written, or there is no Debian to install into.
     *
     * There is deliberately no "refused": this is a step the caller has already agreed to, and a
     * disagreement is expressed by not calling it. A missing rootfs is not a disagreement — it is
     * a prerequisite that is not there, and the answer is this outcome with nothing launched.
     */
    FAILED,
}

/** What happened inside the guest, and every line that says so. */
data class GuestReport(
    val outcome: GuestOutcome,
    val guest: GuestInstall,
    /** How many steps were actually launched, which is 0 when the mark was already there. */
    val stepsRun: Int,
    val lines: List<String>,
)

/**
 * One command inside the Debian, and what it is for.
 *
 * [command] is a **guest** path vector and not a host one, exactly as [ProotCommand.argv]'s is:
 * the whole thing goes through [ProotCommand.argv], so what a caller reads here is the command
 * and what a device runs is the command with proot's own flags in front of it.
 */
data class GuestStep(val what: String, val command: List<String>)

/**
 * The LAMP install inside the guest: the step that runs `apt-get install` for the packages
 * [ArtifactManifest] measured, and the command sequence it would run.
 *
 * **This class builds the command and the state machine. It is not an `apt`.** There is no
 * implementation of [ProotLauncher] in `:core` — a JVM module cannot exec a native helper, and
 * `:app` has the one that does — so every step here ends at `launcher.run(argv, env, cwd)` and
 * the status that comes back. Nothing in this repository has run this sequence: not on a phone,
 * not on an emulator, not in a test. A test that "verified the install" would verify a mock, and
 * this project's whole argument is that a mock is not evidence.
 *
 * ### Where it is called from now, and what that has not changed
 *
 * **`omp.vm.provision.GuestStart` runs this at every start of the app, before Apache is started and
 * only when [omp.vm.provision.ProvisionPaths.guestMarker] is not in the tree**, so a device whose
 * LAMP is installed launches nothing here. That is the only thing wiring it changed, and it changed
 * nothing about what is known:
 *
 * - **Nothing in this repository has ever executed this sequence against a real Debian, on any ABI.**
 *   The tests are a fake launcher and a temporary directory, and a fake `apt` is not an `apt`:
 *   whether `deb.debian.org` is reachable from inside a proot guest, whether `mariadb-server`'s
 *   `postinst` completes with no init to start a server with, and whether these four packages
 *   install at all on trixie are all still open questions this class refuses to answer in advance.
 * - **The claims in "What is not verified here" are now the ones a user is most likely to hit**, and
 *   [omp.vm.provision.GuestState.LAMP_FAILED] and [omp.vm.provision.GuestState.LAMP_MISSING] exist
 *   because of them rather than for tidiness.
 *
 * ### The sequence, and why each step is there
 *
 * ```
 * /usr/bin/apt-get update                     the netboot image ships no package list at all
 * /usr/bin/apt-get install -y <packages…>     the manifest's four names; apt resolves the closure
 * /usr/sbin/a2enmod php8.4                    libapache2-mod-php8.4 unpacked and not enabled is not PHP
 * ```
 *
 * **And then, after this class's [install] and after the PHP tree has been written:**
 *
 * ```
 * /usr/sbin/a2enconf omp-guest                the guest's own API configuration, linked in
 * ```
 *
 * **That line is [enableApi] and it is a separate call on purpose** — see that method, which says
 * what breaks if it is made a step of the sequence above.
 *
 * **`update` is not optional and not politeness.** A netboot rootfs has an empty
 * `/var/lib/apt/lists`, so the second step answers `Unable to locate package apache2-bin` and
 * costs the user 55 MB of a download to find out. The list is a few MB of the same connection and
 * it is the difference between an install that happens and one that does not.
 *
 * **There is no `--no-install-recommends`, and its absence is load-bearing.** The 385,689 KiB in
 * [GuestInstall.installedKib] was measured the way `apt` installs by default, so adding the flag
 * would make the number a user is asked to agree to a number about a run that does not happen.
 *
 * **The last step is not an `apt` call and is still part of LAMP.** `libapache2-mod-php8.4` is a
 * package that does nothing until `a2enmod` has put `php8.4` into the guest's own
 * `mods-enabled`, and an install that stopped one line earlier would be reported as a success
 * with a PHP that no request ever reaches. It is a Debian script that edits a file in the guest's
 * Apache configuration — the same kind of step `dpkg-reconfigure` is — and not a guess about what
 * Apache does with it.
 *
 * ### Why the answer to "does it already have them" is a file and not `dpkg-query`
 *
 * **[ProotLauncher] returns an exit status and nothing else.** There is no pipe, no `stdout` and
 * no way to read what the guest printed, so `dpkg-query -W -f='${Status}' apache2-bin` could be
 * launched and its answer thrown away — which is the same as not asking. So the question is
 * answered the way [ProvisionPaths] answers whether the rootfs is whole: a mark **inside the
 * tree**, written only after the last step exited zero. A guest that has the packages does not
 * run this again, and a guest whose install failed half way has no mark, so the next run starts it
 * from the top — `apt` is the thing that knows which half is already unpacked.
 *
 * ### What is not verified here, and cannot be
 *
 * 1. **That `apt` works at all inside this rootfs.** `mariadb-server`'s postinst starts a server,
 * 3. **That anything is then serving.** Installing `apache2` puts the program on the disk. It
 *    does not start it, because starting it needs an init this app does not have, and
 *    [WebRoot] putting three files in its document root is a different step that says so itself.
 * 4. **That the numbers in [GuestInstall] are still Debian's numbers.** They were measured against
 *    a package index, and `apt` resolving a different closure on a device is a real possibility
 *    that no argument vector can rule out.
 *
 * The caller is a provisioning step, so this is called after the user has agreed, and
 * [Boot] — whose one rule is that nothing is started without being asked — reports the cost with
 * [GuestInstall.costLine] rather than running any of it.
 */
class GuestPackages(
    val paths: ProvisionPaths,
    private val proot: ProotCommand,
    private val launcher: ProotLauncher,
    private val vfs: Vfs,
) {

    /**
     * The sequence, in order, as data.
     *
     * Public and pure so that what would run inside the guest is readable and testable on a JVM
     * that has no guest: [GuestPackagesTest] pins every element of every vector, and the vectors
     * are the deliverable — the running is somebody else's half.
     */
    fun steps(guest: GuestInstall): List<GuestStep> = listOf(
        GuestStep(
            what = "refresh the Debian package list",
            command = listOf(APT_GET, "update"),
        ),
        GuestStep(
            what = "install ${guest.name} and everything it depends on",
            command = listOf(APT_GET, "install", "-y") + guest.packages,
        ),
        GuestStep(
            what = "enable the PHP module Apache needs to serve the pages",
            command = listOf(A2ENMOD, PHP_MODULE),
        ),
    )

    /**
     * The guest's own environment from [ProotCommand.env], plus the two entries that stop a
     * non-interactive install from hanging.
     *
     * A device install has no terminal attached, so a debconf question is a question nobody is
     * there to answer and the step is a `apt` that never returns. `DEBIAN_FRONTEND=noninteractive`
     * is the supported way to say that, and `DEBCONF_NONINTERACTIVE_SEEN` is what debconf itself
     * looks for when a package's postinst checks whether anyone is watching. Nothing else is
     * added: an environment invented for a package that has not been looked at is a guess.
     */
    fun env(): Map<String, String> = LinkedHashMap(proot.env()).apply {
        put("DEBIAN_FRONTEND", "noninteractive")
        put("DEBCONF_NONINTERACTIVE_SEEN", "true")
    }

    /**
     * Run the sequence, unless the mark says it has already run, and say for each step what it was
     * and what it exited with.
     *
     * **The Debian has to be there, and the mark is inside it.** A rootfs that is not unpacked is
     * the one precondition that is not a matter of judgement — the mark's own directory does not
     * exist, so there is nowhere to record the answer — and it is checked before anything is
     * launched rather than after 57 MB has been spent finding out.
     *
     * **The mark is written last and only on a clean run.** It is the one file standing between a
     * guest that has LAMP and a guest that is asked for it again on every boot, and a mark written
     * before the last step would be a claim about an install that did not finish.
     */
    fun install(guest: GuestInstall): GuestReport {
        val lines = ArrayList<String>()
        val state = paths.state(vfs)
        if (!state.rootfsInstalled) {
            // There is no Debian to install into, and this is the one precondition that is not a
            // matter of opinion: without the mark's own directory there is nowhere to record the
            // answer, and 57 MB of mobile data to find out.
            lines += "there is no unpacked Debian at ${proot.rootfs} " +
                "(${ProvisionPaths.ROOTFS_MARKER} is not in it), so there is nothing to install " +
                "${guest.name} into. Nothing was run and no bytes crossed the connection."
            return record(GuestOutcome.FAILED, guest, 0, lines, null, null)
        }
        if (state.guestInstalled) {
            lines += "${guest.name} is already in the Debian at ${proot.rootfs} — " +
                "${ProvisionPaths.GUEST_MARKER} is in the tree — so nothing was run and no bytes " +
                "crossed the connection."
            lines += costLine(guest, measuredKib(guest.packages))
            return record(GuestOutcome.ALREADY_INSTALLED, guest, 0, lines, null, null)
        }
        lines += "installing ${guest.name} inside the Debian at ${proot.rootfs} with apt: " +
            guest.packages.joinToString(", ") + " and everything they depend on. The wait is " +
            "bounded at ${INSTALL_BOUND_MS}ms, and apt is what continues the next run."
        // Written *before* the first step and not after: a record that only appears on the way out
        // cannot say that a run is in progress, and the run this build cannot see is the one that
        // matters — an app that was killed here, or a phone that is still doing this while a user is
        // reading `omp doctor` from a second process.
        writeRecord(INSTALLING, guest, null, null, null, "apt is running inside the Debian now")
        var run = 0
        for (step in steps(guest)) {
            val status = launcher.run(proot.argv(step.command), env(), proot.workDir)
            run++
            when {
                status == TIMED_OUT -> {
                    lines += "${step.what}: ${step.command.joinToString(" ")} was still running at " +
                        "${INSTALL_BOUND_MS}ms and was stopped. ${ProvisionPaths.GUEST_MARKER} was " +
                        "not written, so the next start runs the sequence again and apt continues " +
                        "from what it has already unpacked rather than starting again."
                    return record(GuestOutcome.STOPPED, guest, run, lines, step, null)
                }
                status != 0 -> {
                    lines += "${step.what}: ${step.command.joinToString(" ")} exited with status " +
                        "$status. ${ProvisionPaths.GUEST_MARKER} was not written, so the next run " +
                        "starts the sequence again and apt decides what is still missing. What is on " +
                        "the disk now is a partial install, and this build will not present it as a " +
                        "guest that can serve anything."
                    return record(GuestOutcome.FAILED, guest, run, lines, null, step)
                }
            }
            lines += "${step.what}: ${step.command.joinToString(" ")} exited 0."
        }
        val measured = measuredKib(guest.packages)
        try {
            vfs.writeBytes(paths.guestMarker, markFor(guest, measured))
        } catch (e: FsException) {
            lines += "${paths.guestMarker} could not be written: ${e.errno.text}. The packages " +
                "are installed, but the next run will install them again because it cannot tell."
            return record(GuestOutcome.FAILED, guest, run, lines, null, null, measured)
        }
        lines += costLine(guest, measured)
        return record(GuestOutcome.INSTALLED, guest, run, lines, null, null, measured)
    }

    /**
     * The one step that makes the guest's own API configuration live, run **after** the PHP tree is
     * written and never before.
     *
     * ```
     * /usr/sbin/a2enconf omp-guest
     * ```
     *
     * **It is a separate call and not a fourth step of [install] for one reason: order.** The file
     * `a2enconf` links in is written by [omp.vm.guestapi.GuestApiTree], and `a2enconf` on a file
     * that is not there exits non-zero. Inside [install] that would be a failed `apt` — which means
     * no mark, which means the next boot downloads and installs 57 MB again to find out that the
     * only thing missing was a file this app had not written yet. So the caller runs
     * [install], then the tree, then this, and the order is a line of the caller rather than an
     * accident of a list.
     *
     * **What this step does not do:** it does not start Apache, and it does not check that the PHP
     * it links to parses. A proot guest has no init for anything to start a server with, and this
     * class has no way to read a file out of the guest — the launcher returns an exit status and
     * nothing else. The status this returns is `a2enconf`'s, and it is reported as what it is.
     */
    fun enableApi(): GuestReport {
        val lines = ArrayList<String>()
        val state = paths.state(vfs)
        if (!state.rootfsInstalled) {
            lines += "there is no unpacked Debian at ${proot.rootfs} " +
                "(${ProvisionPaths.ROOTFS_MARKER} is not in it), so there is no Apache to configure. " +
                "Nothing was run."
            return GuestReport(GuestOutcome.FAILED, API_STEP, 0, lines)
        }
        val conf = "${paths.rootfsDir}${paths.guestConf}"
        val there = try {
            vfs.stat(conf)
            true
        } catch (e: FsException) {
            false
        }
        if (!there) {
            // Reported rather than run. A step that fails here would send the next boot back through
            // 57 MB of `apt` over a file this app is the one that has to write.
            lines += "there is no $conf, so ${paths.guestConfName} was not linked in and the guest's " +
                "Apache is not serving this app's API. Nothing was run: install the guest's PHP " +
                "first, which is the step that writes that file."
            return GuestReport(GuestOutcome.FAILED, API_STEP, 0, lines)
        }
        val command = listOf(A2ENCONF, paths.guestConfName)
        val status = launcher.run(proot.argv(command), env(), proot.workDir)
        if (status != 0) {
            lines += "link the guest's API configuration into Apache: " +
                "${command.joinToString(" ")} exited with status $status. The packages are still " +
                "installed and ${ProvisionPaths.GUEST_MARKER} is still in the tree, so the next run " +
                "does not install them again — it runs this step and nothing else."
            return GuestReport(GuestOutcome.FAILED, API_STEP, 1, lines)
        }
        lines += "link the guest's API configuration into Apache: " +
            "${command.joinToString(" ")} exited 0. This step started nothing: a snippet that is " +
            "linked in is not a server that is listening, and nothing in this layer starts one."
        return GuestReport(GuestOutcome.INSTALLED, API_STEP, 1, lines)
    }

    /**
     * The manifest's estimate and `dpkg`'s measurement, side by side, in a sentence a report shows.
     *
     * **Two numbers and never one, and they come from two places that are not the same place.**
     *
     * The **estimate** is [omp.vm.provision.ArtifactManifest.GuestInstall.installedKib], and it was
     * produced on a build machine by resolving the arm64 trixie closure against the Debian package
     * index there. It is an arm64 measurement and nothing has been measured for the other three
     * ABIs; the other three rows carry `null` and this line says so rather than borrowing arm64's.
     * The **wire** figure beside it, `downloadBytes`, was taken the same way from the same index on
     * the same machine.
     *
     * The **real** figure is [measured], and it comes from somewhere else entirely: `dpkg` **inside
     * the guest, on the user's phone, reading its own status file**. That dpkg has never run here —
     * no `apt`, no `dpkg` and no Debian has ever been executed by this build on any ABI — so the
     * number in [measured] is a reading this build makes of a file a real package manager on a real
     * device wrote, and never of a figure this build produced. On a machine with no guest, [measured]
     * is null and the line says so instead of inventing one.
     *
     * **What is deliberately absent is the real download size, and it is absent for a reason that
     * is also a measurement.** `apt` removes the `.deb` files it fetched from
     * `/var/cache/apt/archives` on a successful install, so by the time this can be read the
     * evidence of what crossed the connection is gone and the wire figure above is a build machine's
     * reading of an index, not a count of bytes. Saying which is which is the whole difference
     * between a figure and an estimate, and this line names both.
     */
    private fun costLine(guest: GuestInstall, measured: Long?): String {
        val estimated = guest.installedKib
        val real = when {
            measured == null ->
                "dpkg's own record is not readable at ${paths.dpkgStatus}, so the only figure is the estimate"
            estimated == null -> ArtifactManifest.humanBytes(measured * 1024L) +
                " measured by dpkg, and this build carries no estimate for this architecture to " +
                "compare it with"
            else -> ArtifactManifest.humanBytes(measured * 1024L) + " measured by dpkg, against the " +
                ArtifactManifest.humanBytes(estimated * 1024L) + " the manifest estimated on arm64"
        }
        val wire = guest.downloadBytes
        return "${guest.name}: $real on the device. The " + (wire?.let {
            ArtifactManifest.humanBytes(it) + " the manifest estimated"
        } ?: "download share this manifest does not estimate for this architecture") + " over the " +
            "connection is still an estimate: apt deletes the .deb files it fetched, so the real " +
            "figure cannot be read after the fact and this build does not invent one."
    }

    /**
     * `dpkg`'s own `Installed-Size` over [names], in KiB, or null when the file cannot be read.
     *
     * **A stanza parser and not a `dpkg-query`, for the reason the whole of this layer's design is
     * a reason.** [omp.vm.provision.ProotLauncher] returns an exit status and nothing else, so asking
     * `dpkg-query` would mean launching a command in a guest that may not even be running and
     * throwing the answer away. The rootfs is a directory on the phone and this file is text in it.
     *
     * **Only stanzas `dpkg` says are `install ok installed` are counted**, and a name it does not
     * list at all is simply not added: a partial install measures what is really there, which is the
     * whole point of measuring it.
     */
    fun measuredKib(names: List<String>): Long? {
        val text = try {
            String(vfs.readBytes(paths.dpkgStatus), Charsets.UTF_8)
        } catch (e: FsException) {
            return null
        }
        var total = 0L
        var found = 0
        for (stanza in text.split("\n\n")) {
            var name: String? = null
            var installed: Long? = null
            var ok = false
            for (line in stanza.lineSequence()) {
                when {
                    line.startsWith("Package: ") -> name = line.substringAfter("Package: ").trim()
                    line.startsWith("Status: ") -> ok = line.contains("install ok installed")
                    line.startsWith("Installed-Size: ") ->
                        installed = line.substringAfter("Installed-Size: ").trim().toLongOrNull()
                }
            }
            if (ok && name != null && name in names && installed != null) {
                total += installed
                found++
            }
        }
        return if (found == 0) null else total
    }

    /** Write the record for a finished run and hand the report back unchanged. */
    private fun record(
        outcome: GuestOutcome,
        guest: GuestInstall,
        stepsRun: Int,
        lines: List<String>,
        stoppedAt: GuestStep?,
        failedAt: GuestStep?,
        measured: Long? = null,
    ): GuestReport {
        writeRecord(outcome.name, guest, stoppedAt, failedAt, measured, lines.lastOrNull())
        return GuestReport(outcome, guest, stepsRun, ArrayList(lines))
    }

    /** What goes in the record, in the order it is written, and the keys a reader looks for. */
    private fun writeRecord(
        outcome: String,
        guest: GuestInstall,
        stoppedAt: GuestStep?,
        failedAt: GuestStep?,
        measured: Long?,
        said: String?,
    ) {
        val text = StringBuilder(RECORD_HEADER).append('\n')
        fun line(key: String, value: String?) {
            if (value != null) text.append(key).append(' ').append(value).append('\n')
        }
        line(KEY_OUTCOME, outcome)
        line(KEY_NAME, guest.name)
        line(KEY_PACKAGES, guest.packages.joinToString(","))
        line(KEY_ESTIMATED_DOWNLOAD, guest.downloadBytes?.toString())
        line(KEY_ESTIMATED_INSTALLED, guest.installedKib?.toString())
        line(KEY_MEASURED_INSTALLED, measured?.toString())
        line(KEY_STEP, (stoppedAt ?: failedAt)?.let { it.command.joinToString(" ") })
        line(KEY_SAID, said?.replace('\n', ' ')?.trim()?.ifEmpty { null })
        line(KEY_AT, stamp(System.currentTimeMillis()))
        try {
            val file = installRecordFile(paths)
            omp.vm.provision.mkdirs(vfs, file.substringBeforeLast('/'))
            vfs.writeBytes(file, text.toString().toByteArray(Charsets.UTF_8))
        } catch (e: FsException) {
            // A record that could not be written costs `omp doctor` its answer and costs nothing
            // else: the report this run returns already says what happened, and the mark inside the
            // tree — not this file — is what stops the next start installing again.
        }
    }

    /** What goes in the mark, with the measured figure beside the estimated one. */
    private fun markFor(guest: GuestInstall, measured: Long?): ByteArray = buildString {
        appendLine("#omp-guest/v1 — name packages downloadBytes installedKib measuredKib")
        append(guest.name)
        append(' ')
        append(guest.packages.joinToString(","))
        append(' ')
        append(guest.downloadBytes ?: "unmeasured")
        append(' ')
        appendLine((guest.installedKib ?: "unmeasured").toString() + " " + (measured ?: "unmeasured"))
    }.toByteArray(Charsets.UTF_8)

    companion object {


        /**
         * The wait every step of the install is given, in milliseconds: five minutes.
         *
         * **A ceiling and not an estimate, and the two are different numbers for a reason.** 55,211,704
         * bytes over a phone radio is minutes and the 376.7 MiB unpack onto the same flash is not, so a
         * number in between would be a guess dressed as a promise. Five minutes is long enough that an
         * install which is going to finish usually finishes, and bounded because a wedged `apt` must
         * not hold a start of the app for ever — [omp.vm.guestapi.AgentUpdate] makes exactly this
         * trade at 60 seconds for a check whose fast path is half a second, and the consequence is the
         * same: a run that does not fit is **stopped**, recorded as [GuestOutcome.STOPPED], and the next
         * start runs the sequence again with `apt` continuing from what it already unpacked.
         *
         * **The launcher enforces it, and this number is what the app builds that launcher with.** It
         * cannot be enforced from in here: [omp.vm.provision.ProotLauncher] takes a vector and returns a
         * status, and only `:app`'s [com.omp.terminal.vm.ProotProcessLauncher] can destroy a process.
         * That is why the number lives here and not there — it is a fact about the step, and the step
         * reports 124, the same status [omp.vm.guestapi.UpdateOutcome.TIMED_OUT] uses, as
         * [GuestOutcome.STOPPED].
         */
        const val INSTALL_BOUND_MS = 300_000L

        /**
         * The status that means the launcher stopped a step at its bound.
         *
         * **124, the same number and for the same reason** as
         * [omp.vm.guestapi.UpdateOutcome.TIMED_OUT.exitStatus]: it is the one a shell already uses for a
         * command that had to be killed, so "we stopped it" can never be read as "it failed", and the
         * outcome's own name is what tells the two apart in a report.
         */
        const val TIMED_OUT = 124

        /** The value the record carries while the sequence is in progress and nothing has finished. */
        const val INSTALLING = "INSTALLING"

        /** The first line of [recordFile], which is a comment and says which build wrote the rest. */
        const val RECORD_HEADER =
            "#omp-guest-packages/v1 — written by omp.vm.provision.GuestPackages, one run per start"

        /** The file name inside [omp.vm.provision.ProvisionPaths.downloadDir]. */
        const val RECORD_NAME = "guest-packages"

        const val KEY_OUTCOME = "outcome"
        const val KEY_NAME = "name"
        const val KEY_PACKAGES = "packages"

        /** What the manifest estimated crosses the connection, in bytes. An estimate, always. */
        const val KEY_ESTIMATED_DOWNLOAD = "estimated-download"

        /** What the manifest estimated the install occupies, in KiB. Measured on arm64 only. */
        const val KEY_ESTIMATED_INSTALLED = "estimated-installed-kib"

        /** What `dpkg`'s own status file says, in KiB. Null until a clean run has produced one. */
        const val KEY_MEASURED_INSTALLED = "measured-installed-kib"

        /** The command that did not finish, in full, so a report can quote it back. */
        const val KEY_STEP = "step"

        const val KEY_SAID = "said"
        const val KEY_AT = "at"

        /** UTC, ISO-8601: the stamp [omp.vm.guestapi.AgentUpdate] writes into its own record. */
        fun stamp(millis: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(millis))

        /** `apt-get` in the guest, named by its own path because the guest has no shell to find it. */
        const val APT_GET = "/usr/bin/apt-get"

        /** Debian's Apache module switcher, which is what turns the PHP package on. */
        const val A2ENMOD = "/usr/sbin/a2enmod"

        /**
         * The module `libapache2-mod-php8.4` installs.
         *
         * It is spelled out rather than derived from the package list because a Debian that moved
         * the module's major version would ship a different package name too, and
         * [ArtifactManifest.LAMP_PACKAGES] is the place that is measured.
         */
        const val PHP_MODULE = "php8.4"

        /**
         * Debian's Apache configuration switcher, and the sibling of [A2ENMOD].
         *
         * **`a2enmod` links a module into `mods-enabled`; `a2enconf` links a snippet into
         * `conf-enabled`.** Both ship in the `apache2-bin` package, so the guest has this the
         * moment [install]'s second step exits zero — which is why [enableApi] refuses rather than
         * runs when the drop-in is not there.
         */
        const val A2ENCONF = "/usr/sbin/a2enconf"

        /**
         * What [enableApi]'s report is about, which is not an install of anything.
         *
         * [GuestReport] carries a [GuestInstall] because it is a report about one, and this step is
         * about neither a cost nor a download. The name is what a report shows and the figures are
         * unmeasured, so [GuestInstall.costLine] is never asked about it.
         */
        private val API_STEP = GuestInstall("the guest's API configuration", emptyList(), null, null)
    }
}

/** Where the install's own record lives, beside the guest origin's own: beside the provisioning state, not in the rootfs. */
fun installRecordFile(paths: ProvisionPaths): String = "${paths.downloadDir}/${GuestPackages.RECORD_NAME}"

/**
 * The record as it is on the disk, or null when there is none.
 *
 * **Null and never an exception**, and null is the ordinary case: a device that has never installed
 * the guest's LAMP is every device before its first start of the app, and `omp doctor` has to be
 * able to say so rather than failing.
 */
fun readInstallRecord(vfs: Vfs, paths: ProvisionPaths): InstallRecord? {
    val text = try {
        String(vfs.readBytes(installRecordFile(paths)), Charsets.UTF_8)
    } catch (e: FsException) {
        return null
    }
    val fields = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val parts = line.split(' ', limit = 2)
        if (parts.size == 2) fields[parts[0]] = parts[1]
    }
    return if (fields.isEmpty()) null else InstallRecord(fields)
}

/**
 * What one run of the guest's `apt` install left behind.
 *
 * **[outcome] is the name or null, and [outcomeName] is either way** — the same pair
 * [omp.vm.guestapi.UpdateRecord] has, for the same reason: a record written by a newer build must
 * be reported as a name this build does not have and never as one of this build's own.
 */
data class InstallRecord(val fields: Map<String, String>) {
    val outcomeName: String? get() = fields[GuestPackages.KEY_OUTCOME]
    val outcome: GuestOutcome? get() = outcomeName?.let { GuestOutcome.entries.firstOrNull { e -> e.name == it } }
    val name: String? get() = fields[GuestPackages.KEY_NAME]
    val packages: String? get() = fields[GuestPackages.KEY_PACKAGES]

    /** What the manifest estimated would cross the connection. An estimate, whatever happened. */
    val estimatedDownload: Long? get() = fields[GuestPackages.KEY_ESTIMATED_DOWNLOAD]?.toLongOrNull()
    val estimatedInstalledKib: Long? get() = fields[GuestPackages.KEY_ESTIMATED_INSTALLED]?.toLongOrNull()

    /** What `dpkg` says is really there, or null when no clean run has produced a figure. */
    val measuredInstalledKib: Long? get() = fields[GuestPackages.KEY_MEASURED_INSTALLED]?.toLongOrNull()

    /** The command that did not finish, in full. */
    val step: String? get() = fields[GuestPackages.KEY_STEP]
    val said: String? get() = fields[GuestPackages.KEY_SAID]
    val at: String? get() = fields[GuestPackages.KEY_AT]
}
