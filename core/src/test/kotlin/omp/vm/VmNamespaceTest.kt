package omp.vm

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
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
 * The namespace itself: what a fresh kernel looks like, what the mount table says, how `/mnt/android`
 * is a real bind, and the three bookkeeping pieces (processes, users, exec) that have to be right
 * before anything else in the VM is worth anything.
 */
class VmNamespaceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun aFreshKernelHasTheRootfsSkeleton() {
        // The phase-3 rootfs is a real one: `lib` is back, and `dev`, `proc` and `sys` are the
        // kernel's own mountpoint directories, which a real `ls /` shows too.
        assertEquals(
            listOf("bin", "boot", "dev", "etc", "home", "lib", "media", "mnt", "opt", "proc", "root",
                "run", "sbin", "srv", "sys", "tmp", "usr", "var"),
            h.names("/"),
        )
        // The same list through a command, because `ls /` is how a user checks this.
        assertEquals(h.names("/").joinToString("\n") + "\n", h.stdout("ls /"))
    }

    @Test
    fun theMountTableIsBuiltInOrderAndSaysWhatEachOneIs() {
        assertEquals(
            // The app's own bind is last: it is made at the end of the boot, after the filesystems
            // the kernel provides, which is the order a real init mounts a user's own things in.
            listOf("/", "/mnt/android", "/proc", "/sys", "/dev", "/run", "/tmp", "/mnt/omp"),
            h.kernel.mountTable().map { it.target },
        )
        val launcher = h.kernel.mountTable().first { it.target == "/mnt/omp" }
        assertEquals(omp.vm.launcher.Containers.hostRootOf(h.services), launcher.source)
        assertTrue(launcher.note!!, launcher.note!!.contains("the app's own bind"))
        val proc = h.kernel.mountTable().first { it.target == "/proc" }
        assertEquals("proc", proc.fstype)
        assertTrue(proc.options.startsWith("ro,"))
        assertNull(proc.note)
    }

    @Test
    fun bootReportsEveryMountAndInit() {
        val lines = h.boot.map { it.text }
        for (target in listOf("/", "/mnt/android", "/proc", "/sys", "/dev", "/run", "/tmp")) {
            assertTrue("no boot line for $target", lines.any { it.contains(" on $target ") })
        }
        assertTrue(lines.any { it.startsWith("init: pid 1 omp-init ready") })
        assertTrue(h.boot.none { it.failed })
    }

    @Test
    fun aFailedMountIsReportedAsAFailure() {
        // A root that cannot exist: a file where a directory has to be.
        File(folder.root, "blocker").writeText("not a directory")
        val services = h.services
        val kernel = VmKernel(File(folder.root, "blocker/child"), services) { services.monotonicMillis() }
        val lines = kernel.boot()
        assertTrue("a mount that could not be set up must be reported", lines.any { it.failed })
        assertTrue(lines.any { it.text.contains("omp-root(broken)") })
    }

    @Test
    fun theAndroidBindMapsThePhonesStorageOneToOne() {
        val external = h.externalDir!!
        File(external, "photo.jpg").writeText("jpeg")
        File(external, "Documents").mkdirs()

        assertEquals(listOf("Documents", "photo.jpg"), h.names("/mnt/android"))

        // Both directions: a write through the Vfs is a file on the phone, and a file the user
        // drops in afterwards shows up in the namespace without a refresh.
        h.kernel.vfs.writeBytes("/mnt/android/from-vm.txt", "written".toByteArray())
        assertEquals("written", File(external, "from-vm.txt").readText())
        File(external, "from-phone.txt").writeText("dropped")
        assertTrue(h.names("/mnt/android").contains("from-phone.txt"))
        assertEquals("dropped", h.text("/mnt/android/from-phone.txt"))
    }

    @Test
    fun withoutTheGrantTheBindIsEmptyAndSaysWhy() {
        val blocked = VmHarness(File(folder.root, "blocked"), granted = false, externalDir = null)
        assertEquals(emptyList<String>(), blocked.names("/mnt/android"))

        val mount = blocked.kernel.mountTable().first { it.target == "/mnt/android" }
        assertNotNull("a mount that is not what it looks like must explain itself", mount.note)
        assertTrue(mount.note!!.contains("no shared storage"))
        assertTrue(blocked.stdout("mount").contains(mount.note!!))
        assertEquals(FsErrno.NO_SUCH_FILE, blocked.errnoOf { blocked.kernel.vfs.readBytes("/mnt/android/anything") })
    }

    @Test
    fun withoutTheGrantButWithStorageTheBindIsStillEmptyAndSaysWhy() {
        val external = File(folder.root, "storage").apply { mkdirs() }
        File(external, "photo.jpg").writeText("jpeg")
        val blocked = VmHarness(File(folder.root, "blocked2"), granted = false, externalDir = external)

        assertEquals(emptyList<String>(), blocked.names("/mnt/android"))
        val mount = blocked.kernel.mountTable().first { it.target == "/mnt/android" }
        assertTrue(mount.note!!.contains("all-files access"))
        assertTrue(blocked.stdout("mount").contains("all-files access"))
    }

    @Test
    fun procAndSysRefuseEveryMutation() {
        for (path in listOf("/proc/meminfo", "/sys/omp/android/uid")) {
            assertEquals("openWrite $path", FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.openWrite(path, false) })
            assertEquals("writeBytes $path", FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.writeBytes(path, ByteArray(1)) })
            assertEquals("createFile $path", FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.createFile(path) })
            assertEquals("mkdir $path", FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.mkdir(path) })
            assertEquals("delete $path", FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.delete(path) })
            assertEquals("setModified $path", FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.setModified(path, 0L) })
        }
        assertEquals(
            FsErrno.READ_ONLY,
            h.errnoOf { h.kernel.vfs.writeBytes("/proc/meminfo", "x".toByteArray()) },
        )
        // And through a command, which is where a user meets it.
        val result = h.run("sh -c 'cat /proc/meminfo > /proc/meminfo'")
        assertTrue(result.err, result.err.contains("Read-only file system"))
    }

    @Test
    fun theRootfsIsWritableAndTheEphemeralMountsAreJustDirectories() {
        h.kernel.vfs.writeBytes("/home/ubuntu/notes.txt", "vm".toByteArray())
        assertEquals("vm", File(folder.root, "vm/home/ubuntu/notes.txt").readText())

        h.kernel.vfs.writeBytes("/tmp/scratch", "x".toByteArray())
        assertTrue(File(folder.root, "vm/tmp/scratch").isFile)
        h.kernel.vfs.delete("/tmp/scratch")
        assertFalse(File(folder.root, "vm/tmp/scratch").exists())
        // A tmpfs wipe is a directory wipe, and nothing here pretends otherwise.
        assertEquals("tmpfs", h.kernel.mountTable().first { it.target == "/tmp" }.fstype)
    }

    @Test
    fun aPathNoMountCoversIsNoSuchFile() {
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.stat("/nowhere/at/all") })
        // Crossing into a mountpoint is fine; the root mount still answers for everything else.
        assertEquals(VNodeType.DIRECTORY, h.kernel.vfs.stat("/").type)
        assertEquals(VNodeType.DIRECTORY, h.kernel.vfs.stat("/proc/").type)
    }

    // ---- the process table ------------------------------------------------------------

    @Test
    fun theProcessTableNeverReusesAPid() {
        val processes = h.kernel.processes
        val first = processes.register(listOf("sleep", "30"))
        val second = processes.register(listOf("ls"))
        val third = processes.register(listOf("cat"))
        assertEquals(listOf(100, 101, 102), listOf(first.pid, second.pid, third.pid))

        processes.release(second.pid)
        processes.release(second.pid)
        processes.release(9999)
        assertEquals(
            // pid 2 is the login shell the namespace session registered, and 1 is init.
            listOf(1, 2, 100, 101, 102),
            processes.snapshot().map { it.pid },
        )
        assertEquals('Z', processes.snapshot().first { it.pid == second.pid }.state)
        assertEquals(103, processes.register(listOf("df")).pid)
    }

    @Test
    fun initIsPidOneAndTheLoginShellIsPidTwo() {
        val processes = h.kernel.processes
        val init = processes.snapshot().first { it.pid == VmProcessTable.INIT_PID }
        assertEquals("omp-init", init.comm)
        assertEquals('S', init.state)
        assertTrue(init.argv.contains("--root=/"))

        val login = h.kernel.startLoginShell()
        assertEquals(VmProcessTable.LOGIN_PID, login.pid)
        assertEquals("sh", login.comm)
        assertEquals(1000, login.uid)
        // Starting it again is the same shell, not a second one.
        assertEquals(login.pid, h.kernel.startLoginShell().pid)
    }

    @Test
    fun shutdownEndsEveryProcess() {
        h.kernel.processes.register(listOf("sleep", "1"))
        h.kernel.startLoginShell()
        assertTrue(h.kernel.isRunning())
        h.kernel.shutdown()
        assertFalse(h.kernel.isRunning())
        assertEquals(0, h.kernel.processes.size())
        // The disk is untouched, and a second boot works.
        assertTrue(File(folder.root, "vm/etc/passwd").isFile)
        h.kernel.boot()
        assertEquals(1, h.kernel.processes.snapshot().size)
    }

    // ---- the exec path ----------------------------------------------------------------

    @Test
    fun aProgramFileIsAProgramAndAnythingElseIsNot() {
        val vfs = h.kernel.vfs
        assertTrue(VmExec.isProgramFile(vfs, "/usr/bin/ls"))
        assertEquals("ls", VmExec.programName(vfs, "/usr/bin/ls"))
        assertNotNull(VmExec.lookup(vfs, h.kernel.commands, "/usr/bin/ls"))

        // A real file that is not a program stays Exec format error, exactly as on the phone.
        vfs.writeBytes("/usr/bin/data", "#!/bin/sh\necho hi\n".toByteArray())
        assertFalse(VmExec.isProgramFile(vfs, "/usr/bin/data"))
        assertNull(VmExec.programName(vfs, "/usr/bin/data"))
        val error = runCatching { VmExec.lookup(vfs, h.kernel.commands, "/usr/bin/data") }.exceptionOrNull()
        assertTrue("a plain file must not resolve to a command", error is FsException)
        val failure = error as FsException
        assertEquals(FsErrno.INVALID_ARGUMENT, failure.errno)
        // The exception's message is the errno, as the Vfs contract says; the phone's wording
        // travels in the path, and it is what the shell prints.
        assertTrue(failure.path!!.contains(VmExec.NOT_A_PROGRAM))

        // A directory, and a file that is not there at all, are both "not a program".
        assertFalse(VmExec.isProgramFile(vfs, "/usr/bin"))
        assertFalse(VmExec.isProgramFile(vfs, "/usr/bin/absent"))
    }

    @Test
    fun theShellRunsAProgramFileAndRefusesAPlainFile() {
        h.kernel.vfs.writeBytes("/usr/bin/greet", "${VmExec.SHEBANG}echo\n".toByteArray())
        assertEquals("hello\n", h.stdout("sh -c '/usr/bin/greet hello'"))

        val refused = h.run("sh -c '/etc/passwd'")
        assertEquals(126, refused.status)
        assertTrue(refused.err, refused.err.contains("Exec format error"))
    }

    // ---- users ------------------------------------------------------------------------

    @Test
    fun usersAreReadThroughTheVfsAndCanSwitch() {
        val users = h.kernel.users
        assertEquals("ubuntu", users.currentName())
        assertEquals(1000, users.uid())
        // A real passwd file: the system accounts a package script expects, and ubuntu last.
        val names = users.passwd().map { it.name }
        assertEquals(listOf("root", "daemon", "bin", "sys", "nobody", "ubuntu"), names.filter { it in setOf("root", "daemon", "bin", "sys", "nobody", "ubuntu") })
        assertEquals(17, names.size)
        assertEquals(0, users.byName("root")!!.uid)
        assertTrue(users.group().any { it.name == "sudo" && it.members.contains("ubuntu") })

        assertEquals(0, users.switchTo("root").uid)
        assertEquals("root", users.currentName())
        // `nobody` is in the real passwd file now, so the refusal needs a name that really is not.
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { users.switchTo("not-a-user") })
        // A refused switch does not change who we are.
        assertEquals("root", users.currentName())
        assertEquals("ubuntu", users.backToDefault().name)

        // The file is parsed through the Vfs, so replacing it changes the answer.
        h.kernel.vfs.writeBytes(
            "/etc/passwd",
            "carol:x:1500:1500:Carol:/home/carol:/usr/bin/sh\n".toByteArray(),
        )
        assertEquals(listOf("carol"), users.passwd().map { it.name })
        assertEquals(1500, users.switchTo("carol").uid)
    }

    @Test
    fun theNamespaceHasTheProgramsAndTheEtcFilesBootWrote() {
        assertTrue(File(folder.root, "vm/usr/bin/ls").isFile)
        assertTrue(h.text("/etc/os-release").contains("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\""))
        assertEquals("stub device", h.text("/etc/hostname").trim())
        // And through the shell, which is the only way most of this will ever be reached.
        assertEquals("stub device\n", h.stdout("cat /etc/hostname"))
        assertEquals("stub device\n", h.stdout("hostname"))
    }
}
