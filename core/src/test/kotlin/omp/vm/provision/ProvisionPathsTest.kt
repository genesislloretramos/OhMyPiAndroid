package omp.vm.provision

import omp.shell.PlatformServices
import omp.shell.StubPlatformServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrangement, and the one fact about the platform it has to ask for.
 *
 * Two directories and not one is the whole deployment decision this layer makes, so the test that
 * matters is the one that says which artifact lands in which: the payload in app-private storage,
 * because proot's emulated loader *reads* it, and a native helper in the exec directory, because
 * the kernel has to *exec* that one. A single-directory layout would pass every other test in this
 * package and be wrong on a device.
 */
class ProvisionPathsTest {

    @Test
    fun theOrdinaryArrangementPutsThePayloadInAppStorageAndTheHelperWhereThePlatformSays() {
        val paths = ProvisionPaths.inAppStorage(platform(nativeLibraryDir = "/data/app/lib/arm64"))

        assertEquals("/data/user/0/com.omp.terminal/files", paths.targetDir)
        assertEquals("/data/app/lib/arm64", paths.execDirectory)
        assertEquals("/data/user/0/com.omp.terminal/files", paths.workDir)
        // The rootfs and the agent are payload: both under the target directory, and neither
        // anywhere near the read-only exec directory, which this layer never writes to at all.
        assertTrue(paths.rootfsDir.startsWith(paths.targetDir))
        assertTrue(paths.agentBinary.startsWith(paths.targetDir))
        assertFalse(paths.installDir.startsWith("/data/app/lib/arm64"))
        // A partial download is data too, and it lives beside the resume record.
        assertTrue(paths.partial("x").startsWith(paths.workDir))
        assertTrue(paths.stateFile.startsWith(paths.workDir))
    }

    @Test
    fun aPlatformThatDoesNotKnowItsNativeLibraryDirectoryStillGetsAnArrangement() {
        val paths = ProvisionPaths.inAppStorage(platform(nativeLibraryDir = null))

        // Null, and deliberately not a fallback to the payload: the payload directory is not an
        // exec location on any current Android, so naming it as one would be a directory this
        // layer looks able to use and is not.
        assertNull(paths.execDirectory)
        assertEquals("/data/user/0/com.omp.terminal/files", paths.targetDir)
        assertTrue(paths.installDir.startsWith(paths.targetDir))
    }

    @Test
    fun theDocumentRootAndTheGuestsMarkAreBothInsideTheDebianAndNowhereElse() {
        val paths = ProvisionPaths.inAppStorage(platform(nativeLibraryDir = null))

        // /var/www/html is Debian's own packaged DocumentRoot, so the guest's Apache serves the
        // app's pages with no configuration file written by this project at all.
        assertEquals(
            "/data/user/0/com.omp.terminal/files/omp/rootfs/var/www/html",
            paths.webRoot,
        )
        // Both marks live inside the tree they describe, so a deleted state file or a half-finished
        // unpack cannot leave one behind saying the work is done.
        assertTrue(paths.guestMarker.startsWith(paths.rootfsDir + "/"))
        assertTrue(paths.rootfsMarker.startsWith(paths.rootfsDir + "/"))
        assertFalse(paths.guestMarker.startsWith(paths.downloadDir + "/"))
    }

    /** The two directory questions, and nothing else: the default for [nativeLibraryDir] is null. */
    private fun platform(nativeLibraryDir: String?) = object : PlatformServices by stub() {
        override fun nativeLibraryDir(): String? = nativeLibraryDir
    }

    private fun stub() = StubPlatformServices(
        home = "/data/user/0/com.omp.terminal/files/home",
        initialDir = "/data/user/0/com.omp.terminal/files/home",
        appFiles = "/data/user/0/com.omp.terminal/files",
    )
}
