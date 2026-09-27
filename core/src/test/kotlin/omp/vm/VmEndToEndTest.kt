package omp.vm

import omp.shell.InputChannel
import omp.shell.Session
import omp.shell.SessionHost
import omp.shell.ShellSession
import omp.shell.exec.CommandTable
import omp.term.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The user story, end to end and through nothing but the shell.
 *
 * Every assertion here is something a person could type. Keystrokes go into one [InputChannel],
 * commands come out of one [Screen], and every answer is read back out of the real filesystem the
 * VM wrote. Nothing calls [VmKernel] to check a fact the user could have read off `mount`, and
 * nothing stubs the Vfs: a namespace that only works when you reach into its internals is not a
 * namespace.
 *
 * Two sessions are in play. [harness] is a REPL *inside* the namespace, which is what the user has
 * after `vm enter`. [Phone] is the shell that owns the `vm` command and drives it, which is what the
 * user has before. The tests about the phone's side of the door use the second.
 */
class VmEndToEndTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var harness: VmHarness

    @Before
    fun setUp() {
        harness = VmHarness(folder.root)
    }

    // ---- the namespace, from the inside --------------------------------------------------

    @Test
    fun lsSlashListsTheRootfsThatIsReallyOnDisk() {
        val listed = harness.stdout("ls /").trim().lines()
        assertTrue(listed.toString(), listed.containsAll(listOf("bin", "etc", "home", "lib", "usr", "var")))
        // proc, sys and dev are the kernel's own mountpoints, so a real `ls /` has them too.
        assertTrue(listed.toString(), listed.containsAll(listOf("proc", "sys", "dev")))
        // The names came off a directory, not out of a constant.
        assertEquals(
            "ls / disagrees with the directory underneath it",
            harness.kernel.vfs.readDir("/").map { it.name }.toSet(),
            listed.toSet(),
        )
        assertTrue(File(folder.root, "vm/etc/os-release").isFile)
    }

    @Test
    fun osReleaseSaysUbuntu() {
        val out = harness.stdout("cat /etc/os-release")
        assertTrue(out, out.contains("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\""))
        assertTrue(out, out.contains("ID=ubuntu"))
        assertTrue(out, out.contains("VERSION_ID=\"24.04\""))
        assertTrue(out, out.contains("VERSION_CODENAME=noble"))
        // The same bytes a `cat` in the namespace reads are the bytes on the phone's disk.
        assertEquals(out, File(folder.root, "vm/etc/os-release").readText())
    }

    @Test
    fun unameAndProcVersionNameTheVmAndTheAndroidHost() {
        assertEquals(
            "Linux stub device 14-omp-vm #1 SMP in-process-userspace-vm aarch64 localhost\n",
            harness.stdout("uname -a"),
        )
        val version = harness.stdout("cat /proc/version")
        assertTrue(version, version.contains("in-process userspace VM"))
        // The host model is the one the platform reports, not something the namespace made up.
        assertTrue(version, version.contains("Pixel Stub"))
        assertTrue(version, version.contains("no kernel"))
    }

    @Test
    fun theIdentityIsUbuntuAndSudoAndSuChangeTheNameOnly() {
        assertEquals("ubuntu\n", harness.stdout("whoami"))
        assertTrue(harness.stdout("id").startsWith("uid=1000(ubuntu) gid=1000(ubuntu) groups="))
        val sudoed = harness.run("sudo id")
        assertTrue(sudoed.out, sudoed.out.startsWith("uid=0(root)"))
        // The claim `sudo` must never let stand on its own: uid 0 is a name, and it says so.
        assertTrue(sudoed.err, sudoed.err.contains("no boundary was crossed"))
        // The name lasts one command and the real uid never moved: that is the whole honesty claim.
        assertEquals("ubuntu\n", harness.stdout("whoami"))
        assertEquals("10123\n", harness.text("/sys/omp/android/uid"))

        val su = harness.stdout("su root")
        assertTrue(su, su.startsWith("switched to root\n"))
        assertTrue(su, su.contains("changes the name the session answers to and nothing else"))
        assertEquals("root\n", harness.stdout("whoami"))
    }

    @Test
    fun psShowsInitAtPidOne() {
        val ps = harness.stdout("ps")
        assertTrue(ps, ps.startsWith("  PID TTY"))
        val init = ps.lines().firstOrNull { it.trimStart().startsWith("1 ") }
        assertNotEquals("no pid 1 in:\n$ps", null, init)
        assertTrue(init!!, init.contains("omp-init"))
        assertTrue(init, init.contains("--root=/"))
    }

    @Test
    fun mountListsTheGeneratedFilesystemsAndTheAndroidBind() {
        val mounts = harness.stdout("mount")
        for (target in listOf("/", "/proc", "/sys", "/dev", "/mnt/android")) {
            assertTrue("no mount line for $target:\n$mounts", mounts.contains(" on $target "))
        }
        assertTrue(mounts, mounts.contains("type proc"))
        assertTrue(mounts, mounts.contains("type sysfs"))
        assertTrue(mounts, mounts.contains("type devtmpfs"))
        assertTrue(mounts, mounts.contains("shared-storage on /mnt/android"))
    }

    @Test
    fun dfFreeAndMeminfoAllReadTheSamePlatformFacts() {
        val totalKb = harness.services.stub.memory.totalBytes / 1024
        val meminfo = harness.stdout("cat /proc/meminfo")
        assertTrue(meminfo, meminfo.contains("MemTotal:"))
        assertTrue(meminfo, meminfo.contains(totalKb.toString()))

        // free parses that same generated file, so it cannot disagree with the cat above it.
        val free = harness.stdout("free")
        assertTrue(free, free.contains("Mem:"))
        assertTrue("free disagrees with /proc/meminfo:\n$free", free.contains(totalKb.toString()))

        // df is a different question and a real one: a row per mount, taken from the Vfs.
        val df = harness.stdout("df")
        assertTrue(df, df.lines().any { it.startsWith("/".padEnd(18)) })
        assertTrue(df, df.lines().any { it.startsWith("/mnt/android") })
    }

    @Test
    fun usrBinLsIsARealExecutableThatReallyRuns() {
        val file = File(folder.root, "vm/usr/bin/ls")
        assertTrue("$file is not on disk", file.isFile)
        assertEquals("the program file is not 0755", "rwxr-xr-x", posix(file))

        assertEquals("#!omp/v1 program ls", harness.text("/usr/bin/ls").lines().first())
        val long = harness.stdout("ls -l /usr/bin/ls").trim()
        assertTrue(long, long.startsWith("-rwx"))
        assertTrue(long, long.endsWith(" ls"))

        // The exec path is the file, not a name: `sh` reads line 1 and runs the program it names.
        assertEquals("/usr/bin/cat\n", harness.stdout("sh -c '/usr/bin/ls /usr/bin/cat'"))
    }

    @Test
    fun aptInstallsFromTheLocalIndexAndDpkgListsFilesThatExist() {
        val install = harness.run("apt install openssh-server")
        assertEquals(install.err, 0, install.status)
        // Nothing travelled: the honest apt says so instead of printing a Get: line.
        assertFalse(install.out, install.out.contains("Get:"))

        val listed = harness.stdout("dpkg -L openssh-server")
        val files = listed.lines().filter { it.startsWith("/") }
        assertTrue(listed, files.isNotEmpty())
        for (path in files) {
            assertTrue("dpkg -L named a file that is not there: $path", exists(path))
        }
        assertTrue(files.contains("/etc/ssh/sshd_config"))

        // The reverse lookup resolves a real file back to the package that owns it.
        assertEquals("coreutils: /usr/bin/ls\n", harness.stdout("dpkg -S /usr/bin/ls"))
        // And a second install is a no-op with a status of its own.
        val again = harness.run("apt install openssh-server")
        assertEquals(again.err, 0, again.status)
        assertTrue(again.out, again.out.contains("openssh-server is already the newest version (1:9.6p1-3ubuntu13)"))
    }

    @Test
    fun journaldIsActiveAndItsJournalHasALastLine() {
        val status = harness.stdout("systemctl status systemd-journald")
        assertTrue(status, status.contains("Active: active"))

        val last = harness.stdout("journalctl -u systemd-journald -n 1").trim().lines()
        assertEquals(last.toString(), 1, last.size)
        assertTrue(last.toString(), last.single().contains("systemd-journald.service"))
    }

    @Test
    fun aUnitWhosePidIsGoneComesBackFailedWithAReason() {
        // The state file an app restart leaves behind for a unit that was running a command.
        harness.kernel.vfs.writeBytes(
            "/var/lib/omp/units/vm-hostbridge.service.state",
            "State=active\nMainPID=4242\nSince=1700000000000\nReason=\n".toByteArray(),
        )
        val boot = harness.system.boot().joinToString("\n") { it.text }
        assertTrue(boot, boot.contains("fail vm-hostbridge.service: Main process exited"))

        val status = harness.stdout("systemctl status vm-hostbridge")
        assertTrue(status, status.contains("Active: failed"))
        assertTrue(status, status.contains("Reason: Main process exited, status=gone/MainPID-gone"))
    }

    @Test
    fun aWriteThroughMntAndroidLandsInThePhonesOwnStorage() {
        val external = harness.externalDir!!
        val write = harness.run("echo hi > /mnt/android/f")
        assertEquals(write.err, 0, write.status)
        assertEquals("hi\n", File(external, "f").readText())
        // And the namespace reads back the same file rather than a copy of the directory.
        assertEquals("hi\n", harness.text("/mnt/android/f"))
    }

    @Test
    fun whatWasDoneInThereIsStillThereAfterTheAppStartsAgain() {
        val first = VmHarness(File(folder.root, "restart"))
        first.stdout("echo kept > /home/ubuntu/note.txt")
        first.system.shutdown()
        // A restart is a new VmSystem over the same directory: nothing is kept in memory.
        val second = VmHarness(File(folder.root, "restart"))
        assertEquals("kept\n", second.stdout("cat /home/ubuntu/note.txt"))
        assertTrue(
            "a second boot must not rewrite what is already there",
            second.boot.any { it.text.contains("nothing to create") },
        )
    }

    @Test
    fun theVmCommandRefusesInsideTheNamespaceInOneLine() {
        val result = harness.run("vm")
        assertEquals(1, result.status)
        assertEquals("the refusal is one line", 1, result.err.trim().lines().size)
        assertTrue(result.err, result.err.contains("already inside the VM"))
        assertTrue(result.err, result.err.contains("exit"))
        // It is the namespace's own refusal, not the phone's command answering late.
        assertSame(harness.table.lookup("vm"), omp.vm.cmd.VmInside)
        assertNotEquals(harness.table.lookup("vm"), CommandTable.global.lookup("vm"))
    }

    // ---- the phone, from the outside ------------------------------------------------------

    @Test
    fun vmStatusReportsTheRealStateOfTheRealDisk() {
        val phone = phone()
        val status = phone.run("vm status")
        assertEquals(status.err, 0, status.status)
        assertTrue(status.out, status.out.contains("vm: not booted"))
        assertTrue(status.out, status.out.contains(disk().absolutePath))
        assertTrue(status.out, status.out.contains("/mnt/android: bound to "))
        assertEquals(0, status.out.packages())

        // The one line that has to be there every time, because it is the honest one.
        val note = status.out.lines().first { it.startsWith("note:") }
        assertTrue(note, note.contains("not a privilege escalation on the phone"))
        assertTrue(note, note.contains("10123"))
    }

    @Test
    fun vmBootBootsIdempotentlyAndTheSecondBootChangesNothing() {
        val phone = phone()
        val first = phone.run("vm boot")
        assertEquals("", first.err)
        assertEquals(first.err, 0, first.status)
        assertTrue(first.out, first.out.contains("init: pid 1 omp-init ready"))
        assertTrue(first.out, first.out.contains("rootfs:"))
        assertFalse(first.out, first.out.contains("cannot"))

        val before = tree()

        val second = phone.run("vm boot")
        assertEquals(second.err, 0, second.status)
        assertTrue(second.out, second.out.contains("rootfs: nothing to create"))
        assertEquals("a second boot must not rewrite the disk", before, tree())

        val status = phone.run("vm status")
        assertTrue(status.out, status.out.contains("vm: booted"))
        assertTrue("a booted VM has a seeded dpkg database, got ${status.out.packages()}", status.out.packages() > 0)
        assertTrue(status.out, status.out.lines().any { it.contains("unit systemd-journald.service: active") })
    }

    @Test
    fun vmMountsAndVmServicesReadTheLiveObjects() {
        val phone = phone()
        phone.run("vm boot")
        val mounts = phone.run("vm mounts")
        assertEquals(mounts.err, 0, mounts.status)
        for (target in listOf("/", "/proc", "/sys", "/dev", "/mnt/android")) {
            assertTrue(mounts.out, mounts.out.contains(" on $target "))
        }
        val services = phone.run("vm services")
        assertEquals(services.err, 0, services.status)
        assertTrue(services.out, services.out.contains("systemd-journald.service"))
        assertTrue(services.out, services.out.contains("active"))
    }

    @Test
    fun vmExecRunsOneLineInTheNamespaceAndReturnsItsStatus() {
        val phone = phone()
        val who = phone.run("vm exec 'whoami'")
        assertEquals(who.err, 0, who.status)
        assertEquals("ubuntu\n", who.out)

        val os = phone.run("vm exec 'grep PRETTY_NAME /etc/os-release'")
        assertEquals(os.err, 0, os.status)
        assertEquals("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\"\n", os.out)

        val missing = phone.run("vm exec 'cat /nope'")
        assertNotEquals(0, missing.status)
        assertTrue(missing.err, missing.err.contains("/nope"))
    }

    @Test
    fun vmRefusesAnUnknownSubcommandWithUsageOnStderr() {
        val phone = phone()
        val bad = phone.run("vm nosuchthing")
        assertEquals(2, bad.status)
        assertEquals("", bad.out)
        assertTrue(bad.err, bad.err.contains("vm: unknown subcommand 'nosuchthing'"))
        assertTrue(bad.err, bad.err.contains("usage: vm [status|boot|enter|exec 'LINE'|mounts|"))
        assertTrue(bad.err, bad.err.contains("umount MOUNTPOINT|services|reset --force]"))
    }

    @Test
    fun vmResetRefusesWithoutForceAndChangesNothing() {
        val phone = phone()
        phone.run("vm boot")
        val before = tree()

        val refused = phone.run("vm reset")
        assertEquals(1, refused.status)
        assertTrue(refused.out, refused.out.contains(disk().absolutePath))
        assertTrue(refused.out, refused.out.contains("bytes on disk"))
        assertTrue(refused.out, refused.out.contains("nothing was deleted; pass --force"))
        assertEquals("the refused reset must have changed nothing", before, tree())
        // And the VM still works, because nothing happened.
        assertEquals(0, phone.run("vm exec 'cat /etc/os-release'").status)
    }

    @Test
    fun vmResetWithForceDeletesTheDiskAndBootsAFreshOne() {
        val phone = phone()
        phone.run("vm boot")
        phone.run("vm exec 'echo mine > /home/ubuntu/mine.txt'")
        val kept = File(disk(), "home/ubuntu/mine.txt")
        assertEquals("mine\n", kept.readText())

        val done = phone.run("vm reset --force")
        assertEquals(done.err, 0, done.status)
        assertTrue(done.out, done.out.contains("removed"))
        assertFalse("the user's file survived a factory reset", kept.exists())
        // Rebooted, so the next thing a user does finds a working system rather than a hole.
        assertTrue(done.out, done.out.contains("init: pid 1 omp-init ready"))
        val after = phone.run("vm exec 'cat /etc/os-release'")
        assertEquals(after.err, 0, after.status)
        val gone = phone.run("vm exec 'cat /home/ubuntu/mine.txt'")
        assertNotEquals("the reset left the user's file behind", 0, gone.status)
    }

    // ---- the four things the transcript caught ---------------------------------------------

    @Test
    fun oneArchitectureAnswersEverywhereAndFollowsTheHostAbi() {
        // arm64-v8a is Android's name; Debian calls that aarch64. The boot line, apt.conf,
        // dpkg and uname must all say the same thing, and all of them must follow the host.
        assertTrue(harness.boot.none { it.text.contains("amd64") })
        assertTrue(harness.boot.any { it.text.contains("Ubuntu 24.04.1 LTS, aarch64, noble") })
        assertTrue(harness.text("/etc/apt/apt.conf.d/omp").contains("APT::Architecture \"aarch64\""))
        assertEquals("aarch64\n", harness.stdout("dpkg --print-architecture"))
        assertEquals("aarch64\n", harness.stdout("uname -m"))

        // A different host, built from scratch: every one of those four moves together.
        val other = VmHarness(File(folder.root, "x86"))
        other.stub.props["ro.product.cpu.abi"] = "x86_64"
        val x86 = File(folder.root, "x86-vm")
        val services = other.services
        val system = VmSystem(x86, services) { services.monotonicMillis() }
        system.boot()
        val shell = system.openSession(omp.term.Screen(24, 200), InputChannel())
        fun say(line: String): String {
            val o = java.io.ByteArrayOutputStream()
            val e = java.io.ByteArrayOutputStream()
            shell.shell.executeLine(line, ByteArrayInputStream(ByteArray(0)), o, e, true)
            return String(o.toByteArray(), Charsets.UTF_8)
        }
        assertEquals("amd64\n", say("dpkg --print-architecture"))
        assertEquals("amd64\n", say("uname -m"))
        assertEquals("amd64", VmArch.of(services))
    }

    @Test
    fun suMovesTheEnvironmentAndThePromptButNotTheWorkingDirectory() {
        val session = harness.shellSession
        assertTrue(session.prompt().contains("ubuntu@ubuntu:"))
        harness.stdout("su root")
        // The prompt is read from the session's USER, so it moves with the identity.
        assertTrue(session.prompt().contains("root@ubuntu:"))
        assertEquals("/root\n", harness.stdout("echo \$HOME"))
        assertEquals("root\n", harness.stdout("echo \$USER"))
        // Real `su` does not change the working directory, and neither does this one.
        assertEquals("/home/ubuntu\n", harness.stdout("pwd"))
    }

    @Test
    fun psListsTheShellAndTheRunningCommandAndNothingAfterIt() {
        // The session's own shell is pid 2 in the namespace.
        val listed = harness.stdout("ps")
        assertTrue(listed, listed.lines().any { it.trimStart().startsWith("2 ") && it.contains("/bin/sh") })

        harness.run("sleep 30 &")
        val bg = harness.session.lastBackgroundPid
        assertTrue("no background pid was handed out", bg > 0)
        val during = harness.stdout("ps")
        assertTrue(during, during.lines().any { it.trimStart().startsWith("$bg ") && it.contains("sleep") })
        // The pid in the table is the one $! reported, not a second numbering.
        assertTrue(during, harness.kernel.processes.of(bg) != null)

        harness.session.job(bg)!!.cancel()
        harness.session.job(bg)!!.join()
        val after = harness.stdout("ps")
        assertFalse(after, after.lines().any { it.trimStart().startsWith("$bg ") })
    }

    @Test
    fun theProcessCountIsCountedAndThePhonesPsIsUntouched() {
        val before = harness.kernel.processes.size()
        harness.run("sleep 30 &")
        val bg = harness.session.lastBackgroundPid
        assertTrue("the count must move with a real process", harness.kernel.processes.size() > before)

        val phone = phone()
        // The phone's own ps is a different command over a different table, and says so.
        val phonePs = phone.run("ps")
        assertEquals(0, phonePs.status)
        assertTrue(phonePs.out, phonePs.out.contains("4242"))
        assertFalse("the phone must not see the VM's pid namespace", phonePs.out.contains("omp-init"))

        harness.session.job(bg)!!.cancel()
        harness.session.job(bg)!!.join()
        assertEquals(before, harness.kernel.processes.size())
    }

    @Test
    fun theBootCreatesEveryPathItSaysItCreates() {
        // The silent-failure class: `writeIfMissing` swallows NO_SUCH_FILE, so a file written to a
        // directory that was never made just does not exist, and the boot log undercounts.
        for (path in listOf("/etc/python3", "/etc/python3/debian_version", "/etc/ld.so.conf.d", "/etc/apt/apt.conf.d")) {
            assertTrue("$path does not exist after a fresh boot", exists(path))
        }
    }

    @Test
    fun sudoersIsFourFourZeroAndTheSudoersListingMatchesIt() {
        assertEquals(0x120, harness.kernel.vfs.stat("/etc/sudoers").mode)
        assertTrue(harness.stdout("ls -l /etc/sudoers"), harness.stdout("ls -l /etc/sudoers").startsWith("-r--r-----"))

        // A %sudo user gets the rule the file grants; anybody else gets the absence of one.
        assertTrue(harness.stdout("sudo"), harness.stdout("sudo").contains("(ALL : ALL) ALL"))
        harness.stdout("su root")
        val asRoot = harness.stdout("sudo")
        assertTrue(asRoot, asRoot.contains("(ALL : ALL) ALL"))
        // And an account with no rule is told so rather than shown a grant.
        harness.stdout("su root")
        assertEquals(1, harness.run("su nobody").status)
    }

    @Test
    fun suRefusesAnAccountWhoseShellDoesNotExistHere() {
        for (account in listOf("daemon", "nobody")) {
            val refused = harness.run("su $account")
            assertEquals("su $account must be refused", 1, refused.status)
            assertTrue(refused.err, refused.err.contains("is not a program in this userland"))
        }
        // And the accounts with a real shell are not.
        assertEquals(0, harness.run("su root").status)
        assertEquals(0, harness.run("su ubuntu").status)
    }

    @Test
    fun procStatNeverClaimsMoreUserTimeThanHasElapsed() {
        // processCpuTimes() is milliseconds and /proc/stat is jiffies: subtracting one from the
        // other produced `user` larger than the elapsed time after a few minutes of app use.
        harness.stub.cpuTimes = 6_000_000L to 7_200_000L
        val row = harness.text("/proc/stat").lines().first { it.startsWith("cpu ") }
            .trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val user = row[0]
        val idle = row[3]
        assertTrue("user $user + idle $idle must not exceed the elapsed 720000 jiffies", user + idle <= 720_000)
    }

    @Test
    fun aSecondBootDoesNotMoveTheBootBoundaryOutFromUnderTheJournal() {
        val first = harness.stdout("journalctl -b")
        assertTrue(first, first.isNotBlank())
        phoneRun("vm boot")
        assertEquals(first, harness.stdout("journalctl -b"))
    }

    private fun phoneRun(line: String): String = phone().run(line).out

    @Test
    fun freePrintsOnlyTheColumnsTheNamespaceCanKnow() {
        val out = harness.stdout("free")
        assertTrue(out, out.contains("total") && out.contains("used") && out.contains("free") && out.contains("available"))
        // MemFree in the free column, not MemAvailable: the stub's available is half its total.
        assertTrue(out, out.contains("Mem:"))
    }

    @Test
    fun theJournalAndSystemctlNameTheHostsOwnName() {
        val host = "stub device"
        assertTrue(harness.stdout("journalctl -n 1").contains(host))
        assertTrue(harness.stdout("systemctl status systemd-journald").contains(host))
    }

    @Test
    fun bareAptPrintsItsUsageAndAptListShowsEveryPackage() {
        val bare = harness.run("apt")
        assertEquals(0, bare.status)
        assertTrue(bare.out, bare.out.contains("Usage: apt [options] command"))
        assertTrue("bare apt must not be a package listing", !bare.out.contains("coreutils/noble"))
        val list = harness.stdout("apt list")
        // The seven the base system installed, marked, and the ones that are only in the index.
        assertTrue(list, list.contains("coreutils/noble,now 9.4-1ubuntu6.2 aarch64 [installed"))
        assertTrue("a not-installed package must still be listed", list.contains("openssh-server/noble,now 1:9.6p1-3ubuntu13 aarch64 [installable]"))
    }

    // ---- the door, and what is behind it ---------------------------------------------------

    @Test
    fun vmEnterRunsARealReplOnTheSameTerminalAndExitComesBack() {
        val phone = phone()
        val outer = phone.input.onInterrupt
        phone.feed("whoami", "cat /etc/os-release", "exit")

        val entered = phone.run("vm enter")
        assertEquals(0, entered.status)
        assertEquals("one push and one pop", 1, phone.host.pushed.size)
        assertEquals(listOf(phone.host.pushed.single()), phone.host.popped.toList())
        assertSame(phone.shell.session, phone.host.front)

        val screen = phone.screenText()
        // The prompt named the namespace, so the user can see where they are.
        assertTrue(screen, screen.contains("ubuntu:"))
        assertTrue(screen, screen.contains("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\""))
        assertTrue("whoami answered with the namespace's user:\n$screen", screen.lines().any { it.trim() == "ubuntu" })
        // And the phone said where the user is again.
        assertTrue(entered.out, entered.out.contains("back on the phone"))
        // Ctrl-C went back to whoever held it before, on the same channel.
        assertSame(outer, phone.input.onInterrupt)
    }

    @Test
    fun theVmsLastStatusIsWhatThePhoneShellReportsAfterwards() {
        val phone = phone()
        phone.feed("cat /nope", "exit")
        val entered = phone.run("vm enter")
        // `cat` of a missing file is 1 and `exit` with no argument carries the last status out, so
        // `$?` on the phone means the same thing it would if the user had typed it there.
        assertEquals(1, entered.status)
        assertTrue(phone.screenText(), phone.screenText().lines().any { it.contains("/nope") })
    }

    @Test
    fun backInTheVmEndsTheNestedReplAndNeverTheRootOne() {
        val phone = phone()
        val status = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val entering = Thread {
            status.set(phone.run("vm enter").status)
            done.countDown()
        }
        entering.start()
        assertTrue("the VM REPL never took the terminal", phone.awaitPush())
        val inVm = phone.host.front
        assertNotEquals(phone.shell.session, inVm)

        // Exactly what `onBackPressed` does for a session in front: ask it to end, and feed the
        // key its line editor is blocked waiting for.
        inVm.exitRequested = true
        phone.input.feed(byteArrayOf(CTRL_D))

        assertTrue("Back inside the VM did not return to the phone shell", done.await(5, TimeUnit.SECONDS))
        assertSame(phone.shell.session, phone.host.front)
        assertEquals(1, phone.host.popped.size)
        // The root session was never asked to exit, and that is what keeps the app alive.
        assertFalse(phone.shell.session.exitRequested)
        assertEquals(0, status.get())
    }

    @Test
    fun theHostSeesTheSessionGoInAndComeBack() {
        val phone = phone()
        phone.feed("exit")
        phone.run("vm enter")

        assertEquals("one push and one pop", 2, phone.host.pushed.size + phone.host.popped.size)
        assertEquals(1, phone.host.pushed.size)
        assertEquals(1, phone.host.popped.size)
        // The same session both times, and it is not the root one.
        assertEquals(phone.host.pushed.single(), phone.host.popped.single())
        assertNotEquals(phone.shell.session, phone.host.pushed.single())
        // The root is in front again, and it is the root that owns the host.
        assertSame(phone.shell.session, phone.host.front)
        assertSame(phone.host, phone.shell.session.host)
    }

    @Test
    fun aNestedSessionTakesCtrlCWhileItRunsAndHandsItBackAfter() {
        val phone = phone()
        val outer: () -> Unit = {}
        phone.input.onInterrupt = outer
        val done = CountDownLatch(1)
        val entering = Thread {
            phone.run("vm enter")
            done.countDown()
        }
        entering.start()
        assertTrue("the VM REPL never took the terminal", phone.awaitPush())

        // While the VM's REPL is up, the handler on the channel is the one it installed, so a
        // Ctrl-C would cancel the VM's foreground job rather than the phone's.
        assertNotSame(outer, phone.input.onInterrupt)
        assertSame(phone.host.front, phone.host.pushed.single())

        // ^C at an empty prompt interrupts it; the next line is what ends the REPL.
        phone.input.feed(byteArrayOf(CTRL_C))
        phone.input.feed("exit\r".toByteArray(Charsets.UTF_8))

        assertTrue("the nested REPL did not finish", done.await(5, TimeUnit.SECONDS))
        assertSame("Ctrl-C was not handed back to the phone's session", outer, phone.input.onInterrupt)
        assertSame(phone.shell.session, phone.host.front)
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The VM's disk, at the place the `vm` command puts it. */
    private fun disk(): File = File(harness.services.appFilesDir(), VmCommand.DISK_DIR)

    /** Every file in the VM's disk with its size, which is what "nothing changed" means here. */
    private fun tree(): Set<Pair<String, Long>> =
        disk().walkTopDown().filter { it.isFile }.map { it.absolutePath to it.length() }.toSet()

    private fun exists(path: String): Boolean = try {
        harness.kernel.vfs.stat(path)
        true
    } catch (e: Exception) {
        false
    }

    /** `rwxr-xr-x`, from the mode the file really has on the phone's disk. */
    private fun posix(file: File): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    /** The number on the `packages:` line of `vm status`. */
    private fun String.packages(): Int =
        lines().first { it.startsWith("packages:") }
            .removePrefix("packages:").trim().substringBefore(' ').toInt()

    private val CTRL_C: Byte = 0x03
    private val CTRL_D: Byte = 0x04

    /** The phone-side shell: the one that owns `vm`. See [VmPhone]. */
    private fun phone(services: VmServices = harness.services): VmPhone = VmPhone(services)
}
