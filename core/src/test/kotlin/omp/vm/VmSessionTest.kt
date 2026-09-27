package omp.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The session the app hands to the user: the VM environment, the VM prompt, and a `cat` that reaches
 * the rootfs. If any of this is wrong the whole VM is unreachable, so it is worth a test of its own.
 */
class VmSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun theSessionHasTheVmEnvironment() {
        val session = h.openSession().session
        assertEquals("/home/ubuntu", session.env["HOME"])
        assertEquals("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", session.env["PATH"])
        assertEquals("ubuntu", session.env["USER"])
        assertEquals("ubuntu", session.env["LOGNAME"])
        assertEquals("/bin/sh", session.env["SHELL"])
        assertEquals("xterm-256color", session.env["TERM"])
        assertEquals("stub device", session.env["HOSTNAME"])
        assertEquals("en_US", session.env["LANG"])
        assertEquals("/home/ubuntu", session.cwd)
        // Nothing from the phone's PATH leaks in.
        assertFalse(session.env["PATH"]!!.contains("/system/bin"))
    }

    @Test
    fun theSessionReadsTheRootfsAndAnswersWithTheVmPrompt() {
        val shell = h.openSession()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = shell.shell.executeLine("cat /etc/os-release", ByteArrayInputStream(ByteArray(0)), out, err, true)
        assertEquals(String(err.toByteArray(), Charsets.UTF_8), 0, status)
        assertTrue(String(out.toByteArray(), Charsets.UTF_8).contains("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\""))
        assertTrue(shell.prompt().contains("ubuntu:"))

        // And the VM's own uname, not the phone's.
        val uname = ByteArrayOutputStream()
        shell.shell.executeLine("uname -r", ByteArrayInputStream(ByteArray(0)), uname, err, true)
        assertEquals("14-omp-vm\n", String(uname.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun theTableIsAPhoneSetPlusTheUserlandAndIsACopy() {
        val table = h.system.table
        // A phone command, a VM file command and the whole userland set are in the one table.
        assertTrue(table.lookup("echo") != null)
        assertTrue(table.lookup("ls") != null)
        for (name in listOf("apt", "apt-get", "dpkg", "dpkg-query", "systemctl", "journalctl", "sudo", "su", "login", "groups")) {
            assertTrue("the VM table is missing $name", table.lookup(name) != null)
        }
        // It is a copy of the phone's set, not the phone's set itself: a name registered on the
        // phone later cannot reach into a VM that is already running.
        assertFalse(table === omp.shell.exec.CommandTable.global)
    }

    @Test
    fun everyCommandInTheTableHasAProgramFileAfterABoot() {
        val sbin = setOf("su", "sudo", "systemctl", "journalctl")
        val missing = h.system.table.names().filter { name ->
            val path = if (name in sbin) "/usr/sbin/$name" else "/usr/bin/$name"
            VmExec.programName(h.kernel.vfs, path) != name
        }
        assertTrue("no program file for: $missing", missing.isEmpty())
    }
}
