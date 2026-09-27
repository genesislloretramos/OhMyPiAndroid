package omp.vm.doctor

import omp.shell.PlatformServices
import omp.shell.StubPlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.VEntry
import omp.shell.fs.Vfs
import omp.shell.fs.VDiskUsage
import omp.vm.provision.Abi
import omp.vm.provision.ArtifactManifest
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionStatus
import omp.vm.provision.ProvisionStatusHolder
import omp.vm.provision.WebRoot
import omp.vm.guestapi.AgentUpdate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The report, pinned line by line, over a real filesystem and a real archive.
 *
 * **Every assertion here is on the exact line, because the whole value of this command is that a
 * person can read it and paste it into a bug.** A test that asserted `out.contains("arm64")` would
 * pass on a report that had every fact in it and no fact in the right place, and a report whose
 * lines move is a report nobody can diff between two runs.
 *
 * The setup is a [omp.shell.fs.RealVfs] over a temp directory and a [StubPlatformServices] with the
 * two properties the command reads: `ro.product.cpu.abilist` (which is what
 * [omp.shell.AndroidPlatformServices] writes from `Build.SUPPORTED_ABIS`) and a package path
 * pointing at a zip this test builds. The zip is a real archive read by the real
 * [omp.shell.fs.Vfs], so the "is this build's own copy" answer is a comparison of bytes and not
 * of a stub's answer about bytes.
 */
class DoctorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var work: File
    private lateinit var exec: File
    private lateinit var stub: StubPlatformServices
    private lateinit var paths: ProvisionPaths
    private lateinit var services: PlatformServices
    private lateinit var apk: File

    /** The three pages, as this build's own copy of them. */
    private val pages = mapOf(
        "index.html" to "<!doctype html>\n<title>omp</title>\n".toByteArray(),
        "app.css" to ":root { --bg: #101216; }\n".toByteArray(),
        "app.js" to "// the page's own script, with no dependency in it\n".toByteArray(),
    )

    @Before
    fun setUp() {
        files = folder.newFolder("files")
        // The ordinary arrangement `ProvisionPaths.inAppStorage` builds: the payload and the
        // download work are both under the app's own files directory, which is what a device has.
        work = files
        exec = folder.newFolder("lib", "arm64-v8a")
        stub = StubPlatformServices(home = File(files, "home").path, initialDir = File(files, "home").path, appFiles = files.path)
        stub.props[Doctor.FINGERPRINT] = "google/stub/stub:14/UP1A.231005.007/1:user/release-keys"
        stub.props[Doctor.ABILIST] = Abi.ARM64.abiName
        paths = ProvisionPaths(files.path, exec.path, work.path)
        apk = File(folder.root, "app/~~kQ==/omp.test-1==/base.apk").apply { parentFile.mkdirs() }
        buildApk()
        // The stub's own package path is a string with no version code in it, and the doctor says so
        // rather than guessing at one. This double answers with the archive this test built, which
        // is the two things the command reads: where the package is, and what is in it.
        services = object : PlatformServices by stub {
            override fun packagePaths(pkg: String): List<String> = listOf(apk.path)
        }
    }

    /** A real archive with real entries before the assets, as an APK has. */
    private fun buildApk() {
        ZipOutputStream(apk.outputStream()).use { zip ->
            // A manifest and a dex first, so the reader walks past real entries to reach the
            // assets — which is what it does on a device, and what a stubbed seek would not.
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(64))
            zip.closeEntry()
            for ((name, bytes) in pages) {
                zip.putNextEntry(ZipEntry("${BuildPages.ASSET_DIR}/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @After
    fun tearDown() {
        ProvisionStatusHolder.clear()
    }

    // ---- the healthy device --------------------------------------------------------------------

    @Test
    fun aProvisionedDeviceReadsGreenAndSaysWhatItCannotTellYou() {
        provisionEverything()
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = doctor().report()
        val freeText = ArtifactManifest.humanBytes(RealVfs().diskUsage(files.path).freeBytes)

        assertEquals(
            report.text(),
            listOf(
                "omp doctor: read-only — nothing below downloads, writes, starts or stops anything",
                "",
                "identity",
                "  app:           omp.test",
                "  version:       1",
                "  installed at:  ${apk.path}",
                "  build:         google/stub/stub:14/UP1A.231005.007/1:user/release-keys",
                "  uid:           10123",
                "  gid:           10123",
                "  abi:           arm64-v8a",
                "  debian arch:   arm64",
                "  real agent:    obtainable on arm64-v8a: omp-linux-arm64, 224.0 MiB (234,866,984 bytes)",
                "",
                "guest",
                "  payload:       ${files.path} — exists, 2 entries",
                "  exec dir:      ${exec.path} — exists, 5 entries",
                "  wire:          334.2 MiB (350,458,048 bytes)",
                "  room:          710.9 MiB (745,403,584 bytes)",
                "  space check:   would pass: ${freeText} is free and the manifest asks for 710.9 MiB (745,403,584 bytes)",
                "",
                "helper",
                "  expected:      libproot.so, libproot-loader.so, libproot-loader32.so, libtalloc.so, libandroid-shmem.so",
                "  libproot.so:   247408 bytes",
                "  libproot-loader.so: 18136 bytes",
                "  libproot-loader32.so: 6244 bytes",
                "  libtalloc.so:  31440 bytes",
                "  libandroid-shmem.so: 14432 bytes",
                "  verdict:       all 5 are there, which is as far as this build can check",
                "",
                "provisioning",
                "  state file:    ${paths.stateFile} — not there",
                "  debian-trixie-rootfs-arm64: complete, unpacked at ${paths.rootfsDir}",
                "  omp-linux-arm64: complete, unpacked at ${paths.agentBinary}",
                "  last attempt:  no record at ${paths.stateFile}: nothing has been downloaded by this build",
                "  last run:      none recorded in this app's memory, which does not survive a restart; the state file and the .part files above are the only evidence there is",
                "  phase:         installed: nothing in progress",
                "",
                "guest state",
                "  rootfs:        unpacked, .omp-provisioned is in ${paths.rootfsDir}",
                "  agent:         installed at ${paths.agentBinary}",
                "  web root:      installed at ${paths.webRoot}",
                "  web bytes:     3 of 3 are this build's own copy — index.html is this build's own copy; app.css is this build's own copy; app.js is this build's own copy",
                "  lamp:          not installed: no .omp-guest-packages in ${paths.rootfsDir}",
                "",
                "agent update",
                "  record:        ${AgentUpdate.recordFile(paths)} — not there",
                "  outcome:       never: no record at ${AgentUpdate.recordFile(paths)}, so this build has " +
                    "not run 'omp update' in a guest on this device",
                "  version:       none: nothing has run in a guest, so no version of the agent in one has been measured",
                "  last run:      none: the update runs at every start of the app, and only when the Debian and its agent are both on this device",
                "",
                Doctor.SECTION_ORIGIN,
                "  state:         NOT_STARTED: a Debian is on this device and this build has not started the guest, so the " +
                    "chat the WebView was handed is this app's own loopback server; a guest that was only launched is " +
                    "never shown as though it were answering",
                "  port:          ${omp.vm.provision.GuestWeb.RESERVED_PORT}, the port this build reserves for Apache inside the Debian, and nothing has tried to reserve it",
                "  apache:        not asked: this build has not started the guest on this device, and this command starts nothing and asks nothing to find out",
                "  agent:         not run: this build has not started the guest on this device, so there is no boot's 'omp update' from it to report; the 'agent update' section above is where any other run of it is read",
                "",
                "chat",
                "  origin:        this app's own loopback server: this build has not started the guest on this device, and a guest that was only launched is never shown as though it were answering",
                "  port:          8731, from ${paths.workDir}/web/url",
                "  listening:     yes, something accepted a connection on 127.0.0.1:8731 inside 400ms",
                "  token:         ${paths.workDir}/web/token — 25 bytes — read, never printed by this command",
                "",
                "gaps",
                "  none: every fact above was read from this device",
                "",
                "next",
                "  web: the agent's web front end is up on 127.0.0.1:8731; 'web' prints that url and this install's token",
                "",
                "  what this cannot tell you: nothing above executed the native helper. \"the helper is",
                "  there\" is not \"the guest will boot\", and no build of this app has ever run proot on any",
                "  device. The next failure after a report that reads healthy is the kernel refusing to",
                "  exec a file out of the exec directory, a proot that will not accept these flags, or a",
                "  Debian that unpacked without a loader in it.",
            ),
            report.lines,
        )
    }

    // ---- one test per gap that must be reported rather than hidden ----------------------------

    @Test
    fun aHelperDirectoryThePackageManagerEmptiedSaysSoAndNamesTheAttribute() {
        // The single most likely first-install failure, and the one that looks most like a device
        // with no proot on it at all from a shell: the directory exists and holds nothing.
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  exec dir:      ${exec.path} — exists and is empty",
            report.lines.first { it.startsWith("  exec dir:") },
        )
        assertEquals(
            report.text(),
            "  libproot.so:   unreadable: not in ${exec.path} (No such file or directory)",
            report.lines.first { it.startsWith("  libproot.so:") },
        )
        assertEquals(
            report.text(),
            "  verdict:       none of the 5 packaged files is in ${exec.path}: the package manager did not " +
                "extract them, and android:extractNativeLibs=\"true\" is the attribute that makes it",
            report.lines.first { it.startsWith("  verdict:") },
        )
        // Every one of the five is named, not just the first, and each names itself as unreadable.
        for (name in Doctor.EXPECTED.getValue(Abi.ARM64)) {
            assertTrue(name, report.lines.any { it.startsWith("  $name:") && it.contains(Doctor.UNREADABLE) })
        }
    }

    @Test
    fun aHalfDownloadedAgentIsNamedWithBothByteCountsAndSaysItResumes() {
        val manifest = ArtifactManifest.of(Abi.ARM64)
        File(paths.downloadDir).mkdirs()
        val part = File(paths.partial(manifest.agent!!.name))
        part.writeBytes(ByteArray(12_582_912))
        File(paths.stateFile).writeText(
            "#omp-provision/v1 — name received status\n${manifest.agent.name} 12582912 partial\n",
        )
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  omp-linux-arm64: partial, 12.0 MiB (12,582,912 bytes) of 224.0 MiB (234,866,984 bytes); " +
                "the next run continues from there",
            report.lines.first { it.startsWith("  omp-linux-arm64:") },
        )
        assertEquals(
            report.text(),
            "  last attempt:  omp-linux-arm64, recorded as '12582912 partial'",
            report.lines.first { it.startsWith("  last attempt:") },
        )
        assertEquals(
            report.text(),
            "  phase:         downloading: 1 artifact(s) have a .part file, and the next 'omp provision' " +
                "resumes each from that file's own length",
            report.lines.first { it.startsWith("  phase:") },
        )
    }

    @Test
    fun aDiskWithNoRoomIsReportedAsARefusalTheSpaceCheckWouldMake() {
        val short = object : Vfs by RealVfs() {
            override fun diskUsage(path: String) = VDiskUsage(1024L * 1024L * 1024L, 512L * 1024L)
        }
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = Doctor(services, short, paths, probe = probe(up = true)).report()

        assertEquals(
            report.text(),
            "  space check:   would refuse: 524288 bytes is free and the manifest asks for " +
                "710.9 MiB (745,403,584 bytes)",
            report.lines.first { it.startsWith("  space check:") },
        )
        // A refusal is an answer and not a gap: the disk answered, and the only thing this run
        // could not establish is about the guest, which is not installed on this fixture.
        assertEquals(
            report.text(),
            listOf(
                "unreadable: there is no unpacked Debian, so ${paths.webRoot} is not a document root yet",
                "unreadable: there is no unpacked Debian, so nothing can have been installed into it",
            ),
            report.gaps,
        )
        // And the advice follows from it, rather than recommending a download that would refuse.
        assertTrue(
            report.text(),
            report.lines.any { it.contains("so 'omp provision' would refuse") },
        )
    }

    @Test
    fun aDirectoryTheFilesystemWillNotOpenIsNamedUnreadableAndAppearsInTheGaps() {
        val denied = object : Vfs by RealVfs() {
            override fun readDir(path: String): List<VEntry> {
                if (path == files.path) throw FsException(FsErrno.PERM_DENIED, path)
                return RealVfs().readDir(path)
            }
        }
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = Doctor(services, denied, paths, probe = probe(up = true)).report()

        assertEquals(
            report.text(),
            "  payload:       unreadable: ${files.path} could not be read: Permission denied",
            report.lines.first { it.startsWith("  payload:") },
        )
        // The same fact, named again in the gaps section: an in-place line and a summary, both
        // present, and never the silence that would read as a healthy directory.
        assertTrue(
            report.text(),
            report.gaps.contains("unreadable: ${files.path} could not be read: Permission denied"),
        )
    }

    @Test
    fun anUnpackedRootfsWithNoDocumentRootSaysThePagesAndTheLampAreUnreadable() {
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsDir, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  web root:      installed at ${paths.webRoot}",
            report.lines.first { it.startsWith("  web root:") },
        )
        // The three pages are not there, so the comparison against this build's own bytes could
        // not be made, and it says so rather than reporting zero of three as a healthy answer.
        assertEquals(
            report.text(),
            "  web bytes:     0 of 3 are this build's own copy — index.html is not there; " +
                "app.css is not there; app.js is not there",
            report.lines.first { it.startsWith("  web bytes:") },
        )
        // The guest's own LAMP is a separate fact and it is reported on its own terms: the Debian
        // is unpacked, so this is an answer rather than a gap.
        assertEquals(
            report.text(),
            "  lamp:          not installed: no ${ProvisionPaths.GUEST_MARKER} in ${paths.rootfsDir}",
            report.lines.first { it.startsWith("  lamp:") },
        )
    }

    @Test
    fun aDeviceWithNoDebianAtAllGapsBothGuestFactsRatherThanReportingThemAsHealthy() {
        // Neither the document root nor the LAMP can be established without a Debian, and saying
        // "not installed" for either would be a claim about a tree that is not there.
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  web root:      unreadable: there is no unpacked Debian, so ${paths.webRoot} is not a " +
                "document root yet",
            report.lines.first { it.startsWith("  web root:") },
        )
        assertEquals(
            report.text(),
            "  lamp:          unreadable: there is no unpacked Debian, so nothing can have been installed into it",
            report.lines.first { it.startsWith("  lamp:") },
        )
        // Both are named again in the gaps section, so a reader who skims cannot miss them.
        assertEquals(
            report.text(),
            listOf(
                "unreadable: there is no unpacked Debian, so ${paths.webRoot} is not a document root yet",
                "unreadable: there is no unpacked Debian, so nothing can have been installed into it",
            ),
            report.gaps,
        )
    }

    @Test
    fun aDocumentRootHoldingSomebodyElsesBytesPrintsBothLengthsAndSaysWhichFile() {
        provisionEverything()
        // What Debian's own apache2 package does to index.html at apt time, and what a user does
        // with an editor: one file out of three stops being the app's.
        File(paths.webRoot, "index.html").writeText("<html><body>Apache2 Debian Default Page</body></html>")
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  web bytes:     2 of 3 are this build's own copy — index.html is 53 bytes here and " +
                "35 bytes in this build: different bytes; app.css is this build's own copy; " +
                "app.js is this build's own copy",
            report.lines.first { it.startsWith("  web bytes:") },
        )
    }

    @Test
    fun aPortNothingIsListeningOnIsReportedAsNoAndTheNextStepSaysNothing() {
        publishUrl(port = 8731, up = false)

        // The probe is built here rather than by doctor(), which answers "up": this is the one
        // test whose subject is a connect that is refused.
        val report = Doctor(services, RealVfs(), paths, probe = probe(up = false)).report()

        assertEquals(
            report.text(),
            "  listening:     no, nothing accepted a connection on 127.0.0.1:8731 inside 400ms",
            report.lines.first { it.startsWith("  listening:") },
        )
        // A refused connect is an answer, and the advice below follows from it by not appearing.
        assertFalse(report.text(), report.lines.any { it.startsWith("  web:") })
    }

    @Test
    fun a32BitAbiIsToldTheAgentIsNeverComingAndTheDebianIsArm() {
        stub.props[Doctor.ABILIST] = Abi.ARMEABI_V7A.abiName
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            listOf(
                "  abi:           armeabi-v7a",
                "  debian arch:   arm",
                "  real agent:    not obtainable on armeabi-v7a: the agent publishes no 32-bit Linux build",
            ),
            report.lines.filter { it.startsWith("  abi:") || it.startsWith("  debian arch:") || it.startsWith("  real agent:") },
        )
        // The 32-bit loader is not packaged for this ABI, so it must not be looked for: a report
        // that named it would send a user looking for a file this build never ships.
        assertEquals(
            report.text(),
            "  expected:      libproot.so, libproot-loader.so, libtalloc.so, libandroid-shmem.so",
            report.lines.first { it.startsWith("  expected:") },
        )
    }

    @Test
    fun aDeviceWithNoNativeLibraryDirectorySaysSoRatherThanGuessingOne() {
        val nowhere = ProvisionPaths(files.path, null, work.path)

        val report = Doctor(services, RealVfs(), nowhere, probe = probe(up = true)).report()

        assertEquals(
            report.text(),
            "  exec dir:      unreadable: the platform named no native library directory, so there is no " +
                "exec directory to look in",
            report.lines.first { it.startsWith("  exec dir:") },
        )
        assertEquals(
            report.text(),
            "  expected:      unreadable: no exec directory: the platform named no native library directory",
            report.lines.first { it.startsWith("  expected:") },
        )
        // And nothing downstream invents a directory to look in.
        assertTrue(
            report.text(),
            report.gaps.any { it.contains("there is no exec directory to look in") },
        )
    }

    @Test
    fun aMissingAllFilesGrantIsOneOfTheThreeThingsItSaysToRun() {
        stub.storageManager = false
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  grant-storage: this app does not hold \"All files access\", which Documents/omp needs",
            report.lines.first { it.startsWith("  grant-storage:") },
        )
    }

    @Test
    fun aRunThatWasCancelledMidDownloadIsReportedWithItsOwnPhaseAndBytes() {
        val agent = ArtifactManifest.of(Abi.ARM64).agent!!
        ProvisionStatusHolder.begin(Abi.ARM64.abiName)
        ProvisionStatusHolder.progress(
            Abi.ARM64.abiName,
            omp.vm.provision.Progress(agent.name, omp.vm.provision.Phase.DOWNLOADING, 12_582_912L, agent.sizeBytes),
        )
        ProvisionStatusHolder.finish(omp.vm.provision.ProvisionOutcome.CANCELLED)
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val report = doctor().report()

        assertEquals(
            report.text(),
            "  last run:      ended as CANCELLED, on omp-linux-arm64: 12.0 MiB (12,582,912 bytes) of " +
                "224.0 MiB (234,866,984 bytes)",
            report.lines.first { it.startsWith("  last run:") },
        )
    }

    @Test
    fun aDeviceWithNoChosenArchitectureNamesThePropertyItReadAndGapsEveryLineItCannot() {
        stub.props.remove(Doctor.ABILIST)
        stub.props[Doctor.ABILIST] = "riscv64"

        // A riscv64 phone: the platform names an architecture and the app has no entry for it, in
        // both of the two places [Abi.detect] looks. It answers null rather than guessing, and this
        // is the report a user on such a phone gets.
        val report = Doctor(services, RealVfs(), paths, probe = probe(up = true), osArch = "riscv64").report()

        assertEquals(
            report.text(),
            "  abi:           unreadable: no ABI this app knows in riscv64",
            report.lines.first { it.startsWith("  abi:") },
        )
        // Every section that needed the manifest says so in place, and the gaps section carries the
        // same sentences. Nothing is silently missing.
        assertTrue(report.text(), report.gaps.any { it.contains("no manifest without a known ABI") })
        assertTrue(report.text(), report.gaps.any { it.contains("no ABI, so no packaged set") })
    }

    // ---- the shapes ----------------------------------------------------------------------------

    @Test
    fun theTenSectionsPrintInAFixedOrderAndTheClosingLineIsLast() {
        provisionEverything()
        writeHelperFiles()
        publishUrl(port = 8731, up = true)

        val lines = doctor().report().lines

        val heads = lines.filter { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("omp doctor:") }
        assertEquals(
            listOf(
                "identity",
                "guest",
                "helper",
                "provisioning",
                "guest state",
                "agent update",
                Doctor.SECTION_ORIGIN,
                "chat",
                "gaps",
                "next",
            ),
            heads,
        )
        // The closing block is the last thing printed and every line of it is indented, so it
        // cannot be mistaken for a section head by anything reading the shape of the report.
        assertEquals(Doctor.CANNOT.size, lines.takeLast(Doctor.CANNOT.size).size)
        assertTrue(
            lines.last(),
            lines.takeLast(Doctor.CANNOT.size).all { it.startsWith("  ") && it.trim().isNotEmpty() },
        )
        assertTrue(lines.last().trim().startsWith("Debian that unpacked without a loader in it."))
    }

    @Test
    fun theReportOpensOneSocketAtMostAndWritesNothing() {
        provisionEverything()
        writeHelperFiles()
        publishUrl(port = 8731, up = true)
        val before = treeOf(folder.root)
        var probes = 0

        val report = Doctor(services, RealVfs(), paths, probe = Doctor.Probe { port ->
            probes++
            port == 8731
        }).report()

        // One probe, read twice: the chat section and the next-step section are the same fact.
        assertEquals("the probe must be made once and read twice", 1, probes)
        // The read-only promise, checked against the filesystem rather than asserted.
        assertEquals(report.text(), before, treeOf(folder.root))
    }

    @Test
    fun theExpectedHelperListIsWhatThisApkActuallyPackages() {
        // The list is a copy of app/src/main/jniLibs/<abi>/, and a copy is only honest while it
        // matches. This is the test that notices somebody renaming a file or adding an ABI.
        val jni = jniLibsDirectory()
        for ((abi, names) in Doctor.EXPECTED) {
            val onDisk = File(jni, abi.abiName).list()!!.sorted()
            assertEquals(
                "app/src/main/jniLibs/${abi.abiName} and Doctor.EXPECTED have drifted apart",
                onDisk,
                names.sorted(),
            )
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun doctor(): Doctor = Doctor(services, RealVfs(), paths, probe = probe(up = true))

    private fun probe(up: Boolean) = Doctor.Probe { up }

    private fun provisionRootfs() {
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsDir, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
    }

    private fun provisionEverything() {
        provisionRootfs()
        File(paths.webRoot).mkdirs()
        for ((name, bytes) in pages) File(paths.webRoot, name).writeBytes(bytes)
        File(paths.agentDir).mkdirs()
        File(paths.agentBinary).writeBytes(ByteArray(1_024))
    }

    /** The five files the APK packages for arm64, at the sizes the real ones have. */
    private fun writeHelperFiles() {
        for ((name, size) in mapOf(
            "libproot.so" to 247_408,
            "libproot-loader.so" to 18_136,
            "libproot-loader32.so" to 6_244,
            "libtalloc.so" to 31_440,
            "libandroid-shmem.so" to 14_432,
        )) {
            File(exec, name).writeBytes(ByteArray(size))
        }
    }

    private fun publishUrl(port: Int, up: Boolean) {
        File(files, "web").mkdirs()
        File(files, "web/url").writeText("http://127.0.0.1:$port/login?t=a-32-character-test-token\n")
        File(files, "web/token").writeText("a-32-character-test-token")
        assertTrue("the probe double must agree with the fixture", up || !up)
    }

    /** Every file under [root] with its length, so "nothing was written" is a fact. */
    private fun treeOf(root: File): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        root.walkTopDown().forEach { out[it.path] = if (it.isFile) it.length() else -1L }
        return out
    }

    /**
     * The app's own `jniLibs/`, found by walking up from wherever the test was started.
     *
     * The same walk-up [omp.vm.provision.WebRootTest] does, for the same reason: the point is to
     * read the *app's* files rather than a fixture of them.
     */
    private fun jniLibsDirectory(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val jni = File(dir, "app/src/main/jniLibs")
            if (jni.isDirectory) return jni
            dir = dir.parentFile
        }
        throw AssertionError(
            "app/src/main/jniLibs was not found above ${File(".").absolutePath}: the test that says the " +
                "doctor looks for the files this build packages cannot be run without them",
        )
    }
}
