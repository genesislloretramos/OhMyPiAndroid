package omp.vm.provision

import omp.vm.VmSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The argument vector and the environment, pinned exactly.
 *
 * This is the whole of what is verifiable about proot in a JVM test: the strings. The flags are
 * proot's documented ones and the binds are this app's own directories, and every one of them is a
 * decision somebody has to be able to read in a diff — a wrong bind is a file the guest cannot see,
 * and a missing `TERM` is a screen full of escape sequences. What none of it proves is that proot
 * does what it is asked; that needs a device, and [ProotCommand]'s own KDoc lists what to look at.
 */
class ProotCommandTest {

    private val command = ProotCommand(
        prootPath = "/data/app/~~a==/com.omp.terminal-1/lib/arm64/omp/proot",
        rootfs = "/data/user/0/com.omp.terminal/files/omp/rootfs",
        host = Abi.ARM64,
        guest = Abi.ARM64,
        dataDir = "/data/user/0/com.omp.terminal/files",
        visibleDir = "/storage/emulated/0/Documents/omp",
        agentDir = "/data/user/0/com.omp.terminal/files/omp/bin",
    )

    @Test
    fun theArgumentVectorIsExactlyThis() {
        assertEquals(
            listOf(
                // proot itself, in the exec directory: the one file here the kernel execs.
                "/data/app/~~a==/com.omp.terminal-1/lib/arm64/omp/proot",
                "-r", "/data/user/0/com.omp.terminal/files/omp/rootfs",
                // The app's own storage, one path in and one path out, so the agent finds its
                // transcripts and its key without a translation.
                "-b", "/data/user/0/com.omp.terminal/files:/data/user/0/com.omp.terminal/files",
                // The one directory a user can open in a file manager, where the VM puts it too.
                "-b", "/storage/emulated/0/Documents/omp:/mnt/omp",
                // The agent, 0755 and read by proot, in a guest whose /usr/bin is the rootfs.
                "-b", "/data/user/0/com.omp.terminal/files/omp/bin:/usr/local/bin",
                "-w", "/root",
                "-k", "/data/user/0/com.omp.terminal/files/proot.pid",
                "/usr/local/bin/omp",
                "update",
            ),
            command.argv(listOf("/usr/local/bin/omp", "update")),
        )
    }

    @Test
    fun theAgentArgvCarriesTheWordsTheAgentUnderstands() {
        assertEquals(
            listOf("/usr/local/bin/omp"),
            command.agentArgv(emptyList()).takeLast(1),
        )
        assertEquals(
            listOf("/usr/local/bin/omp", "update"),
            command.agentArgv(listOf("update")).takeLast(2),
        )
        assertEquals("/usr/local/bin/omp", command.agentPath())
    }

    @Test
    fun theEnvironmentIsTheGuestsAndNotThePhones() {
        assertEquals(
            mapOf(
                "HOME" to "/root",
                // A real Debian PATH and nothing from Android: `/system/bin` does not exist in here.
                "PATH" to VmSystem.PATH,
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "TMPDIR" to "/tmp",
                "PROOT_NO_SECCOMP" to "1",
            ),
            command.env(),
        )
    }

    @Test
    fun aForeignRootfsHasToNameItsEmulatorRatherThanGuessOne() {
        val foreign = ProotCommand(
            prootPath = "/lib/proot",
            rootfs = "/rootfs",
            host = Abi.ARM64,
            guest = Abi.X86_64,
            dataDir = "/data",
            visibleDir = "/sdcard/Documents/omp",
            agentDir = "/lib/bin",
            qemu = "qemu-x86_64-static",
        )
        val argv = foreign.argv(listOf("/usr/local/bin/omp"))
        val at = argv.indexOf("-q")
        assertTrue("a foreign guest must name an emulator", at > 0)
        assertEquals("qemu-x86_64-static", argv[at + 1])
        // And the same-arch vector names none, because a binary built for this device's own
        // architecture is exec'd by proot unassisted.
        assertTrue(!command.argv(listOf("/usr/local/bin/omp")).contains("-q"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aForeignRootfsWithNoEmulatorIsRefusedRatherThanGivenAWrongName() {
        ProotCommand(
            prootPath = "/lib/proot",
            rootfs = "/rootfs",
            host = Abi.ARM64,
            guest = Abi.X86_64,
            dataDir = "/data",
            visibleDir = "/sdcard/Documents/omp",
            agentDir = "/lib/bin",
        ).argv(listOf("/usr/local/bin/omp"))
    }
}
