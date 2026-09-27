package omp.vm

import omp.shell.fs.FsErrno
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * systemd-lite. The tests that matter are the failure ones: a unit that is running, a unit that
 * failed and why, a unit that does not exist, and a unit whose pid did not survive a reboot — which
 * must come back `failed`, never `active`.
 */
class VmServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun theUnitsThatReallyExistAreTheThreeAndNotSsh() {
        val units = h.system.kernel.units.units().map { it.name }
        assertEquals(listOf("omp-vmd", "systemd-journald", "vm-hostbridge"), units)
        assertFalse("there is no sshd, so there is no ssh unit", units.contains("ssh"))
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.stat("/etc/systemd/system/ssh.service") })
    }

    @Test
    fun statusOfARunningUnitShowsStateSinceAndItsPid() {
        val out = h.stdout("systemctl status omp-vmd")
        assertTrue(out, out.contains("omp-vmd.service - omp VM kernel"))
        assertTrue(out, out.contains("Active: active (running)"))
        assertTrue(out, out.contains("Main PID: 1"))
        assertTrue(out, out.contains("Loaded: /etc/systemd/system/omp-vmd.service (enabled)"))
        assertEquals(0, h.run("systemctl status omp-vmd").status)
    }

    @Test
    fun statusOfAStoppedUnitIsInactiveAndExitsNonZero() {
        h.stdout("systemctl stop vm-hostbridge")
        val result = h.run("systemctl status vm-hostbridge")
        assertTrue(result.out, result.out.contains("Active: inactive (dead)"))
        assertTrue(result.out, result.out.contains("Main PID: -"))
        assertEquals(1, result.status)
        assertEquals("inactive\n", h.stdout("systemctl is-active vm-hostbridge"))
    }

    @Test
    fun statusOfAUnitThatDoesNotExistSaysLoadedNotFound() {
        val result = h.run("systemctl status no-such-unit")
        // 4 is the code systemd itself uses for a unit it has never heard of.
        assertEquals(4, result.status)
        assertTrue(result.err, result.err.contains("no-such-unit: loaded not-found"))
        assertEquals("unknown\n", h.stdout("systemctl is-active no-such-unit"))
    }

    @Test
    fun startAndStopAndRestartMoveTheUnit() {
        h.stdout("systemctl stop omp-vmd")
        assertEquals("started omp-vmd.service\n", h.stdout("systemctl start omp-vmd"))
        assertEquals("active\n", h.stdout("systemctl is-active omp-vmd"))
        assertEquals("stopped omp-vmd.service\n", h.stdout("systemctl stop omp-vmd"))
        assertEquals("inactive\n", h.stdout("systemctl is-active omp-vmd"))
        assertTrue(h.stdout("systemctl restart omp-vmd").contains("started omp-vmd.service"))
        assertEquals("active\n", h.stdout("systemctl is-active omp-vmd"))
    }

    @Test
    fun enableCreatesTheWantsSymlinkAndDisableRemovesIt() {
        val disabled = h.stdout("systemctl disable systemd-journald")
        assertEquals("disabled systemd-journald.service\n", disabled)
        assertEquals("disabled\n", h.stdout("systemctl is-enabled systemd-journald"))
        assertEquals(
            FsErrno.NO_SUCH_FILE,
            h.errnoOf { h.kernel.vfs.stat("/etc/systemd/system/multi-user.target.wants/systemd-journald.service") },
        )

        assertEquals("enabled systemd-journald.service\n", h.stdout("systemctl enable systemd-journald"))
        assertEquals("enabled\n", h.stdout("systemctl is-enabled systemd-journald"))
        // A link is asked about with readLink: reading its bytes would answer with the target's.
        assertEquals(
            "../systemd-journald.service",
            h.kernel.vfs.readLink("/etc/systemd/system/multi-user.target.wants/systemd-journald.service"),
        )
        assertTrue(h.stdout("systemctl list-unit-files").contains("enabled"))
    }

    @Test
    fun listUnitsAndListUnitFilesBothAnswer() {
        val units = h.stdout("systemctl list-units")
        assertTrue(units, units.contains("omp-vmd.service"))
        assertTrue(units, units.contains("vm-hostbridge.service"))
        val files = h.stdout("systemctl list-unit-files")
        assertTrue(files, files.contains("/etc/systemd/system/omp-vmd.service"))
        assertTrue(files, files.contains("/etc/systemd/system/systemd-journald.service"))
    }

    @Test
    fun aUnitWhosePidIsGoneComesBackFailedWithAReason() {
        // The state file a previous boot wrote, naming a pid this VM has never heard of. That is
        // exactly what an app restart leaves behind for a unit that was running a command.
        h.kernel.vfs.writeBytes(
            "/var/lib/omp/units/vm-hostbridge.service.state",
            "State=active\nMainPID=4242\nSince=1700000000000\nReason=\n".toByteArray(),
        )
        val lines = h.system.boot().map { it.text }
        assertTrue(
            lines.joinToString("\n"),
            lines.any { it.contains("fail vm-hostbridge.service: Main process exited, status=gone/MainPID-gone") },
        )
        val result = h.run("systemctl status vm-hostbridge")
        assertTrue(result.out, result.out.contains("Active: failed"))
        assertTrue(result.out, result.out.contains("Reason: Main process exited, status=gone/MainPID-gone"))
    }

    @Test
    fun aUnitWhosePidIsAliveSurvivesTheBoot() {
        // omp-vmd's pid is 1, and init is registered again on every boot: it stays active.
        h.stdout("systemctl start omp-vmd")
        h.system.boot()
        assertEquals("active\n", h.stdout("systemctl is-active omp-vmd"))
    }

    @Test
    fun journalctlReturnsTheLinesTheUnitActuallyLogged() {
        val units = h.system.kernel.units
        units.log("vm-hostbridge", "info", "first line for the test")
        units.log("vm-hostbridge", "warning", "second line for the test")
        units.log("vm-hostbridge", "err", "third line for the test")

        val all = h.stdout("journalctl -u vm-hostbridge")
        assertTrue(all, all.contains("first line for the test"))
        assertTrue(all, all.contains("third line for the test"))

        val last = h.stdout("journalctl -u vm-hostbridge -n 2").lines().filter { it.isNotBlank() }
        assertEquals(last.toString(), 2, last.size)
        assertTrue(last.toString(), last.any { it.contains("second line for the test") })
        assertTrue(last.toString(), last.any { it.contains("third line for the test") })
        assertFalse(last.toString(), last.any { it.contains("first line for the test") })

        // -p filters on the real syslog priorities, and -b on the current boot id.
        val errors = h.stdout("journalctl -p err")
        assertTrue(errors, errors.contains("third line for the test"))
        assertFalse(errors, errors.contains("first line for the test"))
        assertTrue(h.stdout("journalctl -b").contains("Started"))
        assertEquals("-- No entries --\n", h.stdout("journalctl -u systemd-journald -n 1 -p emerg"))
    }

    @Test
    fun theJournalIsPlainTextAndSaysSo() {
        val readme = h.text("/var/log/journal/README")
        assertTrue(readme, readme.contains("NOT systemd"))
        assertTrue(readme, readme.contains("plain text file per unit"))
    }

    @Test
    fun sudoLogsTheLineSudoLogs() {
        h.run("sudo id")
        val log = h.text("/var/log/journal/sudo.service.log")
        // The unit name is the file the line is in, so the line itself does not repeat "sudo:".
        assertTrue(log, log.contains("ubuntu@omp-ubuntu : TTY=unknown ; PWD=/home/ubuntu ; USER=root ; COMMAND=id"))
        assertTrue(log, log.contains("USER=root"))
        assertTrue(log, log.contains("COMMAND=id"))
    }
}
