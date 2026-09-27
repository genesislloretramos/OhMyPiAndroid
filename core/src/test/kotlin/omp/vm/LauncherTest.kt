package omp.vm

import omp.shell.Session
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.VEntry
import omp.shell.fs.Vfs
import omp.term.Screen
import omp.vm.launcher.Containers
import omp.vm.launcher.OmpCommand
import omp.vm.workspace.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Vfs] whose directory listings are refused. Android hands an app exactly this for another
 * app's files, and a suite that runs as root cannot reproduce it with `chmod` — so the counting
 * code is given the errno itself rather than a hope that permissions are being enforced.
 */
private class DenyingReadDir(private val inner: Vfs) : Vfs by inner {
    override fun readDir(path: String): List<VEntry> = throw FsException(FsErrno.PERM_DENIED, path)
}

/**
 * `omp`, driven the way a user drives it, in both namespaces at once.
 *
 * Every assertion here is something a person could type. Commands go through a real
 * [omp.shell.exec.Shell] over a real [omp.shell.ShellSession], the answer at the prompt comes off
 * the same [omp.shell.InputChannel] the line editor reads, and every fact about a conversation is
 * checked with [java.io.File] against the directory that is really on the temp filesystem — because
 * the promise this feature makes is not "the command prints a table", it is "the bytes are in a
 * folder the user can open in a file manager". A test that only went through the
 * [omp.shell.fs.Vfs] could not tell a bind from a copy.
 *
 * [phone] is the shell the user is in before `vm enter` and [harness] is the session inside the
 * namespace, and the tests that matter most use both: the whole feature is that a folder written
 * through one is the same folder the other one sees.
 */
class LauncherTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The phone: the session that owns `omp` and `vm`, over the device's own filesystem. */
    private lateinit var phone: VmPhone

    /** A session *inside* the namespace, over the VM's mount table. */
    private lateinit var harness: VmHarness

    /** What `/storage/emulated/0/Documents/omp` is on this machine. */
    private lateinit var container: File

    @Before
    fun setUp() {
        harness = VmHarness(folder.root)
        phone = VmPhone(harness.services)
        container = File(harness.externalDir!!, "Documents/omp")
    }

    // ---- the list, before and after ---------------------------------------------------------

    @Test
    fun ompLsOnAColdAppListsNothingAndSaysWhyAndChangesNothing() {
        // The container is made by the VM's boot, which is what a `mount` in the namespace needs;
        // deleting it here is the "nothing exists yet" a user is really in after wiping Documents.
        assertTrue(container.deleteRecursively())
        val cold = phone.run("omp ls")
        assertEquals(cold.err, 0, cold.status)
        assertTrue(cold.out, cold.out.contains("no conversations"))
        assertTrue(cold.out, cold.out.contains(container.path))
        // "It changes nothing" is the whole claim of this one: no listing may make a folder.
        assertFalse("${container.path} was created by a listing", container.exists())

        phone.run("omp new photos")
        val warm = phone.run("omp ls")
        assertEquals(warm.err, 0, warm.status)
        assertTrue(warm.out, warm.out.contains("photos"))
        // The row names the directory and where it really is, which is what a user needs to open it.
        assertTrue(warm.out, warm.out.contains(File(container, "photos").path))
    }

    @Test
    fun aNewConversationIsARealFolderWithItsMetadataAndTheSessionIsPutInIt() {
        val made = phone.run("omp new photos")
        assertEquals(made.err, 0, made.status)
        assertTrue(made.out, made.out.contains("OMP_WORKSPACE="))
        assertTrue(made.out, made.out.contains("OMP_WORKSPACE_REAL="))

        // A directory the user's file manager can open, with the one file this app writes in it.
        val onDisk = File(container, "photos")
        assertTrue(onDisk.path, onDisk.isDirectory)
        assertEquals(listOf(Workspace.METADATA), onDisk.list()!!.sorted().toList())

        val meta = File(onDisk, Workspace.METADATA).readText()
        assertTrue(meta, meta.contains("version=${Workspace.FORMAT_VERSION}"))
        assertTrue(meta, meta.contains("name=photos"))
        assertTrue(meta, meta.contains("path=${onDisk.path}"))
        assertTrue(meta, meta.contains("host=${onDisk.path}"))
        // The name the user typed is the title; a conversation made without one has none.
        assertTrue(meta, meta.contains("title=photos"))

        // The session is in it, by its namespace path, and both names are in the environment.
        assertEquals(onDisk.path, phone.shell.session.cwd)
        assertEquals(onDisk.path, phone.shell.session.env["OMP_WORKSPACE"])
        assertEquals(onDisk.path, phone.shell.session.env["OMP_WORKSPACE_REAL"])
        assertEquals("${onDisk.path}\n", phone.run("pwd").out)
        assertEquals("${onDisk.path}\n", phone.run("echo \$OMP_WORKSPACE_REAL").out)
    }

    @Test
    fun aNameAlreadyInUseGetsTheSuffixAndBothFoldersAreReallyThere() {
        assertEquals(0, phone.run("omp new photos").status)
        val again = phone.run("omp new photos")
        assertEquals(again.err, 0, again.status)
        assertTrue(again.out, again.out.contains("photos-2"))
        // Both are real, and the first was not emptied to make room for the second.
        assertTrue(File(container, "photos").isDirectory)
        assertTrue(File(container, "photos-2").isDirectory)
        assertEquals("photos", File(File(container, "photos"), Workspace.METADATA).readText().lines()
            .first { it.startsWith("name=") }.removePrefix("name="))
        // And the title kept what the user asked for, which the name on disk no longer says.
        assertTrue(File(File(container, "photos-2"), Workspace.METADATA).readText().contains("title=photos"))
    }

    @Test
    fun aNameThatIsNotThereIsRefusedWithTheErrnoAndTheListExactlyOnce() {
        phone.run("omp new photos")
        val missing = phone.run("omp photos-9")
        assertEquals(1, missing.status)
        assertEquals("", missing.out)
        assertTrue(missing.err, missing.err.contains("No such file or directory"))
        assertTrue(missing.err, missing.err.contains(File(container, "photos-9").path))

        // The list is printed once, and it is the same list `omp ls` prints.
        val listed = phone.run("omp ls").out
        for (line in listed.trim().lines()) {
            assertTrue("printed more than once: $line", missing.err.lines().count { it == line } == 1)
        }
        // A folder the user deleted under the app is the same case, and says the same thing.
        assertTrue(File(container, "photos").deleteRecursively())
        val deleted = phone.run("omp photos")
        assertEquals(1, deleted.status)
        assertTrue(deleted.err, deleted.err.contains("No such file or directory"))
    }

    // ---- the prompt ------------------------------------------------------------------------

    @Test
    fun thePromptAnswersNWithAFreshFolderAndEntersIt() {
        phone.run("omp new notes")
        phone.feed("n")
        val asked = phone.runInteractive("omp")
        assertEquals(asked.err, 0, asked.status)
        val names = container.list()!!.sorted()
        assertEquals("a generated name and the one before it", 2, names.size)
        val generated = names.single { it != "notes" }
        assertTrue(generated, generated.matches(Regex("session-\\d{8}-\\d{4}")))
        assertEquals(File(container, generated).path, phone.shell.session.cwd)
    }


    @Test
    fun thePromptOpensTheNumberedConversation() {
        phone.run("omp new notes")
        phone.run("cd \$HOME")

        phone.feed("1")
        val asked = phone.runInteractive("omp")
        assertEquals(asked.err, 0, asked.status)
        assertTrue(asked.out, asked.out.contains("type a number to continue, or n for a new one"))
        assertEquals(File(container, "notes").path, phone.shell.session.cwd)
        assertEquals(File(container, "notes").path, phone.shell.session.env["OMP_WORKSPACE_REAL"])
    }

    @Test
    fun aPipedOmpPrintsTheTableAndTheTwoOptionsAndReturns() {
        phone.run("omp new photos")
        val before = phone.shell.session.cwd
        val piped = phone.runPiped("omp")
        assertEquals(piped.err, 0, piped.status)
        assertTrue(piped.out, piped.out.contains("photos"))
        assertTrue(piped.out, piped.out.contains("stdin is not the terminal this command owns"))
        assertTrue(piped.out, piped.out.contains("a number from the list above, or n for a new one"))
        // Nothing was asked and nothing was opened, so the session is where it was.
        assertFalse(piped.out, piped.out.contains("created and in"))
        assertEquals(before, phone.shell.session.cwd)

        // `vm exec` is the other case: a throwaway session with a channel nothing will ever write
        // to, and it must not wait for a keypress either.
        val execed = phone.run("vm exec 'omp'")
        assertEquals(execed.err, 0, execed.status)
        assertTrue(execed.out, execed.out.contains("stdin is not the terminal this command owns"))
        assertTrue(execed.out, execed.out.contains("a number from the list above, or n for a new one"))

        // A pipeline is a pipe on stdin and a pipe on stdout, which is the same answer.
        val piped2 = phone.run("omp | cat")
        assertEquals(piped2.err, 0, piped2.status)
        assertTrue(piped2.out, piped2.out.contains("photos"))
        assertTrue(piped2.out, piped2.out.contains("stdin is not the terminal this command owns"))
    }

    @Test
    fun aBackgroundOmpDoesNotTakeTheTerminalAndDoesNotBlock() {
        phone.run("omp new photos")
        val before = phone.shell.session.cwd
        // The channel and `isTty` are the terminal's here, so this is the one case where asking
        // would look right and be wrong: the REPL is back at its prompt before the job has printed
        // a line, and a question asked there would eat the keystrokes the line editor expects.
        // The key is fed first, so a job that asked would swallow it.
        phone.input.feed("1\r".toByteArray(Charsets.UTF_8))
        // The job writes to a file of its own, because a background stage's output does not come
        // back through the shell that started it — and that file is what a user would `cat`.
        val printed = File(folder.root, "background.txt")
        val done = CountDownLatch(1)
        val thread = Thread {
            phone.runInteractive("omp > ${printed.path} &")
            done.countDown()
        }
        thread.start()
        assertTrue("a background omp blocked on the prompt", done.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (phone.shell.session.jobs().isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        val said = printed.readText()
        assertTrue(said, said.contains("stdin is not the terminal this command owns"))
        assertTrue(said, said.contains("a number from the list above, or n for a new one"))
        assertFalse(said, said.contains("created and in"))
        // The keystroke is still in the channel, for the line editor rather than for a job.
        assertEquals('1'.code, phone.shell.session.input!!.pollByte(500))
        assertEquals(before, phone.shell.session.cwd)
        assertEquals(1, container.list()!!.size)
    }

    @Test
    fun ctrlCAtThePromptCancelsItAndLeavesNoFolderAndNoSessionState() {
        phone.run("cd \$HOME")
        phone.input.feed(byteArrayOf(0x03))
        val cancelled = phone.runInteractive("omp")
        assertEquals(cancelled.err, 130, cancelled.status)
        assertTrue(cancelled.err, cancelled.err.contains("the prompt was cancelled"))

        // No half-created folder: the answer is read before anything is made.
        assertEquals("a cancelled prompt creates no folder at all", 0, container.list()!!.size)
        assertEquals(harness.services.initialDirectory(), phone.shell.session.cwd)
        assertEquals(null, phone.shell.session.env["OMP_WORKSPACE"])
    }

    @Test
    fun anAnswerThatIsNeitherANumberNorNCreatesNothing() {
        phone.input.feed("maybe\r".toByteArray(Charsets.UTF_8))
        val answered = phone.runInteractive("omp")
        assertEquals(1, answered.status)
        assertTrue(answered.err, answered.err.contains("'maybe' is not a number and not n"))
        assertEquals(0, container.list()!!.size)
        assertEquals(harness.services.initialDirectory(), phone.shell.session.cwd)
    }

    // ---- taking one away ---------------------------------------------------------------------

    @Test
    fun ompRmRefusesWithoutForceNamesTheRealPathAndChangesNothing() {
        phone.run("omp new photos")
        File(container, "photos/note.txt").writeText("keep me\n")

        val refused = phone.run("omp rm photos")
        assertEquals(refused.err, 1, refused.status)
        assertTrue(refused.out, refused.out.contains(File(container, "photos").path))
        assertTrue(refused.out, refused.out.contains("nothing was deleted; pass --force"))
        assertTrue(refused.out, refused.out.contains("entries under it"))
        // Nothing was deleted, and the file is still where it was.
        assertTrue(File(container, "photos").isDirectory)
        assertEquals("keep me\n", File(File(container, "photos"), "note.txt").readText())

        val done = phone.run("omp rm photos --force")
        assertEquals(done.err, 0, done.status)
        assertTrue(done.out, done.out.contains("removed ${File(container, "photos").path}"))
        assertFalse(File(container, "photos").exists())
        assertEquals("the container is left with nothing in it", 0, container.list()!!.size)
    }

    @Test
    fun aFolderThisAppDidNotMakeSaysSoAndWantsTheForceAsWell() {
        phone.run("omp new notes")
        val byHand = File(container, "mine").apply { mkdirs() }
        File(byHand, "notes.txt").writeText("the user's own\n")

        val refused = phone.run("omp rm mine")
        assertEquals(refused.err, 1, refused.status)
        assertTrue(refused.out, refused.out.contains("was not made by this app"))
        assertTrue(refused.out, refused.out.contains("there is no ${Workspace.METADATA} in it"))
        assertTrue(refused.out, refused.out.contains("nothing was deleted; pass --force"))
        assertTrue("a refusal must not delete the user's folder", byHand.isDirectory)

        val done = phone.run("omp rm mine --force")
        assertEquals(done.err, 0, done.status)
        assertTrue(done.out, done.out.contains("it was not a folder this app made"))
        assertFalse(byHand.exists())
    }

    @Test
    fun rmOfANameThatIsNotThereIsRefusedWithTheErrno() {
        val refused = phone.run("omp rm nope --force")
        assertEquals(1, refused.status)
        assertTrue(refused.err, refused.err.contains("No such file or directory"))
        assertTrue(refused.err, refused.err.contains(File(container, "nope").path))
    }

    // ---- without the grant --------------------------------------------------------------------

    @Test
    fun withoutTheGrantOmpSaysSoAndCreatesNothingAtAll() {
        val ungranted = VmHarness(File(folder.root, "ungranted"), granted = false)
        val bare = VmPhone(ungranted.services)
        val nowhere = File(ungranted.externalDir!!, "Documents/omp")

        val listed = bare.run("omp ls")
        assertEquals(1, listed.status)
        assertTrue(listed.err, listed.err.contains("All files access"))
        assertTrue(listed.err, listed.err.contains("grant-storage"))

        val opened = bare.run("omp")
        assertEquals(1, opened.status)
        assertTrue(opened.err, opened.err.contains("grant-storage"))
        assertTrue(opened.err, opened.err.contains("a bind cannot grant what the app does not have"))

        val created = bare.run("omp new photos")
        assertEquals(1, created.status)
        assertTrue(created.err, created.err.contains("grant-storage"))
        // Not a fabricated container, and not a folder anywhere else either.
        assertFalse(nowhere.exists())
        assertFalse(File(ungranted.externalDir!!, "Documents").exists())

        // And inside the namespace the mount is absent with the same reason, exactly as
        // `/mnt/android` is when the grant is missing.
        val inVm = ungranted.run("omp ls")
        assertEquals(1, inVm.status)
        assertTrue(inVm.err, inVm.err.contains("grant-storage"))
        val mounts = ungranted.run("mount").out
        assertTrue(mounts, mounts.contains("/mnt/android"))
        assertFalse(mounts, mounts.contains(" on ${VmKernel.LAUNCHER_MOUNT} "))
    }

    // ---- the container and its bind -------------------------------------------------------------

    @Test
    fun bootingTwiceLeavesOneMountWithTheRealDeviceAndNothingInTheFstab() {
        val first = phone.run("vm boot")
        assertEquals(first.err, 0, first.status)
        assertEquals(
            "the boot mounts the container once",
            1,
            first.out.lines().count { it.startsWith("mount: ") && it.contains(VmKernel.LAUNCHER_MOUNT) },
        )
        val mounts = phone.run("vm mounts")
        assertEquals(mounts.err, 0, mounts.status)
        val lines = mounts.out.lines().filter { it.contains(" on ${VmKernel.LAUNCHER_MOUNT} ") }
        assertEquals("one mount, one line:\n${mounts.out}", 1, lines.size)
        val line = lines.single()
        assertTrue(line, line.startsWith("${container.path} on ${VmKernel.LAUNCHER_MOUNT} type "))
        // A bind shows its real device, which is what makes it a bind and not a name.
        assertTrue(line, line.contains("(rw,"))
        assertTrue(mounts.out, mounts.out.contains("the app's own bind for its conversations"))

        // A second boot adds no second mount and writes no second line anywhere.
        val before = tree()
        val booted = phone.run("vm boot")
        assertEquals(booted.err, 0, booted.status)
        assertEquals(
            "a second boot must not add a second mount",
            1,
            booted.out.lines().count { it.startsWith("mount: ") && it.contains(VmKernel.LAUNCHER_MOUNT) },
        )
        assertEquals("a second boot must not write to the disk", before, tree())
        assertEquals(
            "one mount after a second boot",
            1,
            phone.run("vm mounts").out.lines().count { it.contains(" on ${VmKernel.LAUNCHER_MOUNT} ") },
        )

        // App-owned means app-owned: no fstab line, and `vm umount` refuses it by name.
        val fstab = phone.run("vm exec 'cat /etc/fstab'").out
        assertFalse(fstab, fstab.contains(VmKernel.LAUNCHER_MOUNT))
        val unmounted = phone.run("vm umount ${VmKernel.LAUNCHER_MOUNT}")
        assertEquals(1, unmounted.status)
        assertTrue(unmounted.err, unmounted.err.contains("cannot be unmounted"))
    }

    // ---- the two namespaces, which is the whole feature -----------------------------------------

    @Test
    fun thePhoneAndTheVmAreTheSameFolderAndNotTwoCopies() {
        // Made on the phone, in the user's own storage.
        assertEquals(0, phone.run("omp new photos").status)
        assertTrue(File(container, "photos").isDirectory)

        // Read from inside the namespace, where the container is /mnt/omp.
        val listed = harness.run("omp ls")
        assertEquals(listed.err, 0, listed.status)
        assertTrue(listed.out, listed.out.contains("photos"))
        assertTrue(listed.out, listed.out.contains(File(container, "photos").path))
        assertTrue(listed.out, listed.out.contains(VmKernel.LAUNCHER_MOUNT))
        // Made on the phone there is no userland to name, so the file says nothing about one; made
        // in the namespace there is, and it says which.
        assertFalse(
            "the phone shell is not a distribution",
            File(File(container, "photos"), Workspace.METADATA).readText().contains("distro="),
        )
        assertEquals(0, harness.run("omp new in-here").status)
        val meta = File(File(container, "in-here"), Workspace.METADATA).readText()
        assertTrue(meta, meta.contains("distro=Ubuntu 24.04.1 LTS"))
        assertTrue(meta, meta.contains("title=in-here"))

        // Opened in there: the session is moved to the namespace path, and the real path is still
        // the one a file manager shows.
        val entered = harness.run("omp photos")
        assertEquals(entered.err, 0, entered.status)
        assertEquals("${VmKernel.LAUNCHER_MOUNT}/photos\n", harness.stdout("pwd"))
        assertEquals("${File(container, "photos").path}\n", harness.stdout("echo \$OMP_WORKSPACE_REAL"))
        assertEquals(
            "${VmKernel.LAUNCHER_MOUNT}/photos\n",
            harness.stdout("echo \$OMP_WORKSPACE"),
        )

        // Written in there, and the bytes are the bytes the phone sees: a bind, not a copy.
        assertEquals(0, harness.run("echo from-the-vm > ${VmKernel.LAUNCHER_MOUNT}/photos/note.txt").status)
        assertEquals("from-the-vm\n", File(File(container, "photos"), "note.txt").readText())
        assertEquals("from-the-vm\n", phone.run("cat ${File(container, "photos").path}/note.txt").out)

        // And written on the phone, read in the namespace.
        assertEquals(0, phone.run("echo from-the-phone > ${File(container, "photos/reply.txt").path}").status)
        assertEquals(
            "from-the-phone\n",
            harness.run("cat ${VmKernel.LAUNCHER_MOUNT}/photos/reply.txt").out,
        )

        // A folder the user made by hand appears in both, and neither invents it.
        val byHand = File(container, "hand-made").apply { mkdirs() }
        assertTrue(harness.run("omp ls").out.contains("hand-made"))
        assertTrue(phone.run("omp ls").out.contains("not made by omp"))
        assertTrue(byHand.isDirectory)
    }

    @Test
    fun theCommandIsTheOneTableThePhoneAndTheVmBothHave() {
        assertNotNull(CommandTable.global.lookup("omp"))
        assertEquals(OmpCommand::class.java, CommandTable.global.lookup("omp")!!.javaClass)
        // Inherited by the namespace's table, and still the same command there.
        assertEquals(CommandTable.global.lookup("omp"), harness.table.lookup("omp"))
        val spec = CommandTable.global.specOf("omp")!!
        assertEquals("system", spec.group)
        // The help text describes what it does and nothing it does not.
        assertTrue(spec.synopsis, spec.synopsis.contains("new [NAME]"))
        assertTrue(spec.synopsis, spec.synopsis.contains("rm NAME --force"))
        assertTrue(spec.notes, spec.notes.contains("Documents"))
    }

    // ---- where the output went, and what the prompt was answered with ---------------------------

    @Test
    fun aRedirectedOmpPrintsTheTableToTheFileAndAsksNothing() {
        phone.run("omp new photos")
        val before = phone.shell.session.cwd
        val captured = File(folder.root, "captured.txt")
        // The channel and `isTty` are the terminal's here: `omp` is the last stage of a one-stage
        // pipeline, and only a redirect says where its output really goes. Without the redirect
        // check the question is asked into the file, the answer is taken from the keyboard, and
        // the next line the user types has lost its first character.
        phone.input.feed("1\r".toByteArray(Charsets.UTF_8))
        val redirected = phone.runInteractive("omp > ${captured.path}")
        assertEquals(redirected.err, 0, redirected.status)
        val said = captured.readText()
        assertTrue(said, said.contains("photos"))
        assertFalse("the question went into the file", said.contains("type a number to continue"))
        assertFalse("a conversation was opened by a key nobody could see", said.contains("omp: in "))
        assertTrue(said, said.contains("stdin is not the terminal this command owns"))
        assertTrue(said, said.contains("a number from the list above, or n for a new one"))
        // Nothing was opened, and the keystroke is still the line editor's.
        assertEquals(before, phone.shell.session.cwd)
        assertEquals('1'.code, phone.shell.session.input!!.pollByte(500))
    }

    @Test
    fun aBareEnterAtThePromptIsNotReportedAsACancellation() {
        phone.run("cd \$HOME")
        val before = phone.shell.session.cwd
        phone.input.feed("\r".toByteArray(Charsets.UTF_8))
        val answered = phone.runInteractive("omp")
        assertEquals(answered.err, 2, answered.status)
        assertTrue(answered.err, answered.err.contains("nothing was typed at the prompt"))
        assertFalse("a bare Enter is not a Ctrl-C", answered.err.contains("cancelled"))
        assertEquals(before, phone.shell.session.cwd)
        assertEquals(0, container.list()!!.size)
    }

    @Test
    fun theCountNamesEveryEntryIncludingDirectoriesAndSaysWhatItCouldNotRead() {
        // The reviewer's folder: a file, a directory and a file inside it is three things, and a
        // prompt that said two was a prompt about a folder nobody has.
        val byHand = File(container, "mine").apply { mkdirs() }
        File(byHand, "holiday.jpg").writeBytes(ByteArray(4))
        File(byHand, "sub").mkdirs()
        File(File(byHand, "sub"), "deep.txt").writeText("x")

        val plain = phone.run("omp rm mine")
        assertEquals(plain.err, 1, plain.status)
        assertTrue(plain.out, plain.out.contains("3 entries under it, 5 bytes"))
        assertFalse(plain.out, plain.out.contains("could not be listed"))

        // A subtree this app cannot read is counted as one entry and said out loud, because its
        // size is unknown and a delete prompt must not be smaller than the truth.
        harness.kernel.vfs.add(
            Mount("/mnt/omp/mine/sub", "test", "ext4", DenyingReadDir(RealVfs(File(byHand, "sub").path))),
        )
        val hidden = harness.run("omp rm mine")
        assertEquals(hidden.err, 1, hidden.status)
        assertTrue(
            hidden.out,
            hidden.out.contains("2 entries under it, 4 bytes (1 could not be listed)"),
        )
        assertTrue("nothing may be deleted by a refusal", byHand.isDirectory)
    }

    @Test
    fun aDeleteThatFailsHalfwaySaysWhatItAlreadyDeleted() {
        // The walk is not atomic, so the walk's own tally has to travel with the failure: a user
        // told "cannot delete X" and not told that the rest of the folder is already gone has been
        // told a lie by omission. A read-only bind is the failure that needs no permissions to
        // arrange, and it is a real one: /dev/null is mounted that way in a real system.
        assertEquals(0, phone.run("omp new photos").status)
        File(container, "photos/a.txt").writeText("first\n")
        val locked = File(container, "photos/locked").apply { mkdirs() }
        File(locked, "keep.txt").writeText("not this\n")
        assertEquals(0, phone.run("vm mount ${locked.path} /mnt/omp/photos/locked --read-only").status)

        val failed = phone.run("vm exec 'omp rm photos --force'")
        assertEquals(failed.err, 1, failed.status)
        assertTrue(failed.err, failed.err.contains(FsErrno.READ_ONLY.text))
        assertTrue(
            "the failure line must say what had already gone:\n${failed.err}",
            failed.err.contains("had already been deleted"),
        )
        // And the truth on disk agrees: the file before the locked one is gone, the locked one is not.
        assertFalse(File(container, "photos/a.txt").exists())
        assertTrue(File(locked, "keep.txt").isFile)
    }

    @Test
    fun aPhoneListingNamesTheContainerOnceAndTheNamespaceNamesItTwice() {
        // On the phone `root` and `hostRoot` are one string, so the "(… in the VM)" parenthetical
        // — which exists to say "two names, one folder" — has nothing to say.
        assertTrue(container.deleteRecursively())
        val cold = phone.run("omp ls")
        assertEquals(cold.out, 1, cold.out.lines().count { it.contains(container.path) })
        assertFalse(cold.out, cold.out.contains("in the VM"))
        assertTrue(cold.out, cold.out.contains("no conversations"))

        phone.run("omp new photos")
        val warm = phone.run("omp ls")
        assertTrue(warm.out, warm.out.contains("photos"))
        assertFalse("the phone must not name the same directory twice", warm.out.contains("in the VM"))
        // The header names the container and the row names the conversation inside it: two paths,
        // two real directories, and no third one invented to contrast them with.
        assertEquals(warm.out, 2, warm.out.lines().count { it.contains(container.path) })

        // In the namespace the two names really are two, and the parenthetical is the point.
        val inVm = harness.run("omp ls")
        assertTrue(inVm.out, inVm.out.contains("(${VmKernel.LAUNCHER_MOUNT} in the VM)"))
        assertTrue(inVm.out, inVm.out.contains("photos"))
    }

    @Test
    fun aSessionOverARootedFilesystemIsRefusedRatherThanGivenAContainerInsideIt() {
        // `RealVfs` publishes `isDeviceRoot` for exactly this question, and a rooted one answers
        // `<root>/storage/...`: a path inside somebody else's namespace, which this session's own
        // filesystem would not serve. The answer is one line, not a container.
        val rooted = RealVfs("/somewhere/else")
        val session = Session(harness.services, Screen(24, 80), rooted)
        val ctx = ExecContext(
            listOf("omp"), ByteArrayInputStream(ByteArray(0)),
            ByteArrayOutputStream(), ByteArrayOutputStream(),
            session.env, harness.services, session, true, AtomicBoolean(false),
        )
        val located = Containers.locate(ctx)
        assertFalse(located.isReady)
        assertTrue(located.refusal, located.refusal.contains("does not say which directories"))
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Every file in the VM's disk with its size, which is what "a second boot changed nothing" means. */
    private fun tree(): Set<Pair<String, Long>> =
        File(harness.services.appFilesDir(), VmCommand.DISK_DIR)
            .walkTopDown().filter { it.isFile }.map { it.absolutePath to it.length() }.toSet()
}
