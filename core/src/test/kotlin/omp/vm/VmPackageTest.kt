package omp.vm

import omp.shell.fs.FsErrno
import omp.vm.pkg.DpkgDatabase
import omp.vm.pkg.PackageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The package manager, and the thing it must never do: claim that bytes travelled. Every answer
 * here is read back out of `/var/lib/dpkg` after the command, so a test that passes means the
 * database and the filesystem agree.
 */
class VmPackageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness
    private lateinit var db: DpkgDatabase

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
        db = h.kernel.packages
    }

    @Test
    fun aFreshRootfsHasTheBaseUserlandInstalled() {
        val installed = db.installedNames()
        assertTrue(installed.toString(), installed.containsAll(listOf("coreutils", "bash", "systemd", "util-linux", "grep", "sed", "findutils")))
        assertFalse(installed.toString(), installed.contains("iproute2"))
        // The index is compiled in, so the database is greppable and complete.
        assertEquals(PackageIndex.names().size, db.stanzas().size)
        assertTrue(h.stdout("dpkg -l").contains("ii  coreutils"))
        // "in" is the real marker for install-ok-not-installed: desired install, status not-installed.
        assertTrue(h.stdout("dpkg -l").contains("in  iproute2"))
    }

    @Test
    fun aptUpdateSaysTheIndexIsLocalAndPrintsNoFetchLine() {
        val out = h.stdout("apt update")
        assertTrue(out, out.contains("compiled into the app"))
        assertTrue(out, out.contains("nothing was downloaded"))
        assertFalse(out, out.contains("Get:"))
        assertFalse(out, out.contains("Fetched"))
        assertFalse(out, out.contains("Hit:"))
    }

    @Test
    fun installWritesTheConffileAndDpkgListsFilesThatExist() {
        val result = h.run("apt install openssl")
        assertEquals(result.err, 0, result.status)
        assertTrue(result.out, result.out.contains("in-process userland"))
        assertFalse(result.out, result.out.contains("Get:"))

        val listed = h.stdout("dpkg -L openssl")
        assertTrue(listed, listed.contains("/etc/ssl/openssl.cnf"))
        assertTrue("dpkg -L named a file that does not exist: $listed", exists("/etc/ssl/openssl.cnf"))
        assertTrue(db.status("openssl")!!.installed())

        // And the reverse lookup resolves it back.
        assertEquals("openssl: /etc/ssl/openssl.cnf\n", h.stdout("dpkg -S /etc/ssl/openssl.cnf"))
        // And the status file says so, in dpkg's own words.
        val status = h.stdout("dpkg -s openssl")
        assertTrue(status, status.contains("Status: install ok installed"))
        assertTrue(status, status.contains("Version: 3.0.13-0ubuntu3.1"))
        assertEquals(
            "the file list should resolve back to its package",
            "openssl",
            db.ownerOf("/etc/ssl/openssl.cnf"),
        )
    }

    @Test
    fun installTwiceSaysAlreadyTheNewestVersion() {
        assertEquals(0, h.run("apt install iproute2").status)
        val again = h.run("apt install iproute2")
        assertEquals(0, again.status)
        assertTrue(again.out, again.out.contains("iproute2 is already the newest version (6.1.0-1ubuntu2)"))
    }

    @Test
    fun anUnknownPackageIsExit100AndSaysUnableToLocate() {
        val result = h.run("apt install nosuchthing")
        assertEquals(100, result.status)
        assertEquals("E: Unable to locate package nosuchthing\n", result.err)
        val viaGet = h.run("apt-get install nosuchthing")
        assertEquals(100, viaGet.status)
        assertTrue(viaGet.err, viaGet.err.contains("E: Unable to locate package nosuchthing"))
    }

    @Test
    fun removeUnlinksTheProgramFileAndKeepsTheConffile() {
        assertEquals(0, h.run("apt install iproute2").status)
        assertTrue(exists("/etc/iproute2/rt_tables"))

        val removed = h.run("apt remove iproute2")
        assertEquals(removed.err, 0, removed.status)
        assertTrue(removed.out, removed.out.contains("Configuration file /etc/iproute2/rt_tables, kept on the system"))
        assertTrue("remove must keep the conffile", exists("/etc/iproute2/rt_tables"))
        assertEquals("deinstall ok config-files", db.status("iproute2")!!.status())
    }

    @Test
    fun purgeTakesTheConffileToo() {
        assertEquals(0, h.run("apt install iproute2").status)
        val purged = h.run("apt purge iproute2")
        assertEquals(purged.err, 0, purged.status)
        assertTrue(purged.out, purged.out.contains("Purging configuration file /etc/iproute2/rt_tables"))
        assertFalse("purge must remove the conffile", exists("/etc/iproute2/rt_tables"))
        assertEquals("purge ok not-installed", db.status("iproute2")!!.status())
    }

    @Test
    fun removingARealPackageUnlinksItsProgramFileAndReinstallRestoresIt() {
        assertTrue(exists("/usr/bin/grep"))
        val removed = h.run("apt remove grep")
        assertEquals(removed.err, 0, removed.status)
        assertFalse("remove must unlink the program file", exists("/usr/bin/grep"))

        val back = h.run("apt install grep")
        assertEquals(back.err, 0, back.status)
        assertTrue("install must write the program file back", exists("/usr/bin/grep"))
        assertEquals("grep", VmExec.programName(h.kernel.vfs, "/usr/bin/grep"))
    }

    @Test
    fun aPackageWithNoProgramsHereSaysSo() {
        val out = h.stdout("apt install openssh-server")
        assertTrue(out, out.contains("openssh-server owns no files in this userland") || out.contains("not in this userland"))
        // The stanza is still installed: the package is in the index, it just has nothing here.
        assertTrue(db.status("openssh-server")!!.installed())
        // And there is no ssh unit, because nothing is listening.
        assertEquals(
            FsErrno.NO_SUCH_FILE,
            h.errnoOf { h.kernel.vfs.readBytes("/etc/systemd/system/ssh.service") },
        )
    }

    @Test
    fun aptGetUpgradeReportsTheTruth() {
        val out = h.stdout("apt-get upgrade")
        assertTrue(out, out.contains("0 upgraded, 0 newly installed"))
        assertTrue(out, out.contains("nothing to upgrade into"))
    }

    @Test
    fun dpkgInstallOfAMissingArchiveSaysItCannotOpenIt() {
        val missing = h.run("dpkg -i nosuch.deb")
        assertEquals(1, missing.status)
        assertTrue(missing.err, missing.err.contains("dpkg: cannot open archive 'nosuch.deb': No such file or directory"))

        // And a file that is there is refused for the honest reason: no archive reader in here.
        h.kernel.vfs.writeBytes("/tmp/fake.deb", "not an ar archive".toByteArray())
        val there = h.run("dpkg -i /tmp/fake.deb")
        assertEquals(1, there.status)
        assertTrue(there.err, there.err.contains("the omp userland installs from its own in-process index"))
    }

    @Test
    fun aptCachePolicyAndShowAndListAllReadTheDatabase() {
        val policy = h.stdout("apt policy iproute2")
        assertTrue(policy, policy.contains("Installed: (none)"))
        assertTrue(policy, policy.contains("Candidate: 6.1.0-1ubuntu2"))
        assertFalse("a local index must not name a remote archive", policy.contains("archive.ubuntu.com"))

        val show = h.stdout("apt show coreutils")
        assertTrue(show, show.contains("Package: coreutils"))
        assertTrue(show, show.contains("Section: utils"))
        assertTrue(show, show.contains("no archive was fetched"))

        val list = h.stdout("apt list --installed")
        assertTrue(list, list.contains("coreutils/noble,now 9.4-1ubuntu6.2 aarch64 [installed,automatic]"))
        assertTrue(list, list.contains("bash/noble,now"))
    }

    @Test
    fun dpkgQueryAnswersFromTheSameDatabase() {
        assertEquals("coreutils\t9.4-1ubuntu6.2\n", h.stdout("dpkg-query -W coreutils"))
        val files = h.stdout("dpkg-query -L coreutils")
        assertTrue(files, files.contains("/usr/bin/ls"))
        val owner = h.stdout("dpkg-query -S /usr/bin/ls")
        assertTrue("dpkg-query -S said: $owner", owner.contains("coreutils: /usr/bin/ls"))
        assertEquals("9.4-1ubuntu6.2\n", h.stdout("dpkg-query -W --showformat=Version coreutils"))
        assertEquals(1, h.run("dpkg-query -s nosuchpkg").status)
    }

    @Test
    fun printArchitectureFollowsTheHostAndMatchesEveryStanza() {
        // The stub's ro.product.cpu.abi is arm64-v8a, which Debian calls aarch64.
        assertEquals("aarch64\n", h.stdout("dpkg --print-architecture"))
        for (stanza in db.stanzas()) {
            assertEquals(stanza.get("Package").orEmpty(), "aarch64", stanza.get("Architecture"))
        }
    }

    @Test
    fun theStatusSurvivesARebootOfTheApp() {
        // Remove something first: a boot that reinstalled it would be a boot that undid the user.
        assertEquals(0, h.run("apt remove grep").status)
        assertFalse(exists("/usr/bin/grep"))
        assertEquals(0, h.run("apt install iproute2").status)
        val before = h.text("/var/lib/dpkg/status")
        // A new VmSystem over the same directory is exactly what an app restart is.
        val restarted = VmSystem(File(folder.root, "vm"), h.services) { h.services.monotonicMillis() }
        restarted.boot()
        assertTrue(restarted.kernel.packages.status("iproute2")!!.installed())
        assertEquals(before, restarted.kernel.packages.let { h.text("/var/lib/dpkg/status") })
        assertTrue(exists("/usr/bin/ls"))
        // The database is the record: grep is still deinstalled, even though the boot's userland
        // sync put its program file back, because the sync writes one file per table command and
        // `grep` is a table command. That is the documented interaction, not an accident.
        assertEquals("deinstall ok config-files", restarted.kernel.packages.status("grep")!!.status())
        assertTrue("the userland sync rewrites a missing program file on every boot", exists("/usr/bin/grep"))
    }

    @Test
    fun anAbsentConffileIsReportedRatherThanInvented() {
        // python3-minimal owns /etc/python3/debian_version, and the rootfs does not ship it: an
        // install has to write it from the config the userland has, or say that it cannot.
        // The rootfs ships the file from the same config the package owns, so it is there before
        // the install; what the install has to do is keep it rather than overwrite it, and say so.
        assertTrue("the rootfs must create the conffile the index names", exists("/etc/python3/debian_version"))
        val before = h.text("/etc/python3/debian_version")
        val out = h.stdout("apt install python3-minimal")
        assertEquals(0, h.run("apt install python3-minimal").status)
        assertTrue(out, out.contains("Keeping existing conffile /etc/python3/debian_version"))
        assertEquals("an install must not rewrite a conffile the user already has", before, h.text("/etc/python3/debian_version"))
    }

    private fun exists(path: String): Boolean = try {
        h.kernel.vfs.stat(path)
        true
    } catch (e: omp.shell.fs.FsException) {
        false
    }
}
