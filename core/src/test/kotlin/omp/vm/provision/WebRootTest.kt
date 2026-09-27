package omp.vm.provision

import omp.shell.fs.RealVfs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The app's pages in the guest's document root, judged by `java.io.File` after every claim.
 *
 * A [WebRoot] writes files and returns a report, and a report is something a class can write
 * whether or not anything happened — so the assertions here are about bytes on a disk: what is
 * there, what is not there, and what was left alone. The rootfs here is a directory with the mark
 * in it, because that is all [ProvisionPaths.state] asks of a provisioned Debian and because
 * building a real Debian to test three writes would be a test of `tar`.
 *
 * **The last test in this file is the one that keeps the two copies together.** It reads the app's
 * own `app/src/main/assets/web/` off this disk — the same directory the app's chat server serves
 * from — installs those bytes, and fails when the guest's copy is not them. That is the only
 * version of "they are the same files" that survives somebody editing the page.
 */
class WebRootTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var paths: ProvisionPaths
    private lateinit var vfs: RealVfs
    private lateinit var rootfs: File

    /** What the app's own source would answer, standing in for the APK's `assets/web/`. */
    private val appPages = mapOf(
        "index.html" to "<!doctype html>\n<title>omp</title>\n".toByteArray(),
        "app.css" to ":root { --bg: #101216; }\n".toByteArray(),
        "app.js" to "// the page's own script, with no dependency in it\n".toByteArray(),
    )

    private val appAssets = WebSource { name -> appPages[name] }

    @Before
    fun setUp() {
        paths = ProvisionPaths(folder.newFolder("files").path, null, folder.newFolder("work").path)
        vfs = RealVfs()
        rootfs = File(paths.rootfsDir).apply { mkdirs() }
        File(rootfs, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
    }

    // ---- the document root ---------------------------------------------------------------------

    @Test
    fun thePagesLandInTheDocumentRootDebianAlreadyServes() {
        val report = webRoot().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        assertEquals(3, report.filesWritten)
        assertEquals(0, report.filesUnchanged)
        // /var/www/html is the DocumentRoot the apache2 package's own 000-default.conf names, and
        // it is the only path that needs no configuration: the page fetches /app.css by absolute
        // path, so anything served under a different URL is a broken page.
        assertEquals(File(rootfs, "var/www/html").path, report.documentRoot)
        for (name in WebRoot.FILES) {
            val served = File(rootfs, "var/www/html/$name")
            assertTrue(served.path, served.isFile)
            assertArrayEquals(name, appPages.getValue(name), served.readBytes())
        }
    }

    @Test
    fun nothingThisStepWritesIsExecutableAndNoModeIsSetAtAll() {
        webRoot().install(vfs)
        for (name in WebRoot.FILES) {
            val served = File(rootfs, "var/www/html/$name")
            assertFalse("$name is a static page with nothing to run", served.canExecute())
        }
        // The other half of the same promise: this class calls no chmod, so a file a user made in
        // the guest keeps the bits it has, even on a run that writes something else. A step that
        // "fixed" the mode would be a second opinion about a file it did not create.
        val mine = File(rootfs, "var/www/html/mine.html")
        mine.writeText("<h1>mine</h1>")
        assertTrue(mine.setExecutable(true))
        val before = vfs.stat(mine.path).mode
        assertTrue(File(rootfs, "var/www/html/app.js").delete())

        val report = webRoot().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        assertEquals(1, report.filesWritten)
        assertFalse(File(rootfs, "var/www/html/app.js").canExecute())
        assertEquals(before, vfs.stat(mine.path).mode)
        assertTrue(mine.canExecute())
    }

    @Test
    fun aSecondInstallWithTheSameBytesDoesNotTouchTheFiles() {
        webRoot().install(vfs)
        val old = 1_000_000_000_000L
        val served = File(rootfs, "var/www/html/index.html")
        vfs.setModified(served.path, old)

        val report = webRoot().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.ALREADY_INSTALLED, report.outcome)
        assertEquals(0, report.filesWritten)
        assertEquals(3, report.filesUnchanged)
        // The mtime is the evidence, and it is set to a year in 2001 first so the assertion cannot
        // pass by two writes landing in the same millisecond.
        assertEquals("a matching file must not even be opened", old, served.lastModified())
        assertTrue(report.lines.any { it.contains("nothing was written") })
    }

    @Test
    fun aFileTheGuestChangedIsPutBackAndTheReportNamesIt() {
        webRoot().install(vfs)
        // What Debian's own apache2 package does to /var/www/html/index.html when it is installed,
        // and what a user does to it with an editor: the bytes on the disk stop being the app's.
        val served = File(rootfs, "var/www/html/index.html")
        served.writeText("<html><body>Apache2 Debian Default Page</body></html>")

        val report = webRoot().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        assertEquals(1, report.filesWritten)
        assertEquals(2, report.filesUnchanged)
        assertArrayEquals(appPages.getValue("index.html"), served.readBytes())
        val line = report.lines.first { it.contains("index.html") && it.contains("written") }
        assertTrue(line, line.contains("a different copy"))
    }

    // ---- the two refusals -----------------------------------------------------------------------

    @Test
    fun aNameThatWouldLeaveTheDocumentRootIsRefusedAndNothingIsWritten() {
        val escape = File(rootfs, "etc/omp-escaped")
        val report = webRoot(files = listOf("index.html", "../../../etc/omp-escaped")).install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.REFUSED, report.outcome)
        assertFalse(escape.path, escape.exists())
        // Not even the directory: a refused install has not made a document root either.
        assertFalse(File(rootfs, "var/www/html").exists())
        assertNull(webRoot().destinationOf("../../../etc/passwd"))
        assertNull(webRoot().destinationOf("/etc/passwd"))
        assertNull(webRoot().destinationOf(""))
        assertEquals(
            "${File(rootfs, "var/www/html/index.html").path}",
            webRoot().destinationOf("index.html"),
        )
    }

    @Test
    fun aPageThisBuildDoesNotHaveIsARefusalAndNotTwoPagesOutOfThree() {
        val report = webRoot(files = listOf("index.html", "app.js", "index.legacy.html"))
            .install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.REFUSED, report.outcome)
        assertTrue(report.lines.any { it.contains("web/index.legacy.html is not in this build") })
        assertFalse(File(rootfs, "var/www/html").exists())
    }

    @Test
    fun aDeviceWithNoDebianHasNoDocumentRootToInstallInto() {
        File(rootfs, ProvisionPaths.ROOTFS_MARKER).delete()
        val report = webRoot().install(vfs)

        assertEquals(report.lines.toString(), WebOutcome.REFUSED, report.outcome)
        assertTrue(report.lines.any { it.contains("no unpacked Debian") })
        assertFalse(File(rootfs, "var/www/html").exists())
    }

    // ---- the one copy of the chat UI -------------------------------------------------------------

    @Test
    fun theGuestsCopyIsTheAppsOwnFilesAndThisFailsWhenTheyDiffer() {
        val assets = appWebDirectory()
        // The app's assets and the guest's document root are the same three files or this is a bug:
        // a fourth page added to the app's assets is a page the guest's Apache cannot serve.
        assertEquals(
            "app/src/main/assets/web holds ${sorted(assets)}, and WebRoot.FILES is " +
                "${WebRoot.FILES.sorted()}: one of them has changed and the other has not",
            sorted(assets),
            WebRoot.FILES.sorted(),
        )
        val fromTheApp = WebSource { name -> File(assets, name).readBytes() }
        val pathsHere = ProvisionPaths(folder.newFolder("real").path, null, folder.newFolder("w").path)
        val real = File(pathsHere.rootfsDir).apply { mkdirs() }
        File(real, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")

        val report = WebRoot(pathsHere, fromTheApp).install(RealVfs())

        assertEquals(report.lines.toString(), WebOutcome.INSTALLED, report.outcome)
        for (name in WebRoot.FILES) {
            assertArrayEquals(
                "app/src/main/assets/web/$name is not what the guest is serving",
                File(assets, name).readBytes(),
                File(real, "var/www/html/$name").readBytes(),
            )
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun webRoot(files: List<String> = WebRoot.FILES) = WebRoot(paths, appAssets, files)

    private fun sorted(dir: File) = dir.list()!!.toList().sorted()

    /**
     * The app's own asset directory, found by walking up from wherever the test was started.
     *
     * `:core` is a plain JVM module and this is a test, not a dependency: the point is to read the
     * *app's* files rather than a copy of them, and a test that quietly fell back to a fixture
     * would be exactly the drift this is here to catch. A repository without the app's assets has
     * no such directory, and the test fails rather than passing against a stand-in.
     */
    private fun appWebDirectory(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val assets = File(dir, "app/src/main/assets/web")
            if (assets.isDirectory) return assets
            dir = dir.parentFile
        }
        throw AssertionError(
            "app/src/main/assets/web was not found above ${File(".").absolutePath}: the test that " +
                "says the guest serves the app's own files cannot be run without them",
        )
    }
}
