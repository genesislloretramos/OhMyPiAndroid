package omp.vm

import omp.shell.fs.FsErrno
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
 * `vm mount`, end to end and through nothing but the shell.
 *
 * Every assertion here is something a person could type. The binds are made by the phone-side `vm`
 * command, read from inside the namespace with `vm exec`, and every fact is checked against the real
 * files on the device underneath: a file written through a bind *is* a file in the host directory,
 * and a bind that is not there says so in `mount` rather than leaving an empty directory where the
 * files were.
 *
 * [newPhone] is the app restarting — a new [omp.shell.exec.CommandTable] with a new `vm` command in
 * it, so nothing is cached and the disk underneath is the only thing that carried over. That is the
 * only honest way to test persistence here, because what has to survive is a file on disk and a
 * fresh process reading it.
 *
 * The honesty half matters as much as the feature, so the refusals are checked for the sentence
 * that explains them: what this app can bind is its own storage plus, with the grant, shared
 * storage, and a bind cannot reach one file more than the app itself can.
 */
class VmBindTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    // ---- the bind itself -----------------------------------------------------------------

    @Test
    fun aBindIsTheHostDirectoryItselfAndNotACopyOfIt() {
        val host = dir("Documents")
        File(host, "before.txt").writeText("on the phone\n")

        val phone = newPhone()
        val mounted = phone.run("vm mount ${host.path}")
        assertEquals(mounted.err, 0, mounted.status)
        assertTrue(mounted.out, mounted.out.contains("${host.path} on /mnt/Documents: mounted read-write"))

        // The default mount point is the directory's own name under /mnt.
        assertEquals("before.txt\n", phone.run("vm exec 'ls /mnt/Documents'").out)

        // A file written in the namespace is a file in the host directory, not a copy of one.
        val write = phone.run("vm exec 'echo written-in-the-vm > /mnt/Documents/after.txt'")
        assertEquals(write.err, 0, write.status)
        assertEquals("written-in-the-vm\n", File(host, "after.txt").readText())

        // And the phone's own namespace reads the same bytes: one directory, two names.
        assertEquals("written-in-the-vm\n", phone.run("cat ${host.path}/after.txt").out)
        assertEquals("on the phone\n", phone.run("vm exec 'cat /mnt/Documents/before.txt'").out)
    }

    @Test
    fun aBindAtAChosenMountPointLandsThereInstead() {
        val host = dir("Pictures")
        File(host, "one.jpg").writeText("jpeg")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/pics").status)
        assertEquals("one.jpg\n", phone.run("vm exec 'ls /mnt/pics'").out)
    }

    @Test
    fun aReadOnlyBindRefusesAWriteFromInsideWithTheRealErrnoAndChangesNothing() {
        val host = dir("ReadOnly")
        File(host, "keep.txt").writeText("untouched\n")
        val phone = newPhone()
        val mounted = phone.run("vm mount ${host.path} /mnt/ro --read-only")
        assertEquals(mounted.err, 0, mounted.status)
        assertTrue(mounted.out, mounted.out.contains("mounted read-only"))

        // Reading is what a read-only bind is for.
        assertEquals("untouched\n", phone.run("vm exec 'cat /mnt/ro/keep.txt'").out)

        // Writing is refused at the seam, with the errno a real read-only mount gives — not by a
        // check inside a command, so a program in the namespace is refused the same way.
        val write = phone.run("vm exec 'echo nope > /mnt/ro/new.txt'")
        assertTrue(write.err, write.err.contains(FsErrno.READ_ONLY.text))
        assertFalse("the host directory was written through a read-only bind", File(host, "new.txt").exists())

        // The options say so, on both sides of the door.
        val table = phone.run("vm mounts").out
        assertTrue(table, table.contains(" on /mnt/ro "))
        assertTrue(table, table.contains("(ro,nosuid,nodev,noexec,relatime)"))
        val inside = phone.run("vm exec 'mount'").out
        assertTrue(inside, inside.contains(" on /mnt/ro "))
        assertTrue(inside, inside.contains("(ro,nosuid"))
        assertTrue(inside, inside.contains("bound read-only"))
    }

    @Test
    fun aHostPathWithASpaceInItIsRecordedAsOneFieldAndComesBackIntact() {
        // `/storage/emulated/0/My Documents` is an ordinary Android directory, and an unescaped
        // space in an fstab line would split the device from the mount point.
        val host = dir("My Documents")
        File(host, "note.txt").writeText("spaces\n")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount '${host.path}' /mnt/docs").status)
        assertTrue(phone.run("vm exec 'cat /etc/fstab'").out.contains("\\040"))
        assertEquals("spaces\n", phone.run("vm exec 'cat /mnt/docs/note.txt'").out)

        val restarted = newPhone()
        restarted.run("vm boot")
        assertTrue(restarted.run("vm mounts").out.contains(" on /mnt/docs "))
        assertEquals("spaces\n", restarted.run("vm exec 'cat /mnt/docs/note.txt'").out)
    }

    // ---- what survives a restart ---------------------------------------------------------

    @Test
    fun aBindComesBackAfterTheAppRestarts() {
        val host = dir("Projects")
        File(host, "app.txt").writeText("kept\n")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/projects").status)

        // A new process, a new kernel, the same disk.
        val restarted = newPhone()
        val boot = restarted.run("vm boot")
        assertEquals(boot.err, 0, boot.status)
        val mounted = restarted.run("vm mounts")
        assertEquals(mounted.err, 0, mounted.status)
        val line = mounted.out.lines().first { it.contains(" on /mnt/projects ") }
        assertTrue(line, line.startsWith("${host.path} on /mnt/projects type "))
        assertEquals("kept\n", restarted.run("vm exec 'cat /mnt/projects/app.txt'").out)

        // The fstab line is still the user's own, in real bind form.
        val fstab = restarted.run("vm exec 'cat /etc/fstab'").out
        assertTrue(fstab, fstab.contains("${host.path} /mnt/projects none bind 0 0"))
        // And the generated lines around it were not rewritten over it.
        assertTrue(fstab, fstab.contains("omp-root / ext4"))
    }

    @Test
    fun twoBindsBothSurviveTheRestartInTheOrderFstabRecordsThem() {
        val first = dir("one")
        val second = dir("two")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${first.path} /mnt/one").status)
        assertEquals(0, phone.run("vm mount ${second.path} /mnt/two").status)

        val boot = newPhone().run("vm boot")
        assertEquals(boot.err, 0, boot.status)
        val binds = boot.out.lines()
            .filter { it.startsWith("mount: ") && (it.contains(first.path) || it.contains(second.path)) }
            .map { it.substringBefore(" type ") }
        assertEquals(
            "the binds are mounted in fstab order, after the built-in mounts",
            listOf("mount: ${first.path} on /mnt/one", "mount: ${second.path} on /mnt/two"),
            binds,
        )
    }

    @Test
    fun aBindWhoseHostDirectoryIsGoneComesBackAbsentWithAReasonRatherThanAFailedBoot() {
        val host = dir("Vanishing")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/gone").status)

        // The user deletes the directory with a file manager, then the app restarts.
        assertTrue(host.deleteRecursively())
        val restarted = newPhone()
        val boot = restarted.run("vm boot")
        assertEquals("the boot must not have stopped", 0, boot.status)
        assertTrue(boot.out, boot.out.contains("init: pid 1 omp-init ready"))
        val failed = boot.err.lines().filter { it.startsWith("bind: ") }
        assertEquals(boot.err, 1, failed.size)
        assertTrue(failed.single(), failed.single().contains(FsErrno.NO_SUCH_FILE.text))
        assertTrue(failed.single(), failed.single().contains(host.path))

        // Nothing is mounted there, and `mount` says why rather than showing an empty directory as
        // if it were the bind.
        val shown = restarted.run("vm exec 'mount'").out
        assertTrue(shown, shown.contains("${host.path} on /mnt/gone: not mounted"))
        assertTrue(shown, shown.contains(FsErrno.NO_SUCH_FILE.text))
        assertTrue(shown.lines().none { it.startsWith("${host.path} on /mnt/gone type ") })

        // The line is still in the fstab, so a directory that comes back is mounted again.
        assertTrue(restarted.run("vm exec 'cat /etc/fstab'").out.contains("${host.path} /mnt/gone none bind 0 0"))
        assertTrue(host.mkdirs())
        File(host, "back.txt").writeText("here again\n")
        val again = newPhone()
        again.run("vm boot")
        assertTrue(again.run("vm mounts").out.contains(" on /mnt/gone "))
        assertEquals("here again\n", again.run("vm exec 'cat /mnt/gone/back.txt'").out)
    }

    // ---- the refusals ---------------------------------------------------------------------

    @Test
    fun theRefusalsNameWhatTheBindCollidedWith() {
        val host = dir("Anything")
        val phone = newPhone()

        val root = phone.run("vm mount ${host.path} /")
        assertEquals(1, root.status)
        assertEquals(
            "vm mount: /: the root of the namespace is always mounted; choose a path under it\n",
            root.err,
        )

        for (point in listOf("/proc", "/sys", "/dev")) {
            val reserved = phone.run("vm mount ${host.path} $point")
            assertEquals(reserved.err, 1, reserved.status)
            assertEquals(
                "vm mount: $point: it is a mount of the VM kernel's own, and a bind cannot be mounted over it\n",
                reserved.err,
            )
        }

        // A mount point that is somebody's else: the platform's own shared-storage bind.
        val android = phone.run("vm mount ${host.path} /mnt/android")
        assertEquals(1, android.status)
        assertTrue(android.err, android.err.contains("vm mount: /mnt/android: it is already a mount point (shared-storage)"))

        // And one of the user's own, refused by the path it is showing.
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/mine").status)
        val mine = phone.run("vm mount ${dir("Other").path} /mnt/mine")
        assertEquals(1, mine.status)
        assertTrue(mine.err, mine.err.contains("vm mount: /mnt/mine: it is already a mount point (${host.path})"))

        // Not one refusal changed anything.
        val table = phone.run("vm mounts").out
        for (point in listOf("/", "/proc", "/sys", "/dev", "/mnt/android")) {
            assertTrue(table, table.contains(" on $point "))
        }
    }

    @Test
    fun theLaunchersOwnMountPointIsReservedSoATypedMountCannotBuryIt() {
        val host = dir("Omp")
        val phone = newPhone()
        val refused = phone.run("vm mount ${host.path} ${VmKernel.LAUNCHER_MOUNT}")
        assertEquals(1, refused.status)
        assertEquals(
            "vm mount: ${VmKernel.LAUNCHER_MOUNT}: it is reserved for the app's project launcher, " +
                "which binds shared storage there; a mount you type cannot be put on top of it\n",
            refused.err,
        )
        // What is mounted there is the app's own container, made by the boot: the mount a user
        // typed did not reach it, and a bind that had been recorded as theirs would be in the fstab.
        val shown = phone.run("vm mounts").out
        assertTrue(shown, shown.contains(" on ${VmKernel.LAUNCHER_MOUNT} "))
        assertTrue(shown, shown.contains("the app's own bind for its conversations"))
        assertFalse(phone.run("vm exec 'cat /etc/fstab'").out.contains(VmKernel.LAUNCHER_MOUNT))
        assertTrue(phone.run("vm exec 'ls /mnt'").out.contains("omp"))
    }

    @Test
    fun aHostPathThatIsNotThereIsRefusedWithTheErrnoAndNotBoundAsAnEmptyDirectory() {
        val phone = newPhone()
        val missing = File(folder.root, "does-not-exist-4711")
        val refused = phone.run("vm mount ${missing.path}")
        assertEquals(1, refused.status)
        assertEquals("vm mount: ${missing.path}: ${FsErrno.NO_SUCH_FILE.text}\n", refused.err)

        // Nothing was created, nothing was mounted, and nothing was recorded — which is what lets a
        // launcher create the directory first and bind it second.
        assertFalse(missing.exists())
        assertFalse(phone.run("vm mounts").out.contains(" on /mnt/does-not-exist-4711 "))
        assertFalse(phone.run("vm exec 'ls /mnt'").out.contains("does-not-exist"))
        assertFalse(phone.run("vm exec 'cat /etc/fstab'").out.contains(missing.path))
    }

    @Test
    fun aPathThisAppCannotReachIsRefusedWithTheReasonAndNoEmptyDirectoryIsBound() {
        val phone = newPhone()
        // A real directory on the machine running the tests, outside the app's own storage: the
        // stand-in for `/system`, which no app may read.
        val unreachable = File("/etc")
        assertTrue("/etc exists on every platform this suite runs on", unreachable.isDirectory)
        val refused = phone.run("vm mount $unreachable")
        assertEquals(1, refused.status)
        assertEquals(
            "vm mount: this app cannot read $unreachable: Android does not grant it, and a bind " +
                "cannot grant what the app does not have\n",
            refused.err,
        )
        // Refused means refused: no mount, no line in the fstab, no directory.
        assertFalse(phone.run("vm mounts").out.contains(" on /mnt/etc "))
        assertFalse(phone.run("vm exec 'cat /etc/fstab'").out.contains(unreachable.path))
        assertFalse(phone.run("vm exec 'ls /mnt'").out.contains("etc"))
    }

    @Test
    fun aFileIsNotSomethingABindCanBePointedAt() {
        val file = File(folder.root, "a-file.txt").apply { writeText("not a directory") }
        val phone = newPhone()
        val refused = phone.run("vm mount ${file.path}")
        assertEquals(1, refused.status)
        assertEquals("vm mount: ${file.path}: ${FsErrno.NOT_A_DIRECTORY.text}\n", refused.err)
    }

    // ---- what the platform allows ---------------------------------------------------------

    @Test
    fun theAppsOwnStorageIsBindableWithNoGrantAtAll() {
        val ungranted = VmHarness(folder.root, granted = false)
        val phone = VmPhone(ungranted.services)
        // `appFilesDir()` and `homeDir()` are the app's own: writable, and no permission to ask for.
        val home = File(ungranted.services.homeDir())
        assertEquals(0, phone.run("vm mount ${home.path} /mnt/app-home").status)
        assertEquals(0, phone.run("vm exec 'echo hi > /mnt/app-home/inside.txt'").status)
        assertEquals("hi\n", File(home, "inside.txt").readText())
        // Read back through the phone's own namespace: the same file, the same bytes.
        assertEquals("hi\n", phone.run("cat ${home.path}/inside.txt").out)

        // The VM's own disk is inside that storage and is bindable for the same reason.
        val disk = File(ungranted.services.appFilesDir(), VmCommand.DISK_DIR)
        assertTrue(disk.isDirectory)
        assertEquals(0, phone.run("vm mount ${disk.path} /mnt/rootfs").status)
        assertTrue(phone.run("vm exec 'ls /mnt/rootfs'").out.contains("etc"))
    }

    @Test
    fun withoutTheGrantASharedStorageBindIsRefusedWithTheGrantMessage() {
        val ungranted = VmHarness(folder.root, granted = false)
        val phone = VmPhone(ungranted.services)
        val shared = File(folder.root, "external").apply { mkdirs() }
        val refused = phone.run("vm mount ${shared.path} /mnt/shared")
        assertEquals(1, refused.status)
        assertTrue(refused.err, refused.err.contains("this app cannot read ${shared.path}"))
        assertTrue(refused.err, refused.err.contains("All files access"))
        assertTrue(refused.err, refused.err.contains("grant-storage"))
        assertTrue(refused.err, refused.err.contains("a bind cannot grant what the app does not have"))
        // Nothing was bound, and the mount point was not even created for it.
        assertFalse(phone.run("vm mounts").out.contains(" on /mnt/shared "))
        assertFalse(phone.run("vm exec 'cat /etc/fstab'").out.contains(shared.path))
    }

    @Test
    fun withTheGrantASharedStorageBindWorksAndIsTheSameDirectoryThePhoneSees() {
        val phone = newPhone()
        // A shared-storage directory of its own, rather than `Documents`, which holds the
        // conversations container the launcher makes and this suite is not testing here.
        val shared = dir("external/Shared")
        File(shared, "plan.txt").writeText("shared\n")
        val mounted = phone.run("vm mount ${shared.path}")
        assertEquals(0, mounted.status)
        // The default mount point is the directory's own name.
        assertEquals("plan.txt\n", phone.run("vm exec 'ls /mnt/Shared'").out)
        assertEquals(0, phone.run("vm exec 'echo shared-back > /mnt/Shared/reply.txt'").status)
        assertEquals("shared-back\n", File(shared, "reply.txt").readText())
    }

    // ---- unmounting -----------------------------------------------------------------------

    @Test
    fun umountOfAMountOfTheKernelsOwnIsRefusedByName() {
        val phone = newPhone()
        for (point in listOf("/proc", "/sys", "/dev", "/run", "/tmp", "/mnt/android", "/")) {
            val refused = phone.run("vm umount $point")
            assertEquals(refused.err, 1, refused.status)
            assertTrue(refused.err, refused.err.contains("vm umount: $point is a mount of the VM kernel's own"))
        }
        // Nothing was taken away: the table is as it was, and the generated filesystems still answer.
        val table = phone.run("vm mounts").out
        for (point in listOf("/proc", "/sys", "/dev", "/run", "/tmp", "/mnt/android")) {
            assertTrue(table, table.contains(" on $point "))
        }
        assertTrue(phone.run("vm exec 'cat /proc/version'").out.contains("in-process userspace VM"))
    }

    @Test
    fun umountTakesTheBindOutOfTheTableAndOutOfTheFstabForGood() {
        val host = dir("Projects")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/projects").status)

        val done = phone.run("vm umount /mnt/projects")
        assertEquals(done.err, 0, done.status)
        assertTrue(done.out, done.out.contains("/mnt/projects is no longer mounted"))
        assertTrue(done.out, done.out.contains("it no longer showed ${host.path}"))

        assertFalse(phone.run("vm mounts").out, phone.run("vm mounts").out.contains(" on /mnt/projects "))
        assertFalse(phone.run("vm exec 'mount'").out, phone.run("vm exec 'mount'").out.contains(" on /mnt/projects "))
        val fstab = phone.run("vm exec 'cat /etc/fstab'").out
        assertFalse(fstab, fstab.contains("/mnt/projects"))
        // The generated lines are still there: an umount edits the user's line out, it does not
        // rewrite the file.
        assertTrue(fstab, fstab.contains("omp-root / ext4"))

        // And a boot really does not bring it back.
        val boot = newPhone().run("vm boot")
        assertEquals(boot.err, 0, boot.status)
        assertFalse(boot.out + boot.err, (boot.out + boot.err).contains("/mnt/projects"))
    }

    @Test
    fun umountOfSomethingThatIsNotMountedSaysSo() {
        val phone = newPhone()
        val refused = phone.run("vm umount /mnt/never")
        assertEquals(1, refused.status)
        assertEquals("vm umount: /mnt/never is not mounted\n", refused.err)
    }

    // ---- what mount, fstab and df show -----------------------------------------------------

    @Test
    fun mountAndFstabShowTheBindWithItsDeviceTypeAndOptions() {
        val host = dir("Documents")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/docs --read-only").status)

        val shown = phone.run("vm exec 'mount'").out
        val line = shown.lines().first { it.contains(" on /mnt/docs ") }
        assertTrue(line, line.startsWith("${host.path} on /mnt/docs type "))
        assertTrue(line, line.contains("(ro,"))
        assertTrue(shown, shown.contains("bound read-only"))

        val fstab = phone.run("vm exec 'cat /etc/fstab'").out
        assertTrue(fstab, fstab.contains("${host.path} /mnt/docs none bind,ro 0 0"))
        // The table the VM has is the table `/proc/mounts` prints.
        assertTrue(phone.run("vm exec 'cat /proc/mounts'").out.contains("${host.path} /mnt/docs"))
    }

    @Test
    fun dfReportsTheBindsOwnRealFreeSpaceRatherThanACopyOfTheRootfsNumbers() {
        val host = dir("Big")
        val phone = newPhone()
        assertEquals(0, phone.run("vm mount ${host.path} /mnt/big").status)

        val before = availBytes(phone.run("vm exec 'df'").out, "/mnt/big")
        assertNotNull("df has no row for the bind:\n${phone.run("vm exec 'df'").out}", before)

        // Writing into the host directory moves the bind's own row: the number is about the
        // directory that was bound, read through its own RealVfs, and not `/`'s copied across.
        File(host, "eight-mib.bin").writeBytes(ByteArray(8 * 1024 * 1024) { 1 })
        val after = availBytes(phone.run("vm exec 'df'").out, "/mnt/big")
        assertNotNull(after)
        assertTrue("df did not move: $before -> $after", before!! - after!! >= 4L * 1024 * 1024)
    }

    @Test
    fun vmUsageRefusesAMalformedMountAndNamesTheFormItTakes() {
        val host = dir("Anything")
        val phone = newPhone()
        assertEquals(2, phone.run("vm mount").status)
        assertTrue(phone.run("vm mount").err.contains("usage: vm mount HOSTPATH [MOUNTPOINT]"))
        assertEquals(2, phone.run("vm mount ${host.path} /a /b").status)
        assertEquals(2, phone.run("vm mount --nonsense ${host.path}").status)
        assertTrue(phone.run("vm mount --nonsense ${host.path}").err.contains("unknown option '--nonsense'"))
    }

    // ---- helpers --------------------------------------------------------------------------

    /** A directory in the app's own storage, which is the tree a bind is allowed to reach. */
    private fun dir(name: String): File = File(folder.root, name).apply { mkdirs() }

    /**
     * The app starting up: a table with a new `vm` command in it, so nothing is cached and the
     * only thing carried over from the last run is the disk.
     */
    private fun newPhone(): VmPhone {
        val table = omp.shell.exec.CommandTable.global.copy()
        table.register(VmCommand())
        return VmPhone(h.services, table)
    }

    /** The Avail column of [point]'s row in `df` output, in bytes. */
    private fun availBytes(df: String, point: String): Long? =
        df.lines()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 6 && it.last() == point }
            ?.get(3)
            ?.toLongOrNull()
}
