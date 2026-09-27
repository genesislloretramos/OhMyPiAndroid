package omp.vm

import omp.shell.cmd.Cmds
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.VEntry
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import omp.vm.workspace.MetadataState
import omp.vm.workspace.Workspace
import omp.vm.workspace.WorkspaceList
import omp.vm.workspace.WorkspaceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A [Vfs] that answers [FsErrno.PERM_DENIED] for the paths a predicate names — another app's files,
 * or a container whose grant is missing. The seam has an errno for exactly this, and a test that
 * could only reach it through `chmod` would silently stop testing anything on a machine where the
 * suite happens to run as root.
 */
private class DenyingVfs(private val inner: Vfs, private val denied: (String) -> Boolean) : Vfs by inner {

    private fun check(path: String) {
        if (denied(path)) throw FsException(FsErrno.PERM_DENIED, path)
    }

    override fun stat(path: String): VStat {
        check(path)
        return inner.stat(path)
    }

    override fun readDir(path: String): List<VEntry> {
        check(path)
        return inner.readDir(path)
    }

    override fun readBytes(path: String): ByteArray {
        check(path)
        return inner.readBytes(path)
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        check(path)
        inner.writeBytes(path, bytes)
    }

    override fun mkdir(path: String) {
        check(path)
        inner.mkdir(path)
    }

    override fun rename(from: String, to: String) {
        check(from)
        check(to)
        inner.rename(from, to)
    }
}


/** A [Vfs] that hands directory entries back in the opposite order, the way a filesystem may. */
private class ShufflingVfs(private val inner: Vfs) : Vfs by inner {

    override fun readDir(path: String): List<VEntry> = inner.readDir(path).reversed()
}

/** A volume that folds case the way the exFAT behind `/storage/emulated/0` does. */
private class CaseFoldingVfs(private val inner: Vfs) : Vfs by inner {

    override fun stat(path: String): VStat = try {
        inner.stat(path)
    } catch (e: FsException) {
        if (e.errno != FsErrno.NO_SUCH_FILE) throw e
        val parent = path.substringBeforeLast('/', "/")
        val name = path.substringAfterLast('/')
        val hit = inner.readDir(parent).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw e
        inner.stat(Workspace.child(parent, hit.name))
    }
}

/**
 * The folder lifecycle, over a real [omp.shell.fs.RealVfs] on a real temporary directory.
 *
 * The arrangement mirrors the phone exactly: `Documents/omp` on the device is bound at `/mnt/omp` in
 * the namespace, and every assertion that matters is made with `java.io.File` against the real
 * directory — because the promise this feature makes is not "the command prints a table", it is "the
 * bytes are in a folder the user can open in a file manager". A test that only went through the [Vfs]
 * could not tell a bind from a copy.
 */
class WorkspaceTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A letter outside the BMP: two `char`s, one character, and the case a cap has to get right. */
    private val deseret = "𐐀"

    /** The real `Documents` on the phone, and the `omp` container every project lives inside. */
    private lateinit var documents: File
    private lateinit var ompDir: File
    private lateinit var vfs: Vfs
    private var clock: Long = 0
    private lateinit var ws: Workspace

    @Before
    fun setUp() {
        documents = folder.newFolder("Documents")
        ompDir = File(documents, "omp").apply { mkdirs() }
        vfs = RealVfs("", listOf("/mnt/omp" to ompDir.path))
        clock = 1_757_000_000_000L // 2025-09-04T15:33:20Z, fixed so every generated name is exact
        ws = Workspace(vfs, "/mnt/omp", ompDir.path) { clock }
    }

    @Test
    fun aNewConversationIsARealFolderTheUserCanOpenInAFileManager() {
        val entry = ws.create("notes")
        assertEquals("notes", entry.name)
        assertEquals("/mnt/omp/notes", entry.path)
        assertEquals(File(ompDir, "notes").path, entry.hostPath)
        assertEquals(clock, entry.createdMillis)
        assertEquals(MetadataState.RECORDED, entry.meta)

        // The whole point, checked with the one thing that knows what a folder is.
        val onDisk = File(ompDir, "notes")
        assertTrue(onDisk.path, onDisk.isDirectory)
        assertEquals(listOf(Workspace.METADATA), onDisk.list()!!.toList())
        // Inside the `omp` container, and not loose in `Documents` where the app must not litter.
        assertFalse(File(documents, "notes").exists())

        // The namespace path is a bind, not a copy: a byte written through the Vfs is the very byte
        // the file manager will show, and there is no second copy of it anywhere.
        vfs.writeBytes("/mnt/omp/notes/out.txt", "written in the VM".toByteArray())
        val written = File(onDisk, "out.txt")
        assertTrue(written.path, written.isFile)
        assertEquals("written in the VM", written.readText())
    }

    @Test
    fun theRealPathComesFromTheBindAndNotFromTheStringItWasGiven() {
        // Exactly the disagreement a caller can produce: the bind is here, the string claims a
        // folder on a phone. The bind wins, or every answer above is a path to nothing.
        val lied = Workspace(vfs, "/mnt/omp", "/storage/emulated/0/Documents/omp") { clock }
        assertEquals(ompDir.path, lied.hostRoot)
        val entry = lied.create("notes")
        assertEquals(File(ompDir, "notes").path, entry.hostPath)
        assertEquals(File(ompDir, "notes").path, lied.metadata(entry)[Workspace.HOST])
        assertTrue(WorkspaceList.render(lied).any { it.contains(ompDir.path) })
    }

    @Test
    fun theGeneratedNameIsTheDocumentedPatternAndTheSecondOneGetsASuffix() {
        val first = ws.create(null)
        val second = ws.create("   ")
        assertTrue(first.name, first.name.matches(Regex("session-\\d{8}-\\d{4}")))
        // The same instant makes the same pattern, so the second one has to step aside.
        assertEquals("${first.name}-2", second.name)
        assertTrue(File(ompDir, second.name).isDirectory)
    }

    @Test
    fun aNameAlreadyInUseGetsTheFirstFreeSuffixRatherThanAnError() {
        assertEquals("notes", ws.create("notes").name)
        assertEquals("notes-2", ws.create("notes").name)
        assertEquals("notes-3", ws.create("notes").name)
        assertEquals(listOf("notes", "notes-2", "notes-3"), ws.list().map { it.name }.sorted())
        // All three are real, and the first was not moved or emptied to make room.
        assertTrue(File(ompDir, "notes-2").isDirectory)
        assertTrue(File(ompDir, "notes-3").isDirectory)
        assertEquals(listOf(Workspace.METADATA), File(ompDir, "notes").list()!!.toList())
        assertEquals("notes", ws.metadata(ws.open("notes"))[Workspace.NAME])
        assertEquals("notes-2", ws.metadata(ws.open("notes-2"))[Workspace.NAME])
    }

    @Test
    fun theCollisionSuffixIsInsideTheCapAndNotPastIt() {
        val long = "a".repeat(100)
        val first = ws.create(long)
        val second = ws.create(long)
        assertEquals(WorkspaceName.MAX, first.name.codePointCount(0, first.name.length))
        assertEquals(WorkspaceName.MAX, second.name.codePointCount(0, second.name.length))
        // The suffix is inside the budget, so the second folder is not longer than the first, and it
        // is still a different folder.
        assertFalse(first.name == second.name)
        assertTrue(File(ompDir, second.name).isDirectory)
    }

    @Test
    fun theListingIsNewestFirstAndTotalWhenTwoFoldersShareAnInstant() {
        val old = ws.create("old")
        val middle = ws.create("middle")
        val newest = ws.create("newest")
        vfs.setModified(old.path, 1_000L)
        vfs.setModified(middle.path, 2_000L)
        vfs.setModified(newest.path, 3_000L)
        assertEquals(listOf("newest", "middle", "old"), ws.list().map { it.name })

        // Equal mtimes fall back to the name. Names differing only by case are the case that can
        // actually break a comparator — a name order that ignored case, or fell back to something
        // unstable, would order these four differently from run to run.
        for (name in listOf("beta", "Beta", "BETA", "alpha")) {
            ws.create(name)
            vfs.setModified(child(name), 5_000L)
        }
        vfs.setModified(old.path, 5_000L)
        // `newest` (3s) and `middle` (2s) are untouched, so the tie group leads and the rest keep
        // their own times — which is the whole of "newest first, then a total order on the name".
        assertEquals(listOf("BETA", "Beta", "alpha", "beta", "old", "newest", "middle"), ws.list().map { it.name })
        // And the same table, even when the filesystem hands the names back in a different order:
        // a listing whose order depends on what `readDir` returned is not one a user can talk about,
        // and comparing a call to itself could never have caught that.
        val shuffled = Workspace(ShufflingVfs(vfs), "/mnt/omp", ompDir.path) { clock }
        assertEquals(ws.list().map { it.name }, shuffled.list().map { it.name })
    }

    @Test
    fun aFolderTheUserMadeByHandIsListedWithoutMetadata() {
        File(ompDir, "photos").apply { mkdirs() }.setLastModified(1_700_000_000_000L)

        val entry = ws.list().single()
        assertEquals("photos", entry.name)
        assertEquals(MetadataState.NONE, entry.meta)
        // Nothing knows when it was created, so the directory's own mtime is the honest answer and
        // is not dressed up as a creation time this app recorded.
        assertEquals(1_700_000_000_000L, entry.createdMillis)

        val rendered = WorkspaceList.render(ws).joinToString("\n")
        assertTrue(rendered, rendered.contains(File(ompDir, "photos").path))
        assertTrue(rendered, rendered.contains("not made by omp"))
    }

    @Test
    fun aMetadataFileThatIsNotOursAndOneWeCannotReadAreToldApart() {
        ws.create("notes")
        File(ompDir, "hand-written").mkdirs()
        File(ompDir, "hand-written/${Workspace.METADATA}").writeText("title=not a record of anything\n")
        File(ompDir, "locked").mkdirs()
        File(ompDir, "locked/${Workspace.METADATA}").writeText("version=1\nname=locked\n")

        val locked = Workspace(DenyingVfs(vfs) { it.startsWith("/mnt/omp/locked/") }, "/mnt/omp", ompDir.path) { clock }
        val byName = locked.list().associateBy { it.name }
        assertEquals(setOf("notes", "hand-written", "locked"), byName.keys)
        assertEquals(MetadataState.RECORDED, byName.getValue("notes").meta)
        assertEquals(MetadataState.CORRUPT, byName.getValue("hand-written").meta)
        // A conversation this app made must never be reported as one it did not make.
        assertEquals(MetadataState.UNREADABLE, byName.getValue("locked").meta)

        // The yes/no shorthand a caller will reach for agrees with the reason in all four states,
        // and says yes for exactly one of the three folders here.
        assertEquals(setOf("notes"), locked.list().filter { it.hasMetadata }.map { it.name }.toSet())

        // Three states, three different sentences: a folder this app made and cannot read must
        // never be described as one it did not make.
        val all = WorkspaceList.render(locked)
        val row = { name: String -> all.single { it.contains(name) && it.contains(ompDir.path) } }
        assertTrue(row("locked"), row("locked").contains("could not be read"))
        assertTrue(row("hand-written"), row("hand-written").contains("not in this app's format"))
        assertFalse(all.toString(), all.any { it.contains("not made by omp") })
    }

    @Test
    fun aCorruptMetadataFileDoesNotHideItsConversation() {
        ws.create("notes")
        File(ompDir, "hand-edited").mkdirs()
        File(ompDir, "hand-edited/${Workspace.METADATA}").writeText("this is not a key=value file\n!!!\n")
        File(ompDir, "truncated").mkdirs()
        File(ompDir, "truncated/${Workspace.METADATA}").writeText("")

        val entries = ws.list()
        assertEquals(setOf("notes", "hand-edited", "truncated"), entries.map { it.name }.toSet())
        val byName = entries.associateBy { it.name }
        assertEquals(MetadataState.RECORDED, byName.getValue("notes").meta)
        assertEquals(MetadataState.CORRUPT, byName.getValue("hand-edited").meta)
        assertEquals(MetadataState.CORRUPT, byName.getValue("truncated").meta)
        // A file we do not recognise is not trusted for a creation time either.
        assertTrue(byName.getValue("hand-edited").createdMillis > 0)
    }

    @Test
    fun aMalformedLineIsSkippedAndTheRestOfTheFileStillReadsBack() {
        val entry = ws.create("notes")
        File(ompDir, "notes/${Workspace.METADATA}").writeText(
            "version=1\n" +
                "this line is not a pair\n" +
                "=novalue\n" +
                "title=count the stars in the sky\n",
        )
        val meta = ws.metadata(entry)
        assertEquals("1", meta[Workspace.VERSION])
        assertEquals("count the stars in the sky", meta[Workspace.TITLE])
        assertFalse(meta.containsKey("this line is not a pair"))
        assertEquals(MetadataState.RECORDED, ws.list().single().meta)
    }

    @Test
    fun aFolderRenamedOnThePhoneListsUnderItsNewNameAndKeepsTheOldOneAsHistory() {
        val created = ws.create("notes")
        assertEquals("notes", created.name)

        // Exactly what the Android file manager does, done here with java.io.File because that is
        // what it is.
        assertTrue(File(ompDir, "notes").renameTo(File(ompDir, "journal")))

        val entry = ws.list().single()
        assertEquals("journal", entry.name)
        assertEquals("/mnt/omp/journal", entry.path)
        assertEquals(File(ompDir, "journal").path, entry.hostPath)
        // The old name is history, and history does not get to rename anything.
        assertEquals("notes", ws.metadata(entry)[Workspace.NAME])
        assertEquals(clock.toString(), ws.metadata(entry)[Workspace.CREATED])
        assertEquals("journal", ws.open("journal").name)
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { ws.open("notes") })

        val row = WorkspaceList.render(ws).single { it.contains(File(ompDir, "journal").path) }
        assertTrue(row, row.startsWith("  1  journal"))
        // The disagreement is said once, in that row, and the directory's name is what is listed.
        assertEquals(1, Regex("renamed from").findAll(row).count())
        assertTrue(row, row.contains("\"notes\""))
    }

    @Test
    fun openRefusesAMissingConversationAndAFileInItsPlace() {
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { ws.open("nothing-here") })
        File(ompDir, "afile").writeText("not a conversation")
        assertEquals(FsErrno.NOT_A_DIRECTORY, errnoOf { ws.open("afile") })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { ws.open("../../etc") })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { ws.open("..") })
    }

    @Test
    fun openRefusesANameThatIsAPathAndDoesNotResolveItSomewhereElse() {
        val notes = ws.create("notes")
        // Every one of these names the same folder, and every one of them is something a caller can
        // compose by accident. Two ways of naming one conversation is one too many.
        for (raw in listOf("notes/../..", "notes/..", "notes/", "./notes", "notes/.", "notes\\x", "no/tes")) {
            assertEquals(raw, FsErrno.NO_SUCH_FILE, errnoOf { ws.open(raw) })
        }
        // A name that merely needs tidying is still the folder the user meant.
        assertEquals("My-Project", ws.create("My Project").name)
        assertEquals("My-Project", ws.open("My Project").name)
        assertEquals(notes.name, "notes")
    }

    @Test
    fun openTakesTheNameTheVolumeReportsNotTheOneThatWasTyped() {
        val folding = Workspace(CaseFoldingVfs(vfs), "/mnt/omp", ompDir.path) { clock }
        folding.create("notes")
        // `/storage/emulated/0` folds case, so these are one directory with one spelling, and the
        // spelling is the directory's.
        val entry = folding.open("NOTES")
        assertEquals("notes", entry.name)
        assertEquals("/mnt/omp/notes", entry.path)
        assertEquals(File(ompDir, "notes").path, entry.hostPath)
        assertEquals(listOf("notes"), folding.list().map { it.name })
    }

    @Test
    fun anUnreadableRootIsNotReportedAsAMissingOne() {
        val denied = Workspace(
            DenyingVfs(vfs) { it == "/mnt/omp" || it.startsWith("/mnt/omp/") },
            "/mnt/omp",
            ompDir.path,
        ) { clock }
        // A phone without the all-files grant looks exactly like this, and "your folder is missing,
        // go and make a conversation" sends the user to create a conversation that fails the same
        // way with the same lie.
        assertEquals(FsErrno.PERM_DENIED, errnoOf { denied.list() })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { denied.create("notes") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { WorkspaceList.render(denied) })
    }

    @Test
    fun aMetadataFileThatCannotBeReadIsNeverWrittenOver() {
        val entry = ws.create("notes")
        val path = File(ompDir, "notes/${Workspace.METADATA}")
        val before = path.readBytes()
        assertTrue(before.isNotEmpty())

        val denied = Workspace(
            DenyingVfs(vfs) { it.endsWith(Workspace.METADATA) },
            "/mnt/omp",
            ompDir.path,
        ) { clock }
        // Rewriting over a file whose keys we could not read would drop the name — the only record
        // the "renamed from" note exists to show.
        assertEquals(FsErrno.PERM_DENIED, errnoOf { denied.writeMetadata(entry, mapOf(Workspace.TITLE to "x")) })
        assertArrayEquals(before, path.readBytes())
    }

    @Test
    fun metadataRoundTripsTheRealHostPathAndEverythingTheCallerAdds() {
        val entry = ws.create("notes")
        ws.writeMetadata(entry, mapOf(Workspace.DISTRO to "Ubuntu 24.04.1 LTS", Workspace.TITLE to "why the sky is dark"))

        val meta = ws.metadata(entry)
        assertEquals("1", meta[Workspace.VERSION])
        assertEquals("notes", meta[Workspace.NAME])
        assertEquals(clock.toString(), meta[Workspace.CREATED])
        assertEquals("/mnt/omp/notes", meta[Workspace.PATH])
        assertEquals(File(ompDir, "notes").path, meta[Workspace.HOST])
        assertEquals("Ubuntu 24.04.1 LTS", meta[Workspace.DISTRO])
        assertEquals("why the sky is dark", meta[Workspace.TITLE])

        // The same facts, read straight off the disk by something that is not this class at all.
        val raw = File(ompDir, "notes/${Workspace.METADATA}").readLines()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(meta, raw)
        // Version first, so a file anything else truncated is still recognisable as ours.
        assertEquals("version=1", File(ompDir, "notes/${Workspace.METADATA}").readLines().first())
    }

    @Test
    fun aLaterWriteKeepsTheNameAndEverythingElseAlreadyRecorded() {
        val entry = ws.create("notes")
        ws.writeMetadata(entry, mapOf(Workspace.DISTRO to "Ubuntu 24.04.1 LTS"))
        ws.writeMetadata(entry, mapOf(Workspace.TITLE to "second thoughts"))
        val meta = ws.metadata(entry)
        assertEquals("notes", meta[Workspace.NAME])
        assertEquals("Ubuntu 24.04.1 LTS", meta[Workspace.DISTRO])
        assertEquals("second thoughts", meta[Workspace.TITLE])
    }

    @Test
    fun aMetadataWriteLeavesNoHalfWrittenFileAndNoScratchFileBehind() {
        val entry = ws.create("notes")
        // A scratch file left by a crash from a previous run must be replaced, not adopted.
        File(ompDir, "notes/${Workspace.TEMP}").writeText("rubbish from a killed process")
        ws.writeMetadata(entry, mapOf(Workspace.TITLE to "whole"))
        assertEquals(listOf(Workspace.METADATA), File(ompDir, "notes").list()!!.toList())
        assertEquals("whole", ws.metadata(entry)[Workspace.TITLE])
    }

    @Test
    fun theTwoTornStatesThatWouldLoseTheNameAreRepairedRatherThanCarriedForward() {
        // Truncate-then-write, interrupted: an empty file, and a file holding only its first line.
        // Neither has a version key, so the folder reads as hand-made, and a naive merge would carry
        // that emptiness forward and finish erasing the history.
        for (torn in listOf("", "name=notes\n")) {
            val entry = ws.create("notes")
            val path = File(ompDir, "notes/${Workspace.METADATA}")
            path.writeText(torn)
            assertEquals(torn, MetadataState.CORRUPT, ws.list().single().meta)

            ws.writeMetadata(entry, mapOf(Workspace.TITLE to "after the tear"))
            val meta = ws.metadata(entry)
            assertEquals(torn, "1", meta[Workspace.VERSION])
            // The directory's own name is the truth, so it can always be put back.
            assertEquals(torn, "notes", meta[Workspace.NAME])
            assertEquals(torn, "after the tear", meta[Workspace.TITLE])
            assertEquals(torn, clock.toString(), meta[Workspace.CREATED])
            assertEquals(torn, MetadataState.RECORDED, ws.list().single().meta)
            // The next rename is still noticed, because the name is back.
            assertTrue(File(ompDir, "notes").renameTo(File(ompDir, "renamed")))
            assertTrue(WorkspaceList.render(ws).any { it.contains("renamed from \"notes\"") })
            assertTrue(File(ompDir, "renamed").deleteRecursively())
        }
    }

    @Test
    fun aMissingRootIsRefusedRatherThanReportedAsNothingThere() {
        // A [RealVfs] rooted at a real directory, as the app's own filesystem is: the missing root
        // maps inside it, so the message can name where the folder *would* be.
        val store = folder.newFolder("app-storage")
        val absent = Workspace(RealVfs(store.path), "/mnt/absent", File(documents, "absent").path) { clock }
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { absent.list() })
        val rendered = WorkspaceList.render(absent).joinToString("\n")
        assertTrue(rendered, rendered.contains("/mnt/absent does not exist"))
        assertTrue(rendered, rendered.contains(File(store, "mnt/absent").path))
        assertFalse(rendered, rendered.contains("conversations in"))
    }

    @Test
    fun anEmptyRootSaysSoInsteadOfPrintingAnEmptyTable() {
        val rendered = WorkspaceList.render(ws)
        assertEquals(1, rendered.size)
        assertTrue(rendered.single(), rendered.single().contains("no conversations yet"))
        assertTrue(rendered.single(), rendered.single().contains(ompDir.path))
    }

    @Test
    fun everyHostileNameBecomesOneSafeRealFolderInsideTheContainer() {
        val hostile = listOf("../../etc/shadow", "a/b", "..", ".".repeat(300), "two\nlines", ".hidden", "-flag")
        for (raw in hostile) {
            val entry = ws.create(raw)
            assertFalse("a separator survived in '${entry.name}'", entry.name.contains('/'))
            assertTrue("'${entry.name}' escaped the root", entry.path.startsWith("/mnt/omp/"))
            assertTrue("'${entry.name}' is not on the disk", File(ompDir, entry.name).isDirectory)
        }
        val names = ws.list().map { it.name }
        assertEquals(hostile.size, names.size)
        assertEquals(hostile.size, names.toSet().size)
        assertTrue(names.all { File(ompDir, it).isDirectory })
        // Nothing landed outside the `omp` container, in either spelling.
        assertEquals(hostile.size, ompDir.listFiles()!!.count { it.isDirectory })
        assertEquals(1, documents.listFiles()!!.size)
        assertFalse(File(documents, "etc").exists())
    }

    @Test
    fun aNameOfNothingButUnusableCharactersStillGetsAConversation() {
        // An emoji, a flag, a zero-width joiner: each is a name the user could type, none of them
        // is a name a folder can have, and none of them may be a reason the user gets nothing.
        val unusable = listOf("🎉", "🇩🇪", "‍", "!!!")
        for (raw in unusable) {
            assertNull(raw, WorkspaceName.sanitize(raw))
            val entry = ws.create(raw)
            assertTrue("'$raw' produced no folder", File(ompDir, entry.name).isDirectory)
            assertTrue("'$raw' -> '${entry.name}'", entry.name.startsWith("session-"))
        }
        // Each one is a separate folder, and none of them is the same conversation as the last.
        assertEquals(unusable.size, ws.list().size)
        assertTrue(ws.list().all { it.name.matches(Regex("session-\\d{8}-\\d{4}(-\\d+)?")) })

        // A name that is mostly usable keeps what it can: the emoji is not a filename character,
        // the letters around it are the user's own.
        assertEquals("ab", WorkspaceName.sanitize("a🎉b"))
        assertEquals("ab", ws.create("a🎉b").name)
    }

    @Test
    fun theListingNumbersTheRowsAndLabelsTheTimeItShows() {
        ws.create("notes")
        val lines = WorkspaceList.render(ws)
        assertTrue(lines[0], lines[0].contains(ompDir.path))
        assertTrue(lines[0], lines[0].contains("/mnt/omp"))
        // The header says which time is on the row, so a user cannot mistake a modification for a
        // creation.
        assertTrue(lines[1], lines[1].contains("modified"))
        assertTrue(lines[1], lines[1].contains("where"))
        val row = lines[2]
        assertTrue(row, row.startsWith("  1  notes  "))
        // The shell's own timestamp format, not a second one invented here, and it is the modified
        // time: the directory was written and then stamped.
        assertTrue(row, row.contains(Cmds.timestamp(clock)))
        assertTrue(row, row.contains(File(ompDir, "notes").path))
    }

    @Test
    fun aNameOfAstralCharactersDoesNotStepTheTableOutOfLine() {
        val wide = ws.create(deseret.repeat(20))
        val plain = ws.create("notes")
        vfs.setModified(plain.path, 1_000L)
        vfs.setModified(wide.path, 2_000L)
        val rows = WorkspaceList.render(ws).filter { it.contains("/Documents/omp/") }
        assertEquals(2, rows.size)
        // Where each row's real path begins, counted in the columns a terminal draws, not in the
        // UTF-16 units `indexOf` counts: an astral name is twice as many units as it is columns.
        val starts = rows.map { columns(it.substring(0, it.indexOf(ompDir.path))) }
        assertEquals(starts[0], starts[1])
    }

    private fun child(name: String): String = "/mnt/omp/$name"

    private fun columns(text: String): Int = text.codePointCount(0, text.length)

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    /** The errno a call refused with; a call that succeeds fails the test instead. */
    private fun errnoOf(body: () -> Unit): FsErrno = try {
        body()
        throw AssertionError("expected an FsException")
    } catch (e: FsException) {
        e.errno
    }
}
