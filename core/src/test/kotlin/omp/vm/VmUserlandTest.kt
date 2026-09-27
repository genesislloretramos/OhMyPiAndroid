package omp.vm

import omp.shell.fs.FsErrno
import omp.shell.fs.VNodeType
import omp.vm.pkg.DpkgDatabase
import omp.vm.rootfs.Rootfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The userland as a filesystem: a fresh boot has to produce a tree a real tool recognises, a second
 * boot has to leave the user's files alone, and the one file that must not be a fake
 * (`/etc/localtime`) has to say what it is.
 */
class VmUserlandTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun aFreshBootMaterialisesTheTreeAndTheOsReleaseSaysUbuntu() {
        val os = h.text("/etc/os-release")
        // The six keys a tool actually reads, in the real key=value form.
        assertTrue(os, os.contains("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\""))
        assertTrue(os, os.contains("NAME=\"Ubuntu\""))
        assertTrue(os, os.contains("ID=ubuntu"))
        assertTrue(os, os.contains("ID_LIKE=debian"))
        assertTrue(os, os.contains("VERSION_ID=\"24.04\""))
        assertTrue(os, os.contains("VERSION_CODENAME=noble"))
        assertTrue(os, os.contains("UBUNTU_CODENAME=noble"))
        assertEquals("24.04.1\n", h.text("/etc/debian_version"))
        assertTrue(h.text("/etc/lsb-release").contains("DISTRIB_CODENAME=noble"))

        // And the directories a real rootfs has.
        for (dir in listOf("/etc/apt", "/var/lib/dpkg", "/var/log/journal", "/usr/local/bin", "/home/ubuntu", "/root", "/media", "/opt", "/srv", "/boot")) {
            assertEquals("$dir is not a directory", VNodeType.DIRECTORY, h.kernel.vfs.stat(dir).type)
        }
        val motd = h.text("/etc/motd")
        assertTrue(motd, motd.contains("in-process"))
        assertTrue(motd, motd.contains("virtual machine"))
    }

    @Test
    fun bootingAgainLeavesAUsersEditAlone() {
        val motd = "/etc/motd"
        val edited = "my own motd, do not touch\n"
        h.kernel.vfs.writeBytes(motd, edited.toByteArray())
        h.kernel.vfs.writeBytes("/etc/hostname", "my-phone\n".toByteArray())

        val again = h.system.boot()
        assertEquals(edited, h.text(motd))
        assertEquals("the hostname must survive a boot too", "my-phone\n", h.text("/etc/hostname"))
        // And the second boot says there was nothing to create, which is what idempotent means.
        val rootfs = again.map { it.text }.filter { it.startsWith("rootfs: ") }
        assertTrue(rootfs.toString(), rootfs.any { it.contains("nothing to create") })
    }

    @Test
    fun resolvConfFollowsTheRouteAndSaysSoWhenThereIsNone() {
        // The stub's route names one server, and the file must name exactly that one.
        assertTrue(h.text("/etc/resolv.conf").contains("nameserver 8.8.8.8"))
        assertFalse(h.text("/etc/resolv.conf").contains("1.1.1.1"))

        val offline = VmHarness(File(folder.root, "offline"))
        offline.services.clearRoute()
        // The file exists from the boot, so drop it and let the next boot write it again.
        File(folder.root, "offline/vm/etc/resolv.conf").delete()
        offline.system.boot()
        val text = offline.text("/etc/resolv.conf")
        assertTrue("resolv.conf invented a nameserver: $text", !text.contains("nameserver"))
        assertTrue("resolv.conf should say there is no DNS: $text", text.contains("No DNS server"))
    }

    @Test
    fun theAccountFilesAgreeWithEachOtherAndWithTheMountTable() {
        val passwd = h.text("/etc/passwd")
        val users = h.system.users
        assertNotNull(users.byName("root"))
        assertEquals(0, users.byName("root")!!.uid)
        assertEquals(1000, users.byName("ubuntu")!!.uid)
        assertNotNull(users.byName("nobody"))
        assertEquals(65534, users.byName("nobody")!!.uid)
        assertTrue(passwd.contains("ubuntu:x:1000:1000:"))

        // shadow locks both real accounts, which is why su and sudo are honest about having no
        // credential to check.
        val shadow = h.text("/etc/shadow")
        assertTrue(shadow, shadow.contains("root:!:"))
        assertTrue(shadow, shadow.contains("ubuntu:!:"))
        assertTrue(shadow, shadow.contains("daemon:*:"))

        // fstab lists the mounts the kernel actually built, and no others — with one exception the
        // kernel itself documents: the bind it makes at every boot for its own conversations is not
        // a user's, so it is not recorded and not read back.
        val fstab = h.text("/etc/fstab")
        for (mount in h.kernel.mounts()) {
            if (mount.appOwned) {
                assertFalse("fstab records the app's own bind", fstab.contains(" ${mount.mountPoint} "))
                continue
            }
            val info = mount.info()
            assertTrue("fstab is missing ${info.target}", fstab.contains(" ${info.target} ${info.fstype} "))
        }
        for (line in fstab.lines()) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("tmpfs")) continue
            val target = line.split(" ")[1]
            assertNotNull("fstab lists $target, which is not mounted", h.kernel.vfs.mountAt(target))
        }
        // And the group file puts ubuntu in sudo, which the id test then reads back.
        assertTrue(h.text("/etc/group").contains("sudo:x:27:ubuntu"))
    }

    @Test
    fun localtimeSaysItIsNotATzfile() {
        val text = h.text("/etc/localtime")
        val first = text.lines().first()
        assertTrue(first, first.startsWith("# This is NOT a tzfile"))
        assertEquals("UTC", text.lines()[1])
        assertEquals("UTC\n", h.text("/etc/timezone"))
    }

    @Test
    fun machineIdIsThirtyTwoHexAndDerived() {
        val id = h.text("/etc/machine-id").trim()
        assertEquals(32, id.length)
        assertTrue(id, id.all { it in "0123456789abcdef" })
        assertEquals(id, h.text("/etc/machine-id").trim())
        // A different device identity is a different id.
        h.stub.props["ro.build.fingerprint"] = "google/stub/stub:15/other:1:user/release-keys"
        File(folder.root, "vm/etc/machine-id").delete()
        h.system.boot()
        assertTrue(id != h.text("/etc/machine-id").trim())
    }

    // ---- program files -----------------------------------------------------------------

    @Test
    fun syncProgramFilesWritesOneExecutableFilePerCommand() {
        val written = Rootfs.syncProgramFiles(h.kernel.vfs, h.table, h.services)
        assertTrue(written.toString(), written.isEmpty() || written.all { it.startsWith("/usr/") })

        // Every name in the table has a file, with the exact first line the exec path requires.
        for (name in h.table.names()) {
            val path = if (name in setOf("su", "sudo", "systemctl", "journalctl")) "/usr/sbin/$name" else "/usr/bin/$name"
            val file = h.kernel.vfs.stat(path)
            assertEquals(path, VNodeType.FILE, file.type)
            assertEquals(0x1ED, file.mode)
            val first = String(h.kernel.vfs.readBytes(path), Charsets.UTF_8).lines().first()
            assertEquals(path, "#!omp/v1 program $name", first)
        }
        assertEquals("ls", VmExec.programName(h.kernel.vfs, "/usr/bin/ls"))
        assertEquals("systemctl", VmExec.programName(h.kernel.vfs, "/usr/sbin/systemctl"))
    }

    @Test
    fun syncProgramFilesRemovesAFileWhoseProgramIsGone() {
        // A program file for a command the table does not have: the sync has to take it away.
        h.kernel.vfs.writeBytes("/usr/bin/ghost", Rootfs.programFile("ghost").toByteArray())
        assertEquals("ghost", VmExec.programName(h.kernel.vfs, "/usr/bin/ghost"))

        val touched = Rootfs.syncProgramFiles(h.kernel.vfs, h.table, h.services)
        assertTrue(touched.toString(), touched.contains("/usr/bin/ghost"))
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.stat("/usr/bin/ghost") })
    }

    @Test
    fun aUserFileInUsrBinIsNotRemoved() {
        h.kernel.vfs.writeBytes("/usr/bin/mine", "#!/bin/sh\necho mine\n".toByteArray())
        Rootfs.syncProgramFiles(h.kernel.vfs, h.table, h.services)
        // No shebang: it is not a program file this wrote, so it stays.
        assertEquals("#!/bin/sh\necho mine\n", h.text("/usr/bin/mine"))
    }

    @Test
    fun runningAProgramFileRunsTheProgram() {
        val listed = h.stdout("ls /usr/bin/ls")
        assertEquals("/usr/bin/ls\n", listed)
        // The exec path resolves the file to the command and runs it, for real.
        val result = h.run("sh -c '/usr/bin/ls /usr/bin/cat'")
        assertEquals(result.err, 0, result.status)
        assertEquals("/usr/bin/cat\n", result.out)
    }

    @Test
    fun fstabIsNotRewrittenByEveryBoot() {
        val first = h.text("/etc/fstab")
        h.system.boot()
        assertEquals(first, h.text("/etc/fstab"))
    }

    @Test
    fun theStatusFileIsGreppableThroughTheRealShell() {
        // The format dpkg writes, read by grep the way a user would read it.
        // Grepped through the real shell, with the options this shell's grep really has: a test that
        // leaned on a flag the command does not implement would fail for a reason that has nothing
        // to do with the database.
        val grepErr = h.stderr("grep '^Package: coreutils' /var/lib/dpkg/status")
        assertEquals("grep rejected the request: $grepErr", "", grepErr)
        assertEquals("Package: coreutils\n", h.stdout("grep '^Package: coreutils' /var/lib/dpkg/status"))
        assertTrue(
            "the status file has no installed stanzas",
            h.stdout("grep -c '^Status: install ok installed' /var/lib/dpkg/status").trim().toInt() >= 7,
        )
        assertTrue("the status file has no stanzas", DpkgDatabase(vfsOf(h)).stanzas().isNotEmpty())
    }

    private fun vfsOf(harness: VmHarness) = harness.kernel.vfs
}
