package omp.vm.doctor

import omp.shell.PlatformServices
import omp.shell.StubPlatformServices
import omp.shell.fs.RealVfs
import omp.vm.guestapi.UpdateOutcome
import omp.vm.provision.Abi
import omp.vm.provision.GuestState
import omp.vm.provision.GuestOutcome
import omp.vm.provision.GuestPackages
import omp.vm.provision.GuestStart
import omp.vm.provision.GuestWeb
import omp.vm.provision.ProvisionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The `guest origin` section, for every state a user can actually be in.
 *
 * **A report whose lines move is a report nobody can diff between two runs**, so every value here
 * is pinned as a whole line rather than matched with `contains`, for the reason
 * [omp.vm.doctor.DoctorTest] gives. The `state:` line in particular has to say *what the state means*
 * and not only name it: "is the guest up, and is it the one answering" is a question with six
 * answers, and `PORT_TAKEN` printed on its own would not answer it.
 *
 * **The records are written by hand rather than by [omp.vm.provision.GuestStart]**, because the
 * subject here is what this command *reads* — including a record naming a state this build does not
 * have, which is the sixth case and the one that keeps the other five honest.
 *
 * **The column is built with the padding the report itself uses** rather than counted out by hand,
 * for the reason [omp.vm.guestapi.AgentUpdateSectionTest] gives.
 */
class GuestOriginSectionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var paths: ProvisionPaths
    private lateinit var services: PlatformServices
    private val port = GuestWeb.RESERVED_PORT
    private val url = GuestWeb.baseUrl(port)

    @Before
    fun setUp() {
        files = folder.newFolder("files")
        val exec = folder.newFolder("lib", "arm64-v8a")
        val stub = StubPlatformServices(
            home = File(files, "home").path,
            initialDir = File(files, "home").path,
            appFiles = files.path,
        )
        stub.props[Doctor.FINGERPRINT] = "google/stub/stub:14/UP1A.231005.007/1:user/release-keys"
        stub.props[Doctor.ABILIST] = Abi.ARM64.abiName
        paths = ProvisionPaths(files.path, exec.path, files.path)
        services = stub
    }

    // ---- 1. no Debian -----------------------------------------------------------------------------

    @Test
    fun aDeviceWithNoDebianSaysNothingWasStartedAndTheScreenIsTheAppsOwn() {
        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.NO_DEBIAN.name}: there is no unpacked Debian on this device, so nothing " +
                    "was started, and the chat the WebView was handed is this app's own loopback server",
            ),
            line(report, "state"),
        )
        assertEquals(
            report.text(),
            say(
                "port",
                "$port, the port this build reserves for Apache inside the Debian, and nothing has " +
                    "tried to reserve it",
            ),
            line(report, "port"),
        )
        assertEquals(
            report.text(),
            say(
                "apache",
                "not asked: this build has not started the guest on this device, and this command " +
                    "starts nothing and asks nothing to find out",
            ),
            line(report, "apache"),
        )
        // An answer about the device, not a gap: there is no guest to ask about, and printing a
        // missing line here would be indistinguishable from a healthy one.
        assertTrue(report.text(), report.gaps.none { it.contains("guest origin") })
    }

    @Test
    fun aDeviceWithNoDebianAlsoNamesTheAppsOwnServerOnTheOriginLine() {
        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "origin",
                "this app's own loopback server: a whole rootfs and the real agent are not both on " +
                    "this device",
            ),
            chatLine(report, "origin"),
        )
    }

    // ---- 2. a Debian that was not started -------------------------------------------------------------

    @Test
    fun aDebianThatWasNeverStartedSaysSoAndRefusesToCallALaunchAGuest() {
        provisionRootfs()

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.NOT_STARTED.name}: a Debian is on this device and this build has not " +
                    "started the guest, so the chat the WebView was handed is this app's own loopback " +
                    "server; a guest that was only launched is never shown as though it were answering",
            ),
            line(report, "state"),
        )
        assertEquals(
            report.text(),
            say(
                "agent",
                "not run: this build has not started the guest on this device, so there is no boot's " +
                    "'omp update' from it to report; the 'agent update' section above is where any " +
                    "other run of it is read",
            ),
            line(report, "agent"),
        )
    }

    // ---- 3. a port somebody else holds ------------------------------------------------------------------

    @Test
    fun aPortSomebodyElseHoldsIsItsOwnStateAndSaysTheGuestsOriginWasRefused() {
        provisionRootfs()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.PORT_TAKEN.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "no",
            GuestStart.KEY_LAUNCHED to "no",
            GuestStart.KEY_ANSWERED to "no",
        )

        val report = doctor().report()

        // The sentence a user is owed after a collision: not "it did not work", and not the app's own
        // server put in the guest's place without saying so.
        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.PORT_TAKEN.name}: something on this phone already holds $url, so Apache " +
                    "was not started at all, and the Debian's origin was refused rather than replaced " +
                    "by this app's own server",
            ),
            line(report, "state"),
        )
        assertEquals(
            report.text(),
            say(
                "port",
                "$port, and the run that started the guest could not reserve it: something on this " +
                    "phone already held it, so Apache was never started on it",
            ),
            line(report, "port"),
        )
        assertEquals(
            report.text(),
            say("apache", "not started: the start launched nothing inside the Debian"),
            line(report, "apache"),
        )
    }

    @Test
    fun aCollisionIsNotReportedAsTheGuestOnTheOriginLine() {
        provisionGuest()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.PORT_TAKEN.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "no",
            GuestStart.KEY_LAUNCHED to "no",
            GuestStart.KEY_ANSWERED to "no",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "origin",
                "this app's own loopback server: Apache inside the Debian is not answering: " +
                    GuestState.PORT_TAKEN.name,
            ),
            chatLine(report, "origin"),
        )
    }

    // ---- 4. started, and nothing came back ----------------------------------------------------------------

    @Test
    fun aGuestThatWasLaunchedAndAnsweredNothingIsReportedWithTheGuestsOwnLastLine() {
        provisionRootfs()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.APACHE_NOT_ANSWERING.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "yes",
            GuestStart.KEY_ANSWERED to "no",
            GuestStart.KEY_SAID to "AH00534: apache2: Configuration error: No MPM loaded",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.APACHE_NOT_ANSWERING.name}: the guest was started and nothing answered " +
                    "a request for this build's chat page on $url, so the Debian's origin was refused " +
                    "rather than replaced by this app's own server",
            ),
            line(report, "state"),
        )
        // The guest's own words, quoted and not paraphrased: the reason Apache did not come up is
        // Apache's to give, and this is the only copy of it anybody will get.
        assertEquals(
            report.text(),
            say(
                "apache",
                "not answering: the start launched apache2 inside the Debian and nothing returned " +
                    "this build's chat document from $url; the guest's own last line was " +
                    "\"AH00534: apache2: Configuration error: No MPM loaded\"",
            ),
            line(report, "apache"),
        )
    }

    @Test
    fun aReservedPortIsReportedAsReservedEvenWhenTheGuestNeverAnswered() {
        provisionRootfs()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.APACHE_NOT_ANSWERING.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "yes",
            GuestStart.KEY_ANSWERED to "no",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "port",
                "$port, reserved by the run that started the guest: it was free, and it was given " +
                    "back so Apache could take it",
            ),
            line(report, "port"),
        )
    }

    // ---- 5. Apache is answering and the agent update did not land -------------------------------------------

    @Test
    fun aGuestThatIsServingWithAnAgentThatDidNotUpdateIsItsOwnStateAndStillServes() {
        provisionGuest()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.AGENT_UPDATE_FAILED.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "yes",
            GuestStart.KEY_ANSWERED to "yes",
            GuestStart.KEY_AGENT to UpdateOutcome.FAILED.name,
            GuestStart.KEY_VERSION to "18.3.5",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.AGENT_UPDATE_FAILED.name}: Apache inside the Debian is answering on " +
                    "$url and the boot's 'omp update' did not land, so the page is the Debian's and " +
                    "the agent inside it is the one that was already there",
            ),
            line(report, "state"),
        )
        assertEquals(
            report.text(),
            say(
                "agent",
                "${UpdateOutcome.FAILED.name}: the agent the Debian's page is talking to is the one " +
                    "that was already there, because `omp update && omp` stopped at the first half — " +
                    "the page is the Debian's all the same; the 'agent update' section above has what " +
                    "it printed",
            ),
            line(report, "agent"),
        )
        // The origin is the guest's. A Debian serving a page with an out-of-date agent in it is
        // still a Debian serving a page, and the `&&` gates the agent and not the web half.
        assertEquals(
            report.text(),
            say(
                "origin",
                "the guest's Apache inside the Debian: a whole rootfs and the real agent are both " +
                    "on this device, and the run that started the guest saw this build's own chat " +
                    "document come back from $url",
            ),
            chatLine(report, "origin"),
        )
    }

    // ---- 6. fully up ------------------------------------------------------------------------------------

    @Test
    fun aGuestThatIsServingWithACurrentAgentSaysSoOnEveryLine() {
        provisionRootfs()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.UP.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "yes",
            GuestStart.KEY_ANSWERED to "yes",
            GuestStart.KEY_AGENT to UpdateOutcome.ALREADY_CURRENT.name,
            GuestStart.KEY_VERSION to "18.3.5",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.UP.name}: Apache inside the Debian is answering on $url and the boot's " +
                    "'omp update' landed, so the page the WebView was handed is the Debian's",
            ),
            line(report, "state"),
        )
        assertEquals(
            report.text(),
            say(
                "apache",
                "answering: the start asked $url for this build's own chat document and got it back",
            ),
            line(report, "apache"),
        )
        assertEquals(
            report.text(),
            say(
                "agent",
                "${UpdateOutcome.ALREADY_CURRENT.name}: the boot's 'omp update' landed at the same " +
                    "start, so the agent the Debian's page is talking to is the one it was brought up " +
                    "to; the 'agent update' section above has what it printed",
            ),
            line(report, "agent"),
        )
    }

    // ---- the seventh case: a record this build did not write -----------------------------------------------

    @Test
    fun aRecordNamingAStateThisBuildDoesNotHaveIsUnreadableAndNamedInTheGaps() {
        provisionRootfs()
        writeRecord(GuestStart.KEY_STATE to "restarting")

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "unreadable: the record at ${recordFile()} names a state 'restarting', which is not " +
                    "one of this build's GuestState " +
                    "(${GuestState.entries.joinToString(", ") { it.name }}), so this build cannot say " +
                    "what the last start of the guest did",
            ),
            line(report, "state"),
        )
        assertTrue(
            report.text(),
            report.gaps.any { it.contains("names a state 'restarting'") },
        )
    }

    // ---- the read-only promise, and the section's place -----------------------------------------------------

    @Test
    fun theSectionOpensNoSocketAndWritesNothingEvenWithAFullRecord() {
        provisionGuest()
        publishUrl()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.UP.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "yes",
            GuestStart.KEY_ANSWERED to "yes",
            GuestStart.KEY_AGENT to UpdateOutcome.ALREADY_CURRENT.name,
        )
        val before = treeOf(folder.root)
        var probes = 0

        val report = Doctor(services, RealVfs(), paths, probe = Doctor.Probe { probes++; true }).report()

        // One probe, and it is the app's own loopback port: whether the guest is answering is read
        // off the record, because a second socket would break the promise this command opens with.
        assertEquals("the one socket in this report is the app's own port", 1, probes)
        assertEquals(report.text(), before, treeOf(folder.root))
    }

    @Test
    fun theSectionPrintsBetweenTheAgentsUpdateAndTheChat() {
        provisionRootfs()
        writeRecord(
            GuestStart.KEY_STATE to GuestState.UP.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_ANSWERED to "yes",
        )

        val heads = doctor().report().lines.filter { it.isNotBlank() && !it.startsWith(" ") }

        assertEquals(
            listOf(
                "omp doctor: read-only — nothing below downloads, writes, starts or stops anything",
                "identity",
                "guest",
                "helper",
                "provisioning",
                "guest state",
                Doctor.SECTION_UPDATE,
                Doctor.SECTION_ORIGIN,
                "chat",
                "gaps",
                "next",
            ),
            heads,
        )
    }

    @Test
    fun aGuestWithNoAgentOnDiskIsStillReportedByWhatTheStartFound() {
        // A 32-bit device: a real Debian, no agent and never one. Apache, its PHP API and its document
        // root do not need the agent binary, so the guest can be serving — and the `origin:` line is
        // still the app's own server, because that is a fact about the disk and not a second opinion
        // about the guest. Two different questions, two different lines.
        writeRecord(
            GuestStart.KEY_STATE to GuestState.UP.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "yes",
            GuestStart.KEY_ANSWERED to "yes",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "origin",
                "this app's own loopback server: a whole rootfs and the real agent are not both on " +
                    "this device",
            ),
            chatLine(report, "origin"),
        )
        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.UP.name}: Apache inside the Debian is answering on $url and the boot's " +
                    "'omp update' landed, so the page the WebView was handed is the Debian's",
            ),
            line(report, "state"),
        )
    }

    @Test
    fun aStateThisBuildDoesNotHaveIsNeverCountedAsAServingGuest() {
        // A record from a newer build that says `answered yes` and a state this one does not have.
        // The safe answer is the app's own server, and the reason line must name the record rather
        // than claim a guest is not answering.
        provisionGuest()
        writeRecord(
            GuestStart.KEY_STATE to "restarting",
            GuestStart.KEY_ANSWERED to "yes",
        )

        val report = doctor().report()

        assertTrue(
            report.text(),
            chatLine(report, "origin").contains("the run that started the guest recorded a state this build does not have"),
        )
    }


    // ---- the guest's own LAMP, which is the commonest answer to "why is the chat from the app" -------

    @Test
    fun aDebianWithNoLampAndNoRecordSaysThePackagesAreNotInstalled() {
        // The state a device provisioned today is in: a rootfs, an agent, and no `apache2-bin`.
        provisionGuest()

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.LAMP_MISSING.name}: a Debian and the real agent are on this device and " +
                    "the guest's own LAMP is not, so there is no apache2 inside it to start, and the " +
                    "chat the WebView was handed is this app's own loopback server; the Debian's " +
                    "origin was refused rather than replaced by it",
            ),
            line(report, "state"),
        )
        assertEquals(
            report.text(),
            say(
                "packages",
                "not installed: no .omp-guest-packages in ${paths.rootfsDir}, and no record of an " +
                    "install at ${omp.vm.provision.installRecordFile(paths)} — nothing has been " +
                    "fetched for them",
            ),
            line(report, "packages"),
        )
        assertEquals(
            report.text(),
            say(
                "origin",
                "this app's own loopback server: this build has not started the guest on this " +
                    "device, and a guest that was only launched is never shown as though it were " +
                    "answering",
            ),
            chatLine(report, "origin"),
        )
    }

    @Test
    fun aRunOfAptThatIsGoingOnRightNowIsItsOwnStateAndSaysSo() {
        // Read from the record the install writes *before* its first step, which is the only way the
        // run this command cannot see is an answer rather than a silence.
        provisionGuest()
        writeInstallRecord(
            GuestPackages.KEY_OUTCOME to GuestPackages.INSTALLING,
            GuestPackages.KEY_SAID to "apt is running inside the Debian now",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.LAMP_INSTALLING.name}: an install of the guest's LAMP was running " +
                    "inside the Debian when the app that started it stopped, so this start ran it " +
                    "again and apt continued from what it had already unpacked; the chat the " +
                    "WebView was handed is this app's own loopback server",
            ),
            line(report, "state"),
        )
        assertTrue(
            report.text(),
            line(report, "packages").contains("the last run was INSTALLING"),
        )
    }

    @Test
    fun aFailedInstallIsReportedWithTheCommandAndTheGestsOwnLastLine() {
        provisionGuest()
        writeInstallRecord(
            GuestPackages.KEY_OUTCOME to GuestOutcome.FAILED.name,
            GuestPackages.KEY_STEP to "/usr/bin/apt-get install -y apache2-bin libapache2-mod-php8.4 " +
                "php8.4-cli mariadb-server",
            GuestPackages.KEY_SAID to "E: Sub-process /usr/bin/dpkg returned an error code",
        )
        writeRecord(
            GuestStart.KEY_STATE to GuestState.LAMP_FAILED.name,
            GuestStart.KEY_PORT to "$port",
            GuestStart.KEY_RESERVED to "yes",
            GuestStart.KEY_LAUNCHED to "no",
            GuestStart.KEY_ANSWERED to "no",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "state",
                "${GuestState.LAMP_FAILED.name}: the guest's own LAMP did not finish installing, so " +
                    "whatever is half on the disk is not a web server this build will start, and the " +
                    "chat the WebView was handed is this app's own loopback server; the Debian's " +
                    "origin was refused rather than replaced by it",
            ),
            line(report, "state"),
        )
        // Half an installed stack, named as half, with the reason quoted rather than paraphrased.
        val packages = line(report, "packages")
        assertTrue(packages, packages.contains("the last run was FAILED at"))
        assertTrue(packages, packages.contains("returned an error code"))
        assertTrue(packages, packages.contains("not a web server this build will start"))
        // And it is not the origin: a device whose LAMP failed serves from the app, and says so.
        assertTrue(chatLine(report, "origin").contains("this app's own loopback server"))
    }

    @Test
    fun anInstallStoppedAtTheBoundIsReportedAsStoppedAndNotAsFailed() {
        provisionGuest()
        writeInstallRecord(
            GuestPackages.KEY_OUTCOME to GuestOutcome.STOPPED.name,
            GuestPackages.KEY_STEP to "/usr/bin/apt-get install -y apache2-bin",
        )
        writeRecord(
            GuestStart.KEY_STATE to GuestState.LAMP_FAILED.name,
            GuestStart.KEY_ANSWERED to "no",
        )

        val report = doctor().report()

        // The two are told apart by the name and not by the number, exactly as
        // `omp.vm.guestapi.UpdateOutcome` tells TIMED_OUT apart from FAILED.
        assertTrue(line(report, "packages").contains("the last run was STOPPED"))
    }

    @Test
    fun anInstalledStackReportsWhatDpkgSaysNextToWhatTheManifestEstimated() {
        provisionGuest()
        File(paths.rootfsDir, ProvisionPaths.GUEST_MARKER).writeText("#omp-guest/v1\n")
        writeInstallRecord(
            GuestPackages.KEY_OUTCOME to GuestOutcome.INSTALLED.name,
            GuestPackages.KEY_ESTIMATED_DOWNLOAD to "57121704",
            GuestPackages.KEY_ESTIMATED_INSTALLED to "385689",
            GuestPackages.KEY_MEASURED_INSTALLED to "412880",
        )

        val report = doctor().report()

        // The real figure beside the estimate, on every ABI, read out of dpkg's own status file with
        // no guest process and no network.
        assertEquals(
            report.text(),
            say(
                "packages",
                "installed: .omp-guest-packages is in ${paths.rootfsDir}, and dpkg reports " +
                    "403.2 MiB (422,789,120 bytes) against the 376.6 MiB (394,945,536 bytes) the " +
                    "manifest estimated on arm64",
            ),
            line(report, "packages"),
        )
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun doctor() = Doctor(services, RealVfs(), paths, probe = Doctor.Probe { false })

    private fun recordFile() = omp.vm.provision.recordFile(paths)

    /**
     * One line of the `guest origin` section.
     *
     * **Scoped to the section because three of its keys are keys in other sections too** — `port:` is
     * in `chat` and `agent:` is not, but `state:` would be — and a helper that took the first match in
     * the report would quietly assert about the wrong line.
     */
    private fun line(report: Doctor.Report, key: String): String =
        inSection(report, Doctor.SECTION_ORIGIN, key)

    /**
     * The same, for the `chat` section, whose `origin:` line is a different sentence about a
     * different thing and must not be confused with anything in `guest origin`.
     */
    private fun chatLine(report: Doctor.Report, key: String): String = inSection(report, "chat", key)

    /** One `key:` line inside one named section, and never one from another section. */
    private fun inSection(report: Doctor.Report, section: String, key: String): String {
        val head = report.lines.indexOf(section)
        assertTrue("the '$section' section is missing from the report", head >= 0)
        // Up to the blank line that ends a section: a report's sections are separated by one, and
        // `port:` is a key in two of them.
        val body = report.lines.drop(head + 1).takeWhile { it.isNotBlank() }
        return body.firstOrNull { it.startsWith("  $key:") }
            ?: throw AssertionError("no '$key:' line in the '$section' section of:\n" + body.joinToString("\n"))
    }

    /** One `key: value` as the report writes it, so no column is counted out by hand here. */
    private fun say(key: String, value: String) = "  " + (key + ":").padEnd(Doctor.WIDTH) + value

    /** A Debian and nothing else: the state of every device that has been unpacked and not used. */
    private fun provisionRootfs() {
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsDir, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
    }

    /** The above and the real agent, which is what makes the guest's origin eligible at all. */
    private fun provisionGuest() {
        provisionRootfs()
        File(paths.agentDir).mkdirs()
        File(paths.agentBinary).writeBytes(ByteArray(1_024))
    }

    /** The address the chat service publishes, without which the one probe has nothing to ask. */
    private fun publishUrl() {
        File(files, "web").mkdirs()
        File(files, "web/url").writeText("http://127.0.0.1:8731/login?t=a-32-character-test-token\n")
    }

    private fun writeInstallRecord(vararg lines: Pair<String, String>) {
        File(paths.downloadDir).mkdirs()
        File(omp.vm.provision.installRecordFile(paths)).writeText(
            buildString {
                append(GuestPackages.RECORD_HEADER).append('\n')
                for ((key, value) in lines) append(key).append(' ').append(value).append('\n')
            },
        )
    }

    private fun writeRecord(vararg lines: Pair<String, String>) {
        File(paths.downloadDir).mkdirs()
        File(recordFile()).writeText(
            buildString {
                append("#omp-guest-origin/v1 — written by omp.vm.provision.GuestStart\n")
                for ((key, value) in lines) append(key).append(' ').append(value).append('\n')
            },
        )
    }

    /** Every file under [root] with its length, so "nothing was written" is a fact. */
    private fun treeOf(root: File): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        root.walkTopDown().forEach { out[it.path] = if (it.isFile) it.length() else -1L }
        return out
    }
}
