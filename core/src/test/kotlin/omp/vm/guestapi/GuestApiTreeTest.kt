package omp.vm.guestapi

import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.WebOutcome
import omp.vm.provision.WebSource
import org.junit.Assert.assertArrayEquals
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

/**
 * The guest's PHP tree, written into an unpacked Debian, judged by `java.io.File` after every claim.
 *
 * **A [GuestApiTree] writes files and returns a report, and a report is something a class can write
 * whether or not anything happened** — so the assertions here are about bytes on a disk: what is
 * there, what is not, what was left alone, and what the Apache drop-in says once its two
 * placeholders are filled in. The rootfs is a directory with the mark in it, because that is all
 * [ProvisionPaths.state] asks of a provisioned Debian.
 *
 * **The last test in this file is the one that keeps the APK and this table together.** It reads
 * `app/src/main/assets/guest/` off this disk and fails when a file is in the APK and not in
 * [GuestApiTree.FILES], which is the difference between a guest that serves the API and a guest
 * whose `index.php` `require`s something nobody shipped.
 */
class GuestApiTreeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var paths: ProvisionPaths
    private lateinit var vfs: RealVfs
    private lateinit var rootfs: File

    /** What the app's own assets would answer, standing in for the APK's `assets/guest/`. */
    private val guestAssets = GuestApiTree.FILES.associate { file ->
        file.source to (
            if (file.source.endsWith(GuestApiTree.TEMPLATE_SUFFIX)) {
                "<Directory @DOCUMENT_ROOT@>\n    FallbackResource @API_INDEX@\n</Directory>\n"
            } else {
                "<?php\n// ${file.source}\n"
            }
            ).toByteArray()
    }

    private val source = WebSource { name -> guestAssets[name] }

    @Before
    fun setUp() {
        paths = ProvisionPaths(folder.newFolder("files").path, null, folder.newFolder("work").path)
        vfs = RealVfs()
        rootfs = File(paths.rootfsDir).apply { mkdirs() }
        File(rootfs, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
    }

    // ---- the tree ------------------------------------------------------------------------------

    @Test
    fun everyFileLandsWhereTheTableSaysAndNothingElseDoes() {
        val report = tree().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        assertEquals(GuestApiTree.FILES.size, report.filesWritten)
        assertEquals(0, report.filesUnchanged)
        for (file in GuestApiTree.FILES) {
            val written = File(rootfs, file.destination.removePrefix("/"))
            assertTrue("${file.source} was not written to ${written.path}", written.isFile)
            assertArrayEquals(file.source, expected(file), written.readBytes())
        }
    }

    @Test
    fun thePhpGoesWhereApacheDoesNotServeItAndTheDropInNamesTheFrontController() {
        val report = tree().install(vfs)

        // The document root is the only directory this app serves, and nothing of the guest's own
        // code goes in it: a file under /var/www/html is a file a request can fetch, and what is in
        // this tree is the code that opens the database and starts the agent.
        for (file in GuestApiTree.FILES) {
            assertFalse(
                "${file.destination} is inside the document root",
                file.destination.startsWith(ProvisionPaths.WEB_DOCROOT + "/"),
            )
        }
        val conf = File(rootfs, paths.guestConf.removePrefix("/"))
        assertTrue(conf.path, conf.isFile)
        val text = conf.readText()
        assertFalse(
            "the drop-in still holds a placeholder, so Apache would be pointed at @API_INDEX@",
            text.contains(GuestApiTree.DOCUMENT_ROOT_TOKEN) || text.contains(GuestApiTree.API_INDEX_TOKEN),
        )
        // One directive, and it is the one that makes an absolute /api/... URL reach a program.
        assertTrue("the drop-in has no FallbackResource: $text", text.contains("FallbackResource"))
        assertTrue(
            "the drop-in does not name the document root: $text",
            text.contains("<Directory ${ProvisionPaths.WEB_DOCROOT}>"),
        )
        assertTrue(
            "the drop-in does not name the front controller: $text",
            text.contains("FallbackResource ${paths.guestApiDir}/${GuestApiTree.INDEX_NAME}"),
        )
        assertEquals(paths.guestConf, report.apacheConf?.removePrefix(paths.rootfsDir))
    }

    /**
     * The bytes as they are written: the asset's own, except for the one rendered file.
     *
     * The comparison in the first test has to be against this and not against the asset, because
     * the Apache drop-in is written with its two placeholders filled in — comparing it with the
     * template it came from would fail on a file that is exactly right.
     */
    private fun expected(file: GuestFile): ByteArray {
        val raw = guestAssets.getValue(file.source)
        if (!file.source.endsWith(GuestApiTree.TEMPLATE_SUFFIX)) return raw
        return String(raw, Charsets.UTF_8)
            .replace(GuestApiTree.DOCUMENT_ROOT_TOKEN, ProvisionPaths.WEB_DOCROOT)
            .replace(GuestApiTree.API_INDEX_TOKEN, paths.guestApiDir + "/" + GuestApiTree.INDEX_NAME)
            .toByteArray(Charsets.UTF_8)
    }

    @Test
    fun aSecondInstallWithTheSameBytesDoesNotTouchTheFiles() {
        tree().install(vfs)
        val old = 1_000_000_000_000L
        val index = File(rootfs, "${ProvisionPaths.GUEST_API_DIR}/index.php")
        vfs.setModified(index.path, old)

        val report = tree().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.ALREADY_INSTALLED, report.outcome)
        assertEquals(0, report.filesWritten)
        assertEquals(GuestApiTree.FILES.size, report.filesUnchanged)
        // The mtime is the evidence, and it is set to a year in 2001 first so the assertion cannot
        // pass by two writes landing in the same millisecond.
        assertEquals("a matching file must not even be opened", old, index.lastModified())
    }

    @Test
    fun aFileTheGuestChangedIsPutBackAndTheReportNamesIt() {
        tree().install(vfs)
        val edited = File(rootfs, "${ProvisionPaths.GUEST_API_DIR}/Routes.php")
        edited.writeText("<?php\n// a user's own edit inside the Debian\n")

        val report = tree().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        assertEquals(1, report.filesWritten)
        assertEquals(GuestApiTree.FILES.size - 1, report.filesUnchanged)
        assertArrayEquals(guestAssets.getValue("api/Routes.php"), edited.readBytes())
        val line = report.lines.first { it.contains("Routes.php") && it.contains("written") }
        assertTrue(line, line.contains("a different copy"))
    }

    @Test
    fun nothingThisStepWritesIsExecutableAndNoModeIsSetAtAll() {
        tree().install(vfs)
        for (file in GuestApiTree.FILES) {
            val written = File(rootfs, file.destination.removePrefix("/"))
            assertFalse("${file.source} is code, not a program this app execs", written.canExecute())
        }
        // The other half of the same promise, and it is the one a `chmod` here would break: a file a
        // user made in the guest keeps the bits it has, even on a run that writes something else.
        val mine = File(rootfs, "${ProvisionPaths.GUEST_API_DIR}/mine.php")
        mine.writeText("<?php\n")
        assertTrue(mine.setExecutable(true))
        val before = vfs.stat(mine.path).mode
        // A file is deleted so that this run really does write something: a run that writes nothing
        // would pass the mode assertion without ever opening a file.
        assertTrue(File(rootfs, "${ProvisionPaths.GUEST_API_DIR}/Sse.php").delete())

        val report = tree().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        assertEquals(1, report.filesWritten)
        assertFalse(
            "a file this step wrote is code, not a program this app execs",
            File(rootfs, "${ProvisionPaths.GUEST_API_DIR}/Sse.php").canExecute(),
        )
        assertEquals(before, vfs.stat(mine.path).mode)
        assertTrue(mine.canExecute())
    }

    // ---- the refusals --------------------------------------------------------------------------

    @Test
    fun aDestinationOutsideTheDebianIsRefusedAndNothingIsWritten() {
        val escape = GuestFile("api/index.php", "/../../../../data/local/tmp/omp.php")
        val report = tree(files = GuestApiTree.FILES + escape).install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.REFUSED, report.outcome)
        assertFalse(File("/data/local/tmp/omp.php").exists())
        assertFalse("a refused install has not made the API directory either", File(rootfs, ProvisionPaths.GUEST_API_DIR).exists())
        assertNull(tree().destinationOf(GuestFile("../escape.php", "/usr/local/share/omp/escape.php")))
        assertNull(tree().destinationOf(GuestFile("api/index.php", "usr/local/share/omp/index.php")))
        assertNull(tree().destinationOf(GuestFile("", "/usr/local/share/omp/index.php")))
        assertNotNull(tree().destinationOf(GuestFile("api/index.php", "/usr/local/share/omp/index.php")))
    }

    @Test
    fun aFileThisBuildDoesNotHaveIsARefusalAndNotSevenFilesOutOfEight() {
        val report = tree(
            files = GuestApiTree.FILES + GuestFile("api/Extra.php", "/usr/local/share/omp/Extra.php"),
        ).install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.REFUSED, report.outcome)
        assertTrue(report.lines.any { it.contains("guest/api/Extra.php is not in this build") })
        assertFalse(File(rootfs, ProvisionPaths.GUEST_API_DIR).exists())
    }

    @Test
    fun aDeviceWithNoDebianHasNoApacheToInstallTheApiInto() {
        File(rootfs, ProvisionPaths.ROOTFS_MARKER).delete()
        val report = tree().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.REFUSED, report.outcome)
        assertTrue(report.lines.any { it.contains("no unpacked Debian") })
        assertFalse(File(rootfs, ProvisionPaths.GUEST_API_DIR).exists())
    }

    // ---- the one copy in the APK ----------------------------------------------------------------

    @Test
    fun everyFileInTheApkIsInTheTableAndTheTableNamesFilesThatExist() {
        val assets = guestAssetDirectory()
        val onDisk = walk(assets).map { it.relativeTo(assets).path }.sorted()
        val inTheTable = GuestApiTree.FILES.map { it.source }.sorted()

        assertEquals(
            "app/src/main/assets/guest holds $onDisk and GuestApiTree.FILES is $inTheTable: one of " +
                "them has changed and the other has not",
            inTheTable,
            onDisk,
        )
        // And the install really does put the app's own bytes in, read off this disk rather than
        // out of a fixture: a tree that installs a copy is a tree that drifts.
        val pathsHere = ProvisionPaths(folder.newFolder("real").path, null, folder.newFolder("w").path)
        val real = File(pathsHere.rootfsDir).apply { mkdirs() }
        File(real, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
        val fromTheApp = WebSource { name -> File(assets, name).takeIf { it.isFile }?.readBytes() }

        val report = GuestApiTree(pathsHere, fromTheApp).install(RealVfs())

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        for (file in GuestApiTree.FILES) {
            val written = File(real, file.destination.removePrefix("/"))
            assertTrue("${file.source} was not installed to ${written.path}", written.isFile)
            if (file.source.endsWith(GuestApiTree.TEMPLATE_SUFFIX)) {
                // The one rendered file, and the assertion is that rendering happened: both
                // placeholders gone, the real paths in their places.
                val text = written.readText()
                assertFalse(text.contains(GuestApiTree.API_INDEX_TOKEN))
                assertTrue(text.contains(pathsHere.guestApiDir + "/" + GuestApiTree.INDEX_NAME))
            } else {
                assertArrayEquals(file.source, File(assets, file.source).readBytes(), written.readBytes())
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun tree(files: List<GuestFile> = GuestApiTree.FILES) = GuestApiTree(paths, source, files)

    private fun walk(dir: File): List<File> =
        dir.listFiles().orEmpty().flatMap { if (it.isDirectory) walk(it) else listOf(it) }

    private fun guestAssetDirectory(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val assets = File(dir, "app/src/main/assets/guest")
            if (assets.isDirectory) return assets
            dir = dir.parentFile
        }
        throw AssertionError(
            "app/src/main/assets/guest was not found above ${File(".").absolutePath}: the test that " +
                "says the guest is shipped this tree cannot be run without it",
        )
    }
}
