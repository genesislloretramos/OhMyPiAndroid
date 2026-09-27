package com.omp.terminal.vm

import omp.vm.provision.Abi
import omp.vm.provision.ProotLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The half of shipping a native helper that can be read on a JVM, and it is most of the decisions.
 *
 * **What this file proves.** That the helper is found under the name the package manager is able to
 * extract, that the two shared libraries end up under the names the *dynamic linker* asks for even
 * though one of those is a name the extractor would refuse, that the environment proot runs under
 * carries the two variables it reads out of its own environment, and that a device this build has no
 * helper for is told so in the stream the user is reading rather than by an exception.
 *
 * **What it cannot prove, and does not pretend to.** That the package manager extracts anything, that
 * the kernel will `execve` what it extracts, or that the linker finds what `LD_LIBRARY_PATH` says —
 * all three need a device. What *is* checked here is that this code names the files those three
 * mechanisms are documented to act on, and that the name it names for `libtalloc.so.2` is the one
 * the binary's `DT_NEEDED` entry contains. A test that built a fake proot and ran it would prove
 * nothing about either.
 */
class ProotHelperTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val target = "data"
    private val exec = "app/lib/arm64-v8a"

    /** The environment `ProotCommand.env()` produces, so the guest's variables are recognisable. */
    private val guest = linkedMapOf(
        "HOME" to "/root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PROOT_NO_SECCOMP" to "1",
    )

    // ---- the names -----------------------------------------------------------------------------

    @Test
    fun theHelperIsNamedSoThePackageManagerCanExtractIt() {
        val helper = helper(exec, Abi.ARM64)

        // The two rules the extractor's own source states: the name starts with "lib" and ends with
        // ".so". Either one failing means the file is skipped silently, and the directory is only
        // ever populated at install time, so nothing would put it back.
        for (name in listOf(ProotHelper.PROOT, ProotHelper.LOADER)) {
            assertTrue("$name must start with lib", name.startsWith("lib"))
            assertTrue("$name must end with .so", name.endsWith(".so"))
        }
        assertEquals("$exec/libproot.so", helper.prootPath)
        assertEquals("$exec/libproot-loader.so", helper.loaderPath)
    }

    @Test
    fun theExecDirectoryIsTheOneThePackageManagerPickedAndNotOneThisComputes() {
        // Whatever `nativeLibraryDir` says is the answer, including a path this class has never
        // heard of. The package manager chose it per install; there is no search to invent here.
        val odd = "/data/app/~~Xk==/com.omp.terminal-7==/lib/arm64-v8a"
        assertEquals("$odd/libproot.so", helper(odd, Abi.ARM64).prootPath)
    }

    @Test
    fun aTrailingSlashIsNotRepeatedInThePath() {
        assertEquals("$exec/libproot.so", helper("$exec/", Abi.ARM64).prootPath)
    }

    // ---- the shared libraries -------------------------------------------------------------------

    @Test
    fun theVersionedLibraryIsPutWhereTheLinkerLooksUnderTheNameTheLinkerLooksFor() {
        // The whole difficulty in one test: `libtalloc.so.2` is the name in proot's DT_NEEDED and
        // the name no package manager will extract, and `libtalloc.so` is the name that is
        // extractable and the name the linker does not ask for. The file is shipped under one and
        // has to be *present* under the other.
        val execDir = folder.newFolder("nativeLibDir")
        File(execDir, "libtalloc.so").writeText("talloc bytes")
        File(execDir, "libandroid-shmem.so").writeText("shmem bytes")

        val helper = ProotHelper(execDir.path, folder.root.path, Abi.ARM64)
        assertEquals(emptyList<String>(), helper.installLibraries())

        val libDir = File(helper.libraryDir)
        assertEquals("talloc bytes", File(libDir, "libtalloc.so.2").readText())
        assertEquals("shmem bytes", File(libDir, "libandroid-shmem.so").readText())
    }

    @Test
    fun theLibraryDirectoryIsUnderAppStorageAndNotUnderTheReadOnlyOne() {
        // execDirectory is read-only to this app and is where anything is *executed*; the libraries
        // are only ever mmap'd, and Android 10's restriction is on execute_no_trans, not on the
        // execute permission that mmap(PROT_EXEC) needs. Two different directories for two
        // different reasons, and mixing them up is the bug this asserts against.
        val helper = helper(exec, Abi.ARM64)

        assertTrue(helper.libraryDir.startsWith(folder.root.path))
        assertFalse(helper.libraryDir.startsWith(exec))
    }

    @Test
    fun aLibraryThatIsNotThereIsNamedInTheAnswerRatherThanLeftToTheLinker() {
        val execDir = folder.newFolder("emptyNativeLibDir")
        File(execDir, "libtalloc.so").writeText("talloc bytes")

        val problems = ProotHelper(execDir.path, folder.root.path, Abi.ARM64).installLibraries()

        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().contains("libandroid-shmem.so"))
        assertFalse(problems.single(), problems.single().contains("libtalloc.so "))
    }

    @Test
    fun installingTwiceChangesNothing() {
        val execDir = folder.newFolder("twiceNativeLibDir")
        File(execDir, "libtalloc.so").writeText("talloc bytes")
        File(execDir, "libandroid-shmem.so").writeText("shmem bytes")
        val helper = ProotHelper(execDir.path, folder.root.path, Abi.ARM64)

        assertEquals(emptyList<String>(), helper.installLibraries())
        val after = File(helper.libraryDir, "libtalloc.so.2").readText()
        File(helper.libraryDir, "libtalloc.so.2").writeText("truncated")
        assertEquals(emptyList<String>(), helper.installLibraries())

        // Length is the idempotence test, so a truncated file is put back rather than trusted.
        assertTrue(File(helper.libraryDir, "libtalloc.so.2").length().toInt() == after.length)
    }

    // ---- the environment -----------------------------------------------------------------------

    @Test
    fun theEnvironmentTellsTheLinkerAndProotWhatTheyReadOutOfIt() {
        val env = helper(exec, Abi.ARM64).env(guest)

        // The dynamic linker searches LD_LIBRARY_PATH, then DT_RUNPATH, then /system/lib64. It never
        // searches the executable's own directory, and this proot's DT_RUNPATH is Termux's, so this
        // is the only channel there is for libtalloc.so.2.
        assertEquals(File(folder.root.path, "data/omp/proot-libs").path, env["LD_LIBRARY_PATH"])
        // And proot's own fallback is /data/data/com.termux/..., which does not exist for this app.
        assertEquals("$exec/libproot-loader.so", env["PROOT_LOADER"])
    }

    @Test
    fun theGuestsEnvironmentIsCarriedThroughWholeAndNotRebuilt() {
        val env = helper(exec, Abi.ARM64).env(guest)

        assertEquals(guest, LinkedHashMap(env).also { it.remove("LD_LIBRARY_PATH"); it.remove("PROOT_LOADER") })
        assertEquals(1, env.entries.count { it.key == "PROOT_NO_SECCOMP" })
        // The two added variables are not in the guest's map, so the guest's copy of the fact and
        // this app's copy of it cannot disagree by both being set from two places.
        assertFalse(guest.containsKey("LD_LIBRARY_PATH"))
        assertFalse(guest.containsKey("PROOT_LOADER"))
    }

    @Test
    fun prootNoSeccompIsNotSetTwice() {
        val env = helper(exec, Abi.ARM64).env(guest)

        // ProotCommand.env() already says it, and it is a phone fact rather than a guest one, so
        // this class does not own it and must not restate it.
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals(1, env.entries.count { it.key == "PROOT_NO_SECCOMP" })
    }

    // ---- the devices this build has nothing for --------------------------------------------------

    @Test
    fun aDeviceWithNoNativeLibraryDirectoryIsRefusedWithASentenceAndAStatus() {
        val out = ByteArrayOutputStream()
        var ran = false
        val inner = ProotLauncher { _, _, _ -> ran = true; 0 }
        val native = NativeProot(helper(null, Abi.ARM64), out, inner)

        val status = native.run(listOf("/nowhere/libproot.so"), guest, folder.root.path)

        assertEquals(ProotProcessLauncher.NOT_STARTED, status)
        assertFalse("nothing may be launched when there is no helper", ran)
        val said = String(out.toByteArray())
        assertTrue(said, said.contains("native library directory"))
    }

    @Test
    fun aDeviceWhoseArchitectureThisBuildDoesNotPackageIsRefusedRatherThanGivenAPath() {
        val helper = helper(exec, null)

        assertNotNull(helper.refusal)
        assertNull(helper.prootPath)
        assertNull(helper.loaderPath)
        assertFalse(helper.env(guest).containsKey("LD_LIBRARY_PATH"))
    }

    @Test
    fun everyAbiThisProjectClaimsResolvesToTheSameLayoutInADifferentDirectory() {
        for (abi in Abi.entries) {
            val helper = helper("/data/app/lib/${abi.abiName}", abi)

            assertNull("${abi.abiName} must resolve", helper.refusal)
            assertEquals("/data/app/lib/${abi.abiName}/libproot.so", helper.prootPath)
        }
    }

    // ---- the launcher that carries it -----------------------------------------------------------

    @Test
    fun theLauncherRunsTheProcessWithTheEnvironmentTheHelperBuilt() {
        val execDir = folder.newFolder("runNativeLibDir")
        File(execDir, "libtalloc.so").writeText("talloc bytes")
        File(execDir, "libandroid-shmem.so").writeText("shmem bytes")
        val helper = ProotHelper(execDir.path, folder.root.path, Abi.ARM64)
        var seen: Map<String, String>? = null
        val inner = ProotLauncher { _, env, _ -> seen = env; 0 }

        val status = NativeProot(helper, ByteArrayOutputStream(), inner)
            .run(listOf(helper.prootPath!!), guest, folder.root.path)

        assertEquals(0, status)
        assertEquals(helper.env(guest), seen)
        assertTrue(File(helper.libraryDir, "libtalloc.so.2").isFile)
    }

    @Test
    fun aMissingLibraryStopsTheProcessBeforeTheLinkerHasToReportIt() {
        val helper = ProotHelper(folder.newFolder("noLibs").path, folder.root.path, Abi.ARM64)
        var ran = false
        val inner = ProotLauncher { _, _, _ -> ran = true; 0 }

        val status = NativeProot(helper, ByteArrayOutputStream(), inner)
            .run(listOf("/nowhere/libproot.so"), guest, folder.root.path)

        assertEquals(ProotProcessLauncher.NOT_STARTED, status)
        assertFalse(ran)
    }

    // ---- ---------------------------------------------------------------------------------------

    private fun helper(execDirectory: String?, abi: Abi?) =
        ProotHelper(execDirectory, folder.root.path + "/$target", abi)
}
