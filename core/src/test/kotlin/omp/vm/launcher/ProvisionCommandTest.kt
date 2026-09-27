package omp.vm.launcher

import omp.shell.exec.CommandTable
import omp.vm.VmHarness
import omp.vm.VmPhone
import omp.vm.provision.Abi
import omp.vm.provision.Artifact
import omp.vm.provision.ArtifactManifest
import omp.vm.provision.FakeTransport
import omp.vm.provision.GuestInstall
import omp.vm.provision.Phase
import omp.vm.provision.ProvisionOutcome
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionStatus
import omp.vm.provision.ProvisionStatusHolder
import omp.vm.provision.Tar
import omp.vm.provision.Transport
import omp.vm.provision.sha256Of
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.Random

/**
 * `omp provision`, driven the way a person drives it, over the real [omp.shell.exec.Shell] and the
 * real [omp.vm.provision.Provisioner].
 *
 * **The only thing that is faked is the wire.** Every assertion here is about what a user sees and
 * about what is or is not on the disk afterwards, and the disk is checked with [java.io.File]
 * rather than through the seam — because the promise this command makes is not "it printed a cost",
 * it is "it did not fetch a byte until a key was pressed, and what it fetched is where it said it
 * would be", and a test that only went through the [omp.shell.fs.Vfs] could not tell a bind from a
 * copy.
 *
 * **The table is the shipped one wherever the shipped one is the point.** [ArtifactManifest.of]
 * pins 58,379,360 bytes of Debian and 234,866,984 bytes of agent with sha256 digests no test can
 * reproduce, so the runs that have to finish are handed [smallManifest] — the same class, the same
 * computations over small numbers — and the tests about what a user is told about the cost use the
 * real figures and assert them.
 *
 * [phone] is the shell on the phone over the device's own filesystem and [harness] is a second one
 * inside the namespace, because "it must refuse in the VM" is only a claim if the refusal is
 * produced by a session that really is in the VM.
 */
class ProvisionCommandTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A booted namespace over the same temp directory the phone sees. */
    private lateinit var harness: VmHarness

    /** The phone: the session that owns `omp`, with a wire and a table of this test's own. */
    private lateinit var phone: VmPhone

    /** The body the "server" has, and the record of every `(url, from)` it was asked for. */
    private lateinit var wire: FakeTransport

    /** The paths the command derives from the platform, so the test asserts about the real ones. */
    private lateinit var paths: ProvisionPaths

    @Before
    fun setUp() {
        ProvisionStatusHolder.clear()
        harness = VmHarness(folder.root)
        // The property Android writes from `Build.SUPPORTED_ABIS`, in the order it wrote it. The
        // command reads the ABI from here and not from `os.arch`, and a test that left it out would
        // be testing this machine's JVM rather than a device.
        harness.stub.props[ABILIST] = Abi.ARM64.abiName
        wire = bodies()
        paths = ProvisionPaths.inAppStorage(harness.services)
        phone = VmPhone(harness.services, table(wire, ::smallManifest))
    }

    @After
    fun tearDown() {
        ProvisionStatusHolder.clear()
    }

    // ---- the cost, before a byte moves ----------------------------------------------------------

    @Test
    fun theCostIsPrintedBeforeAnythingMovesAndItIsTheManifestsOwnSentence() {
        val real = VmPhone(harness.services, table(wire, ArtifactManifest::of))

        val stopped = real.answered("n")

        val out = stopped.out
        val cost = out.indexOf(ArtifactManifest.of(Abi.ARM64).describe())
        assertTrue("the cost was never printed:\n$out", cost >= 0)
        // The wire total, the LAMP share the guest fetches for itself afterwards, and the room the
        // run needs on the device: the three figures a user is agreeing to, and every one of them
        // out of the manifest's own sentence rather than a rounding of it.
        assertTrue(out, out.contains("350,458,048 bytes"))
        assertTrue(out, out.contains("334.2 MiB"))
        assertTrue(out, out.contains("57,211,704 bytes"))
        assertTrue(out, out.contains("745,403,584 bytes"))
        assertTrue(out, out.contains("LAMP"))
        assertTrue("the question comes after the cost", out.indexOf("type y to download that now") > cost)
        assertEquals("no request may be made before the answer", 0, wire.requests.size)
        assertEquals(1, stopped.status)
    }

    @Test
    fun aPipedRunPrintsTheCostAndTheRefusalAndReturnsInsteadOfWaiting() {
        // The shipped table again, because what has to be on screen before the refusal is the cost.
        val real = VmPhone(harness.services, table(wire, ArtifactManifest::of))
        val piped = real.runPiped("omp provision")

        assertEquals(1, piped.status)
        assertTrue(piped.out, piped.out.contains("350,458,048 bytes"))
        assertTrue(piped.out, piped.out.contains("stdin is not the terminal this command owns"))
        assertTrue(piped.out, piped.out.contains("nothing is downloaded"))
        assertTrue(piped.out, piped.out.contains("outcome: REFUSED"))
        assertTrue("a pipe cannot answer, so nothing may be fetched", wire.requests.isEmpty())

        // `vm exec` is the same rule from the other side: a throwaway session inside the namespace.
        val execed = phone.run("vm exec 'omp provision'")
        assertEquals(1, execed.status)
        assertTrue(execed.out, execed.out.contains("this session is inside the VM"))
        assertTrue(wire.requests.isEmpty())
    }

    @Test
    fun anArgumentThisVerbDoesNotHaveIsAUsageErrorAndDownloadsNothing() {
        val forced = phone.run("omp provision --force")

        assertEquals(2, forced.status)
        assertTrue(forced.err, forced.err.contains("unknown option '--force'"))
        assertTrue(forced.err, forced.err.contains("usage: omp provision [--yes]"))
        assertTrue(wire.requests.isEmpty())
    }

    // ---- the answer, and what each one does ------------------------------------------------------

    @Test
    fun aYProvisionsAndTheLastLineSaysTheRealAgentIsAnswering() {
        val done = phone.answered("y")

        assertEquals(done.out, 0, done.status)
        assertTrue(done.out, done.out.contains("outcome: PROVISIONED"))
        // The bytes are where the command said they would be. A progress line counted real bytes:
        // both counts are the manifest's own figures, so a reader can do the arithmetic themselves.
        assertTrue(done.out, done.out.contains("${ROOTFS}: 0 bytes of ${humanBytes(rootfsBytes.size.toLong())}"))
        assertTrue(
            done.out,
            done.out.contains(
                "${ROOTFS}: ${humanBytes(rootfsBytes.size.toLong())} of " +
                    "${humanBytes(rootfsBytes.size.toLong())} — downloaded",
            ),
        )
        assertTrue(done.out, done.out.contains("${ROOTFS}: verifying"))
        assertTrue(done.out, done.out.contains("${ROOTFS}: unpacking into ${paths.rootfsDir}"))
        assertTrue(done.out, done.out.contains("${AGENT}: installing into ${paths.agentBinary}"))
        // The report's own lines, in the order the report collected them.
        assertTrue(done.out, done.out.contains("sha256 ${sha256Of(agentBytes).take(16)}"))
        assertTrue(done.out, done.out.contains("unpacked $ROOTFS into ${paths.rootfsDir}"))
        // The disk, checked the way a file manager would.
        val rootfs = File(paths.rootfsDir, "usr/bin/ls")
        assertTrue(rootfs.path, rootfs.isFile)
        assertEquals("ELF-ish", rootfs.readText())
        val agent = File(paths.agentBinary)
        assertTrue(agent.path, agent.isFile)
        assertTrue("the loader execs a file whose mode is right", agent.canExecute())
        // And the last thing on the screen is which of the two agents is answering.
        val last = done.out.trim().lines().last()
        assertTrue(last, last.startsWith("answering now: the real omp ${ArtifactManifest.AGENT_RELEASE}"))
        assertTrue(last, last.contains(paths.rootfsDir))
    }

    @Test
    fun anythingElseButYStopsAtTheQuestionAndCreatesNothing() {
        val stopped = phone.answered("maybe")

        assertEquals(1, stopped.status)
        assertTrue(stopped.out, stopped.out.contains("stopped at the question"))
        assertTrue(stopped.out, stopped.out.contains("outcome: REFUSED"))
        assertTrue("nothing may be fetched", wire.requests.isEmpty())
        assertFalse("no install directory at all", File(paths.installDir).exists())
        assertFalse("no download directory at all", File(paths.downloadDir).exists())
        assertTrue(stopped.out.trim().lines().last().contains("the Kotlin agent"))
    }

    @Test
    fun ctrlCAtTheQuestionLeavesNothingBehind() {
        phone.input.feed(byteArrayOf(0x03))

        val cancelled = phone.runInteractive("omp provision")

        assertEquals(130, cancelled.status)
        assertTrue(cancelled.out, cancelled.out.contains("the question was cancelled"))
        assertTrue(cancelled.out, cancelled.out.contains("outcome: CANCELLED"))
        assertTrue(cancelled.out, cancelled.out.contains("there is nothing half-finished to keep"))
        assertTrue(wire.requests.isEmpty())
        assertFalse(File(paths.installDir).exists())
        assertFalse(File(paths.downloadDir).exists())
    }

    @Test
    fun yesOnAPipeProceedsAndSaysOutLoudThatNobodyWasAsked() {
        val run = phone.runPiped("omp provision --yes")

        assertEquals(0, run.status)
        assertTrue(run.out, run.out.contains("--yes: the question was not asked"))
        assertTrue(run.out, run.out.contains("without a keystroke from you"))
        // The cost is printed on this path too: a flag that answers the question does not get to
        // hide it, or an automated run would spend money nobody was shown the price of.
        assertTrue(run.out, run.out.contains("${smallManifest(Abi.ARM64).totalBytes} bytes"))
        assertEquals("the two artifacts, once each", 2, wire.requests.size)
        assertTrue(File(paths.agentBinary).isFile)
    }

    // ---- cancellation, and the next run that continues it ------------------------------------------

    @Test
    fun ctrlCMidwayThroughADownloadKeepsWhatArrivedAndTheNextRunContinuesIt() {
        // The cancel is the shell's own flag, raised the way Ctrl-C raises it: by the foreground
        // job this command is running in. The transport raises it once a buffer has been written, so
        // the flag goes up with bytes on the disk and not before — which is the only ordering in
        // which "what arrived is kept" can be a sentence about a file.
        lateinit var shell: VmPhone
        val slow = SlowTransport(bodyMap()) { url, sent ->
            if (url == ROOTFS_URL && sent >= 64L * 1024L) cancelForeground { shell }
        }
        shell = VmPhone(harness.services, table(slow, ::smallManifest))

        val cancelled = shell.answered("y")

        assertEquals(cancelled.out, 130, cancelled.status)
        assertTrue(cancelled.out, cancelled.out.contains("outcome: CANCELLED"))
        // "cancelled" and "lost" are different words, and this is the line that says which it was.
        assertTrue(cancelled.out, cancelled.out.contains("what arrived is kept"))
        assertTrue(cancelled.out, cancelled.out.contains("Nothing of this download was thrown away"))
        val partial = File(paths.downloadDir, "$ROOTFS.part")
        assertTrue(cancelled.out, cancelled.out.contains(partial.path))
        val kept = partial.length()
        assertTrue("nothing was kept on the disk", kept > 0L)
        assertTrue("the resume record is kept too", File(paths.stateFile).isFile)
        assertFalse(
            "a half-downloaded rootfs is never unpacked",
            File(paths.rootfsMarker).exists(),
        )
        assertTrue(cancelled.out.trim().lines().last().contains("the Kotlin agent"))

        // And the next run picks up at exactly that offset, which is the whole point of keeping it.
        val second = phone.answered("y")

        assertEquals(second.out, 0, second.status)
        assertTrue(second.out, second.out.contains("$ROOTFS: resuming at ${humanBytes(kept)} of "))
        assertEquals(
            "the server is asked for the rest, not for the whole file again",
            kept,
            wire.requests[0].second,
        )
        assertEquals("and the rootfs is whole this time", "ELF-ish", File(paths.rootfsDir, "usr/bin/ls").readText())
    }

    @Test
    fun aResumedDownloadSaysItIsResumingAndFromWhere() {
        val first = rootfsBytes.size / 2
        File(paths.downloadDir).mkdirs()
        File(paths.downloadDir, "$ROOTFS.part").writeBytes(rootfsBytes.copyOf(first))

        val done = phone.answered("y")

        assertEquals(0, done.status)
        assertTrue(done.out, done.out.contains("$ROOTFS: resuming at ${humanBytes(first.toLong())} of "))
        assertEquals(
            "the range asked for is the length of the .part file",
            first.toLong(),
            wire.requests[0].second,
        )
        assertEquals("the two halves made the whole Debian", "ELF-ish", File(paths.rootfsDir, "usr/bin/ls").readText())
    }

    @Test
    fun anAlreadyProvisionedDeviceIsOneLineAndNoDownload() {
        assertEquals(0, phone.answered("y").status)
        val fetched = wire.requests.size

        val again = phone.answered("y")

        assertEquals(0, again.status)
        assertTrue(again.out, again.out.contains("is already provisioned"))
        assertTrue(again.out, again.out.contains("Nothing was downloaded"))
        // The question is never asked, because a user must not be asked to agree to bytes that are
        // already on the phone — and a device that is whole gets one line, not a cost and a prompt.
        assertFalse(again.out, again.out.contains("type y to download that now"))
        assertFalse(again.out, again.out.contains("over the network"))
        assertEquals("a second run that re-downloads spends a user's data again", fetched, wire.requests.size)
        assertTrue(again.out.trim().lines().last().startsWith("answering now: the real omp"))
    }

    // ---- where it refuses, and why ----------------------------------------------------------------

    @Test
    fun aThirtyTwoBitAbiRefusesWithTheManifestsOwnSentenceAndDownloadsNothing() {
        // The shipped table, because the gap it reports is the shipped one: a 32-bit user is owed
        // the sentence the manifest gives and not a second wording of it.
        val real = VmPhone(harness.services, table(wire, ArtifactManifest::of))
        for (abi in listOf(Abi.ARMEABI_V7A, Abi.X86)) {
            ProvisionStatusHolder.clear()
            harness.stub.props[ABILIST] = abi.abiName

            val refused = real.answered("y")

            val gap = ArtifactManifest.of(abi).gap
            assertNotNull("$abi has a gap to report", gap)
            assertEquals(refused.out, 1, refused.status)
            assertTrue(refused.out, refused.out.contains(gap!!))
            assertTrue(refused.out, refused.out.contains("omp provision: outcome: REFUSED"))
            assertFalse("the question is never reached", refused.out.contains("type y to download"))
            assertEquals("$abi must not be fetched from", 0, wire.requests.size)
            assertFalse(File(paths.installDir).exists())
            assertTrue(refused.out.trim().lines().last().contains("the Kotlin agent"))
        }
    }

    @Test
    fun insideTheNamespaceItIsOneLineSayingItIsDoneFromThePhoneAndNothingMoves() {
        // The same command object, in the same table, in a session that really is in the VM.
        harness.table.register(OmpCommand(ProvisionCommand(wire, ::smallManifest)))

        val refused = harness.run("omp provision")

        assertEquals(refused.out, 1, refused.status)
        val said = refused.out.lines().filter { it.startsWith("omp provision:") }
        assertEquals("a refusal in the VM is one line, then the outcome:\n${refused.out}", 2, said.size)
        assertTrue(said[0], said[0].startsWith("omp provision: this session is inside the VM"))
        assertTrue(said[0], said[0].endsWith("provisioned from the phone's own shell."))
        // It names no flag and promises nothing.
        assertFalse(refused.out, refused.out.contains("--yes"))
        assertEquals("nothing may be fetched from inside the namespace", 0, wire.requests.size)
        assertFalse(File(paths.installDir).exists())
        assertTrue(refused.out.trim().lines().last().contains("the Kotlin agent"))
    }

    @Test
    fun insideAConversationItRefusesBecauseAnAgentDoesNotFetchADebian() {
        // `omp new` leaves the session standing in the folder, which is the state a question has to
        // be refused in: the environment names a conversation and the cwd is inside it.
        assertEquals(0, phone.run("omp new photos").status)

        val refused = phone.answered("y")

        assertEquals(refused.out, 1, refused.status)
        assertTrue(
            refused.out,
            refused.out.contains("inside a conversation 'omp' is the coding agent"),
        )
        assertTrue(refused.out, refused.out.contains("provisioned from the phone's own shell"))
        assertEquals("nothing may be fetched from a conversation", 0, wire.requests.size)
        assertFalse(File(paths.installDir).exists())
    }

    // ---- the shared status a run publishes -----------------------------------------------------------

    @Test
    fun aRunPublishesItsPhaseAndItsBytesAndStopsPublishingWhenItEnds() {
        // Caught from the wire, mid-download, on the same thread the command is on: what the
        // notification and the state route read while a run is going.
        var seen: ProvisionStatus? = null
        val slow = SlowTransport(bodyMap()) { _, sent ->
            if (seen == null && sent >= 64L * 1024L) seen = ProvisionStatusHolder.current
        }
        val shell = VmPhone(harness.services, table(slow, ::smallManifest))

        val done = shell.runPiped("omp provision --yes")

        assertEquals(0, done.status)
        val running = seen
        assertNotNull("nothing was published while the run was going", running)
        assertTrue("a run in progress says so", running!!.running)
        assertEquals(Abi.ARM64.abiName, running.abi)
        assertEquals(Phase.DOWNLOADING, running.phase)
        assertEquals(ROOTFS, running.artifact)
        assertTrue("bytes are counted from what the downloader wrote", running.receivedBytes > 0L)
        assertTrue("and a run in progress is not a finished one", running.receivedBytes < running.totalBytes)
        assertEquals(rootfsBytes.size.toLong(), running.totalBytes)
        // Both counts are the manifest's own spelling and there is no percentage anywhere in it.
        val line = running.line()
        assertNotNull(line)
        assertEquals(
            "provisioning: downloading $ROOTFS — ${humanBytes(running.receivedBytes)} of " +
                humanBytes(rootfsBytes.size.toLong()),
            line,
        )
        assertFalse(line!!.contains('%'))

        // And when the run ends the slot stops being a run: no line for a notification to show, and
        // the outcome kept so a state route can report how it went.
        val ended = ProvisionStatusHolder.current
        assertFalse("a finished run is not a running one", ended.running)
        assertNull("a run that is not running has no line", ended.line())
        assertEquals(ProvisionOutcome.PROVISIONED, ended.outcome)
        // The artifact it stopped on is the last one it touched, which after a whole run is the
        // agent: a state route that named the rootfs here would be reporting a step two minutes old.
        assertEquals(AGENT, ended.artifact)
    }

    // ---- the table, and the fixtures behind it -------------------------------------------------------

    /**
     * A command table with the real `omp` in it, wired to the transport and the table under test.
     *
     * The global table is copied rather than changed, so a test in this file cannot leave the next
     * one a transport of its own — the same thing [omp.vm.VmKernel] does for the namespace, and the
     * reason this is a copy and not a second `registerDefaults()`.
     */
    private fun table(wire: Transport, artifacts: (Abi) -> ArtifactManifest): CommandTable =
        CommandTable.global.copy().register(OmpCommand(ProvisionCommand(wire, artifacts)))

    /**
     * The shipped table with the two artifacts shrunk to what this machine can hold.
     *
     * Everything the command reads off a table — [ArtifactManifest.gap], [ArtifactManifest.describe],
     * [ArtifactManifest.totalBytes], [ArtifactManifest.requiredBytes] — is the shipped computation
     * over these numbers, so a test that asserts on the output is asserting on the real arithmetic.
     */
    private fun smallManifest(abi: Abi): ArtifactManifest = ArtifactManifest(
        abi = abi,
        rootfs = Artifact(ROOTFS, ROOTFS_URL, rootfsBytes.size.toLong(), null),
        agent = Artifact(AGENT, AGENT_URL, agentBytes.size.toLong(), sha256Of(agentBytes)),
        // Real measured-shape figures, small: the LAMP cost is part of what a user is shown, and a
        // zero here would make that sentence read as though there were nothing to agree to.
        guest = GuestInstall("LAMP", emptyList(), 1_024L, 512L),
    )

    private fun bodies(): FakeTransport = FakeTransport().apply {
        bodies[ROOTFS_URL] = rootfsBytes
        bodies[AGENT_URL] = agentBytes
    }

    /** The same two bodies as a map, for the transport that serves them in small pieces. */
    private fun bodyMap(): Map<String, ByteArray> =
        mapOf(ROOTFS_URL to rootfsBytes, AGENT_URL to agentBytes)

    /**
     * Raises the shell's cancel flag on whatever the foreground job is.
     *
     * Spins on [omp.shell.Session.foreground] because the reference is an ordinary field and the
     * thread reading it is a stage of the pipeline: reading it once and giving up would be a test
     * that passes only when the memory happens to be in step.
     */
    private fun cancelForeground(phone: () -> VmPhone) {
        var job = phone().shell.session.foreground
        var spins = 0
        while (job == null && spins++ < 10_000) {
            Thread.onSpinWait()
            job = phone().shell.session.foreground
        }
        job?.cancel()
    }

    /** A byte count as the manifest spells one, so no test in this file writes the format itself. */
    private fun humanBytes(count: Long): String = ArtifactManifest.humanBytes(count)

    private val agentBytes = "#!glibc\nomp".toByteArray()

    /** The smallest honest Debian: a directory, an executable, a library, a conffile and a symlink. */
    private val rootfsBytes = Tar.gz(
        Tar.directory("usr"),
        Tar.directory("usr/bin"),
        Tar.file("usr/bin/ls", "ELF-ish".toByteArray(), 0x1ED),
        Tar.file("usr/lib/libc.so.6", Random(7).let { r -> ByteArray(300_000).also(r::nextBytes) }),
        Tar.file("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux\"\n".toByteArray()),
        Tar.symlink("bin/sh", "usr/bin/ls"),
    )

    /**
     * A transport that hands a body over in small pieces and reports how far it has got, so a test
     * can cancel a download that genuinely is still going and can look at the app's own state
     * mid-transfer.
     *
     * **A cancellation is the one thing a fake cannot be written for lazily.** A body that arrives
     * in one read is finished before the test thread has looked, so the flag would always go up
     * after the download and the case would never be exercised. This one is the real
     * [omp.vm.provision.Transport] with a throttled body and nothing else changed: the same
     * `open`/`status`/`from` contract [omp.vm.provision.HttpTransport] implements, so the
     * [omp.vm.provision.Provisioner] walks the same code either way.
     *
     * @param onBytes called with the url and the running total after every read.
     */
    private class SlowTransport(
        private val bodies: Map<String, ByteArray>,
        private val chunk: Int = 4096,
        private val onBytes: (url: String, sent: Long) -> Unit,
    ) : Transport {
        override fun open(url: String, from: Long): Transport.Connection {
            val body = bodies[url] ?: throw java.io.IOException("no body for $url")
            val start = minOf(from, body.size.toLong()).toInt()
            var sent = start.toLong()
            val ranged = from > 0L && start.toLong() == from
            return object : Transport.Connection {
                override val status: Int = if (ranged) 206 else 200
                override val from: Long = if (ranged) start.toLong() else 0L
                override val totalBytes: Long = body.size.toLong()
                override fun body(): InputStream = object : InputStream() {
                    private var at = start

                    override fun read(): Int {
                        if (at >= body.size) return -1
                        sent++
                        onBytes(url, sent)
                        return body[at++].toInt() and 0xff
                    }

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (at >= body.size) return -1
                        val count = minOf(minOf(chunk, length), body.size - at)
                        System.arraycopy(body, at, bytes, offset, count)
                        at += count
                        sent += count
                        onBytes(url, sent)
                        return count
                    }
                }

                override fun close() = Unit
            }
        }
    }

    private companion object {
        const val ABILIST = "ro.product.cpu.abilist"
        const val ROOTFS = "debian-trixie-rootfs-arm64"
        const val AGENT = "omp-linux-arm64"
        const val ROOTFS_URL = "https://deb.debian.org/debian/netboot/debian-13.1.0/arm64/linux"
        const val AGENT_URL =
            "https://github.com/can1357/oh-my-pi/releases/download/v18.3.4/omp-linux-arm64"
    }
}

/**
 * One line with the shell's own [omp.shell.InputChannel] as stdin, with the answer fed first.
 *
 * The answer is fed before the command starts rather than after, because the command reads one byte
 * at a time from the very channel the line editor reads — the arrangement [LauncherTest] uses for
 * `omp`'s own prompt, and the one a person on a phone actually has: the key is pressed and the
 * command that is already waiting sees it.
 */
private fun VmPhone.answered(answer: String): VmPhone.Result {
    input.feed(answer.toByteArray(Charsets.UTF_8) + "\r".toByteArray(Charsets.UTF_8))
    return runInteractive("omp provision")
}
