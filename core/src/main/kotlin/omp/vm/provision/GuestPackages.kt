package omp.vm.provision

import omp.shell.fs.FsException
import omp.shell.fs.Vfs

/** What a [GuestPackages.install] ended up doing. */
enum class GuestOutcome {
    /** The mark was already in the tree, so nothing was run and no bytes crossed the connection. */
    ALREADY_INSTALLED,

    /** Every step exited zero and the mark was written. */
    INSTALLED,

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
 *    and a proot guest has no init to be started by; what that does is a question for a device.
 * 2. **That the archive is reachable.** The guest fetches from `deb.debian.org` over the same
 *    mobile connection, and this class cannot see a byte of it.
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
            return GuestReport(GuestOutcome.FAILED, guest, 0, lines)
        }
        if (state.guestInstalled) {
            lines += "${guest.name} is already in the Debian at ${proot.rootfs} — " +
                "${ProvisionPaths.GUEST_MARKER} is in the tree — so nothing was run and no bytes " +
                "crossed the connection."
            lines += guest.costLine(installed = true)
            return GuestReport(GuestOutcome.ALREADY_INSTALLED, guest, 0, lines)
        }
        lines += "installing ${guest.name} inside the Debian at ${proot.rootfs} with apt: " +
            guest.packages.joinToString(", ") + " and everything they depend on."
        var run = 0
        for (step in steps(guest)) {
            val status = launcher.run(proot.argv(step.command), env(), proot.workDir)
            run++
            if (status != 0) {
                lines += "${step.what}: ${step.command.joinToString(" ")} exited with status " +
                    "$status. ${ProvisionPaths.GUEST_MARKER} was not written, so the next run " +
                    "starts the sequence again and apt decides what is still missing."
                return GuestReport(GuestOutcome.FAILED, guest, run, lines)
            }
            lines += "${step.what}: ${step.command.joinToString(" ")} exited 0."
        }
        try {
            vfs.writeBytes(paths.guestMarker, markFor(guest))
        } catch (e: FsException) {
            lines += "${paths.guestMarker} could not be written: ${e.errno.text}. The packages " +
                "are installed, but the next run will install them again because it cannot tell."
            return GuestReport(GuestOutcome.FAILED, guest, run, lines)
        }
        lines += guest.costLine(installed = true)
        return GuestReport(GuestOutcome.INSTALLED, guest, run, lines)
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
     * What goes in the mark: the package set and both measured figures, so a file a user opens in
     * a text editor says what the guest's Debian is holding rather than only that something ran.
     */
    private fun markFor(guest: GuestInstall): ByteArray = buildString {
        appendLine("#omp-guest/v1 — name packages downloadBytes installedKib")
        append(guest.name)
        append(' ')
        append(guest.packages.joinToString(","))
        append(' ')
        append(guest.downloadBytes ?: "unmeasured")
        append(' ')
        appendLine((guest.installedKib ?: "unmeasured").toString())
    }.toByteArray(Charsets.UTF_8)

    companion object {
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
