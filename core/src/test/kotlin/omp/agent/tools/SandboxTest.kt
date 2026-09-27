package omp.agent.tools

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A namespace that cannot follow a link at all, and says so rather than guessing.
 *
 * A [Vfs] is free to be that, and a boundary that only holds on a filesystem that answers every
 * question is not a boundary. The refusal is what makes this one worth a test: the class must not
 * treat a name whose target it never saw as an ordinary one.
 */
private class LinkBlindVfs(private val inner: Vfs) : Vfs by inner {
    override fun realpath(path: String): String {
        val stat = try {
            inner.stat(path)
        } catch (e: FsException) {
            null
        }
        if (stat?.type == VNodeType.SYMLINK) throw FsException(FsErrno.PERM_DENIED, path)
        return inner.realpath(path)
    }
}

/**
 * A [Vfs] that answers [Vfs.realpath] only for paths that are there, which is what a namespace has
 * to do: a dangling link has to fail at the point of use rather than resolve to a guess. A path
 * that is not there yet is still a path the agent may write, so the boundary has to check the
 * deepest ancestor that does answer.
 */
private class ExistingOnlyVfs(private val inner: Vfs) : Vfs by inner {
    override fun realpath(path: String): String {
        try {
            inner.stat(path)
        } catch (e: FsException) {
            throw e
        }
        return inner.realpath(path)
    }
}

/**
 * The boundary, over a real [RealVfs] on a real directory, arranged as the phone is.
 *
 * `Documents/omp` on the device is bound at `/mnt/omp` in the namespace, the project `photos` is
 * one conversation inside it, and `videos` is the conversation next to it that this agent was not
 * given. The namespace has an `/etc` of its own, which is the whole point of the symlink cases: a
 * link to `/etc/passwd` in the namespace resolves to the namespace's `/etc/passwd`, not the phone's,
 * and it is a directory the agent may not touch either way. Every assertion is made through the
 * [Vfs] — the same seam the tools act through — because a boundary checked against a different
 * filesystem than the one that does the writing is a claim, not a result.
 */
class SandboxTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var vfs: Vfs
    private lateinit var box: Sandbox

    @Before
    fun setUp() {
        val ns = folder.newFolder("ns")
        val omp = folder.newFolder("ns", "mnt", "omp")
        vfs = RealVfs(ns.path, listOf("/mnt/omp" to omp.path))
        vfs.mkdir("/mnt/omp/photos")
        vfs.mkdir("/mnt/omp/photos/sub")
        vfs.mkdir("/mnt/omp/photos/deep")
        vfs.mkdir("/mnt/omp/photos/deep/deeper")
        vfs.mkdir("/mnt/omp/videos")
        vfs.mkdir("/etc")
        vfs.writeBytes("/etc/passwd", "root:x:0:0::/root:/bin/sh\n".toByteArray())
        vfs.writeBytes("/mnt/omp/photos/notes.md", "# notes\n".toByteArray())
        box = Sandbox(vfs, "/mnt/omp", "/mnt/omp/photos")
    }

    @Test
    fun theProjectItselfIsTheOnlyPathWithNoRelativeForm() {
        val p = box.resolve("/mnt/omp/photos")
        assertEquals("/mnt/omp/photos", p.value)
        assertEquals(".", p.projectRelative)
        assertTrue(p.isProject)
        assertTrue(box.contains("/mnt/omp/photos"))
        assertEquals(".", box.projectRelative("/mnt/omp/photos"))
    }

    @Test
    fun aFileThatIsThereAFileThatIsNotAndAWholeNestedPathAreAllOurs() {
        val existing = box.resolve("notes.md")
        assertEquals("/mnt/omp/photos/notes.md", existing.value)
        assertEquals("notes.md", existing.projectRelative)
        assertFalse(existing.isProject)

        val fresh = box.resolve("out/new.txt")
        assertEquals("/mnt/omp/photos/out/new.txt", fresh.value)
        assertFalse(fresh.isProject)

        val nested = box.resolve("src/main/kotlin/App.kt")
        assertEquals("/mnt/omp/photos/src/main/kotlin/App.kt", nested.value)
        assertEquals("src/main/kotlin/App.kt", nested.projectRelative)
        assertTrue(box.contains("src/main/kotlin/App.kt"))

        // Not a string: the path the class hands out is the one the filesystem acts on, and the
        // bytes land in the conversation folder the user can open in a file manager.
        assertEquals(nested.value, vfs.realpath(nested.value))
        vfs.mkdir("/mnt/omp/photos/out")
        vfs.writeBytes(fresh.value, "written by the agent".toByteArray())
        assertEquals(
            "written by the agent",
            vfs.readBytes("/mnt/omp/photos/out/new.txt").toString(Charsets.UTF_8),
        )
    }

    @Test
    fun aFromInsideTheProjectIsHonouredAndAFromOutsideItIsARefusal() {
        assertEquals("/mnt/omp/photos/notes.md", box.resolve("../notes.md", "/mnt/omp/photos/sub").value)
        // A `from` relative to the project is the same thing, and the name comes back relative to
        // the project rather than to the directory the tool happens to be in.
        val inSub = box.resolve("new.txt", "sub")
        assertEquals("/mnt/omp/photos/sub/new.txt", inSub.value)
        assertEquals("sub/new.txt", inSub.projectRelative)
        // A tool that walked into another conversation and kept the old `from` writes a refusal.
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("notes.md", "/mnt/omp/videos") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("x", "/etc") })
    }

    @Test
    fun theContainerItselfAndASiblingConversationAreBothRefused() {
        // The answer to "can the agent create a sibling folder in the container": no, and not by
        // any of the three routes — up one level, by absolute path, or by a name that lands there.
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("..") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("/mnt/omp") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("../videos") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("../brand-new") })
        assertFalse(box.contains("/mnt/omp"))
        assertNull(box.projectRelative("../videos/notes.md"))
        // And the container still holds exactly the two conversations this session found in it.
        assertEquals(listOf("photos", "videos"), vfs.readDir("/mnt/omp").map { it.name })
    }

    @Test
    fun aDotDotChainIsCollapsedTheWayTheKernelCollapsesItAndThenRefused() {
        for (raw in listOf(
            "sub/../..",
            "sub/../../notes.md",
            "a/b/../../../escape",
            "notes.md/../..",
        )) {
            assertEquals(raw, FsErrno.PERM_DENIED, errnoOf { box.resolve(raw) })
        }
        // The refusal names where the walk actually ended, not the string that was typed — which
        // is how a caller can tell a boundary answer from a shape answer.
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp", refused("sub/../.."))
        assertEquals(
            FsErrno.PERM_DENIED to "/etc/passwd",
            refused("/mnt/omp/photos/../../../etc/passwd"),
        )
    }

    @Test
    fun aRelativePathThatClimbsOutOfTheProjectIsRefusedAtTheTopOfTheClimb() {
        assertEquals(FsErrno.PERM_DENIED to "/mnt/etc/passwd", refused("../../etc/passwd"))
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp/videos", refused("../videos"))
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp/videos", refused("sub/../../videos"))
        assertEquals(
            FsErrno.PERM_DENIED to "/mnt/omp/videos/notes.md",
            refused("sub/../../videos/notes.md"),
        )
        assertFalse(box.contains("../../etc/passwd"))
        assertNull(box.projectRelative("../videos/notes.md"))
    }

    @Test
    fun anAbsolutePathOutsideTheContainerIsRefused() {
        for (raw in listOf(
            "/etc/passwd",
            "/mnt",
            "/storage/emulated/0/Documents/notes.md",
            "/mnt/omp/photos/../../../../etc/passwd",
        )) {
            assertEquals(raw, FsErrno.PERM_DENIED, errnoOf { box.resolve(raw) })
        }
        assertFalse(box.contains("/etc/passwd"))
    }

    @Test
    fun aSiblingOfTheContainerIsNotTheContainer() {
        // The mount table's longest-match rule means `/mnt/ompx` is a different directory that
        // exists nowhere near the conversations, and a check that compared strings with
        // `startsWith` would have waved this through.
        for (raw in listOf("/mnt/ompx/notes.md", "/mnt/om", "/mnt/ompphotos/notes.md")) {
            assertEquals(raw, FsErrno.PERM_DENIED, errnoOf { box.resolve(raw) })
        }
    }

    @Test
    fun aPathWithANulIsRefusedAsTheNameItIs() {
        // A NUL ends the string in every C API underneath it, so a name that carries one is a
        // name nobody can act on, and it must never reach the seam.
        assertEquals(
            FsErrno.PERM_DENIED to "notes\u0000.md",
            refused("notes\u0000.md"),
        )
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("/mnt/omp/photos/n\u0000") })
        assertFalse(box.contains("notes\u0000.md"))
    }

    @Test
    fun aPathWithANewlineIsRefused() {
        // The transcript is JSON Lines and every diagnostic is one line: a newline in a path is one
        // line of somebody's file and a broken line in ours.
        for (raw in listOf("notes\nmd", "notes\rmd", "sub/\n../notes.md")) {
            assertEquals(FsErrno.PERM_DENIED to raw, refused(raw))
        }
    }

    @Test
    fun aPathAtTheCapIsOursAndOneUnitPastItIsRefusedRatherThanCut() {
        val prefix = "/mnt/omp/photos/"
        val atCap = prefix + "n".repeat(Sandbox.MAX_PATH - prefix.length)
        assertEquals(Sandbox.MAX_PATH, atCap.length)
        val p = box.resolve(atCap)
        assertEquals(atCap, p.value)
        // A cut path is a different path, so the cap refuses; it never shortens one and hands the
        // write to a file the model did not name.
        val past = prefix + "n".repeat(Sandbox.MAX_PATH - prefix.length + 1)
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve(past) })
        assertFalse(box.contains(past))
    }

    @Test
    fun aPathOfNothingButDotsAndSlashesIsAPlaceAndNotAThing() {
        // These name the project, which is inside the boundary, so they are refused on their shape
        // rather than by the check: a caller that has not decided what it is acting on has nothing
        // to act on. The path in the refusal is the raw one, which is how a caller can tell.
        for (raw in listOf("", ".", "..", "./..", "../.", "/..", "/./..")) {
            assertEquals(FsErrno.PERM_DENIED to raw, refused(raw))
        }
        // A component beside one of them is a name, and a name is answerable.
        assertEquals("/mnt/omp/photos/notes.md", box.resolve("sub/../notes.md").value)
    }

    @Test
    fun aSpellingTheVolumeWouldFoldWithTheProjectIsStillRefused() {
        // exFAT behind Documents/omp: `Photos` and `photos` are one directory there, and `café`
        // typed as `e` + U+0301 is another spelling of the same one. Neither spelling is the one
        // the user gave, so neither is approved — a model that discovers the project answers to two
        // names does not thereby get a second one.
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("/mnt/omp/Photos/notes.md") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("../Photos/notes.md") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("../PHOTOS") })

        vfs.mkdir("/mnt/omp/caf\u00E9")
        val cafe = Sandbox(vfs, "/mnt/omp", "/mnt/omp/caf\u00E9")
        // The name as it was given, which is accepted: the refusals above are about the spelling,
        // not about the folder.
        assertEquals("/mnt/omp/caf\u00E9/notes.md", cafe.resolve("notes.md").value)
        assertEquals(FsErrno.PERM_DENIED, errnoOf { cafe.resolve("../cafe\u0301/notes.md") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { cafe.resolve("../CAF\u00C9/notes.md") })
        assertEquals(FsErrno.PERM_DENIED, errnoOf { cafe.resolve("/mnt/omp/cafe\u0301") })
        assertFalse(cafe.contains("../cafe\u0301/notes.md"))
    }

    @Test
    fun aPathWithALeadingDoubleSlashIsRefused() {
        // `//` is a path of its own in POSIX and every layer underneath collapses it differently.
        // Without this rule the first of these is the project and the second is `/etc/passwd`.
        for (raw in listOf("//mnt/omp/photos/notes.md", "//etc/passwd", "//..")) {
            assertEquals(FsErrno.PERM_DENIED to raw, refused(raw))
        }
    }

    @Test
    fun thisConversationsOwnMetadataFolderIsRefusedInEverySpellingAndUnderAnyOtherName() {
        // The one folder inside the project the agent was not given: the transcript it is replayed
        // from and the state file the next turn is run from. Every spelling a model can write is
        // here, and so is the shape that gets past a name check — a link to it under another name.
        vfs.mkdir("/mnt/omp/photos/.omp")
        vfs.writeBytes("/mnt/omp/photos/.omp/state.json", STATE.toByteArray())
        vfs.mkdir("/mnt/omp/photos/.ompx")
        for (raw in listOf(
            ".omp",
            ".omp/state.json",
            ".omp/transcript.jsonl",
            ".omp/x/y.json",
            "/mnt/omp/photos/.omp/state.json",
        )) {
            assertEquals(raw, FsErrno.PERM_DENIED, errnoOf { box.resolve(raw) })
            assertFalse(raw, box.contains(raw))
            assertNull(raw, box.projectRelative(raw))
        }
        // The refusal names the folder as the model would recognise it, not as the kernel would.
        assertEquals(FsErrno.PERM_DENIED to ".omp/state.json", refused(".omp/x/../state.json"))
        // And the file it would have written is still exactly what it was.
        assertEquals(STATE, vfs.readBytes("/mnt/omp/photos/.omp/state.json").toString(Charsets.UTF_8))
        // A folder whose name merely starts the same is the user's, not this app's.
        assertEquals("/mnt/omp/photos/.ompx/notes.md", box.resolve(".ompx/notes.md").value)
        // The name is answered as the model wrote it, which is what the refusal sentence is built
        // from, and a name in another conversation is not this conversation's business.
        assertTrue(box.ownMetadata(".omp"))
        assertTrue(box.ownMetadata(".omp/state.json"))
        assertTrue(box.ownMetadata("/mnt/omp/photos/.omp/x"))
        assertFalse(box.ownMetadata("notes.md"))
        assertFalse(box.ownMetadata("/mnt/omp/videos/.omp"))
        // A link written under another name leads here and is refused here, which is why the check
        // is made on the name the kernel will act on and not on the one that was typed.
        vfs.symlink("/mnt/omp/photos/.omp", "/mnt/omp/photos/cuentas")
        assertEquals("/mnt/omp/photos/.omp/state.json", vfs.realpath("/mnt/omp/photos/cuentas/state.json"))
        assertEquals(FsErrno.PERM_DENIED, errnoOf { box.resolve("cuentas/state.json") })
    }

    @Test
    fun aLinkToASiblingConversationIsFollowedAndTheTargetIsWhatIsChecked() {
        vfs.symlink("/mnt/omp/videos", "/mnt/omp/photos/out")
        // The link is a real one and it really points there, so the refusal below is the class
        // following it rather than a name this test made up.
        assertEquals("/mnt/omp/videos", vfs.realpath("/mnt/omp/photos/out"))
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp/videos/notes.md", refused("out/notes.md"))
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp/videos", refused("out"))
        assertFalse(box.contains("out/notes.md"))
        assertNull(box.projectRelative("out/notes.md"))
    }

    @Test
    fun aLinkOutOfTheContainerIsFollowedAndRefused() {
        // The kernel resolves a link against its own root, so this is the namespace's `/etc`, and
        // it is a directory the agent was never given whatever the phone's `/etc` looks like.
        vfs.symlink("/etc", "/mnt/omp/photos/etc")
        assertEquals("/etc/passwd", vfs.realpath("/mnt/omp/photos/etc/passwd"))
        assertEquals(FsErrno.PERM_DENIED to "/etc/passwd", refused("etc/passwd"))
        assertEquals(FsErrno.PERM_DENIED to "/etc", refused("etc"))
        assertFalse(box.contains("etc/passwd"))
    }

    @Test
    fun aDanglingLinkIsFollowedToWhereItPointsAndNotTakenAtFaceValue() {
        // Out, and it points at nothing: a name the check would otherwise accept.
        vfs.symlink("/mnt/omp/videos/gone.txt", "/mnt/omp/photos/dangling")
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp/videos/gone.txt", refused("dangling"))
        // In, and it points at a file that has not been written yet: still a real target, and
        // writing through it writes there rather than where the link is.
        vfs.symlink("/mnt/omp/photos/sub/new.txt", "/mnt/omp/photos/inside")
        val p = box.resolve("inside")
        assertEquals("/mnt/omp/photos/sub/new.txt", p.value)
        assertEquals("sub/new.txt", p.projectRelative)
        vfs.writeBytes(p.value, "through the link".toByteArray())
        assertEquals(
            "through the link",
            vfs.readBytes("/mnt/omp/photos/sub/new.txt").toString(Charsets.UTF_8),
        )
    }

    @Test
    fun aLinkThatStaysInsideStillComesBackDownIntoTheProject() {
        // The link leads two levels down, so the answer is not the one a lexical collapse would
        // have given: the kernel follows the link first and applies the `..` to where it landed.
        vfs.symlink("/mnt/omp/photos/deep/deeper", "/mnt/omp/photos/shortcut")
        val p = box.resolve("shortcut/../notes.md")
        assertEquals("/mnt/omp/photos/deep/notes.md", p.value)
        assertEquals("deep/notes.md", p.projectRelative)
        assertEquals("/mnt/omp/photos/deep/deeper", box.resolve("shortcut").value)
        assertEquals("/mnt/omp/photos/deep/deeper/notes.md", box.resolve("shortcut/notes.md").value)
    }

    @Test
    fun aClimbOutThroughALinkIsRefusedEvenWhenItWouldHaveLandedBackInside() {
        vfs.symlink("/mnt/omp/videos", "/mnt/omp/photos/away")
        // Lexically this is `notes.md` in the project, because the `..` cancels the link's name.
        // It is followed first, so it is `/mnt/omp/notes.md`, which is not ours. The two answers
        // about the boundary disagree and the disagreement is a refusal.
        assertEquals(FsErrno.PERM_DENIED to "/mnt/omp/notes.md", refused("away/../notes.md"))
    }

    @Test
    fun aLinkLoopInsideTheProjectIsReportedAsTheSeamReportsIt() {
        vfs.symlink("/mnt/omp/photos/loop-b", "/mnt/omp/photos/loop-a")
        vfs.symlink("/mnt/omp/photos/loop-a", "/mnt/omp/photos/loop-b")
        // A fact about the folder rather than a decision about the boundary, so it keeps its own
        // errno — and the two verdict callers still get an answer rather than an exception.
        assertEquals(FsErrno.SYMLINK_LOOP, errnoOf { box.resolve("loop-a") })
        assertFalse(box.contains("loop-a"))
        assertNull(box.projectRelative("loop-a"))
    }

    @Test
    fun aNamespaceThatOnlyAnswersRealpathForWhatExistsStillChecksTheParentOfANewFile() {
        val strict = ExistingOnlyVfs(vfs)
        val sandbox = Sandbox(strict, "/mnt/omp", "/mnt/omp/photos")
        // Nothing in this tree is there, so the walk has to stop at the project and re-attach
        // every name under it.
        val deep = sandbox.resolve("src/deep/new.txt")
        assertEquals("/mnt/omp/photos/src/deep/new.txt", deep.value)
        assertEquals("src/deep/new.txt", deep.projectRelative)
        // And the parent of a new file is a link out, so the walk that stops there finds the link
        // and the file lands outside the boundary.
        strict.symlink("/mnt/omp/videos", "/mnt/omp/photos/out")
        assertEquals(
            FsErrno.PERM_DENIED to "/mnt/omp/videos/new.txt",
            refused("out/new.txt", sandbox),
        )
    }

    @Test
    fun aNameTheWalkCouldNotFollowIsRefusedRatherThanTakenAtFaceValue() {
        vfs.symlink("/mnt/omp/photos/sub", "/mnt/omp/photos/shortcut")
        val sandbox = Sandbox(LinkBlindVfs(vfs), "/mnt/omp", "/mnt/omp/photos")
        // The link is a real one; this namespace just cannot say where it points.
        assertEquals("/mnt/omp/photos/sub", vfs.realpath("/mnt/omp/photos/shortcut"))
        // Under it, the ancestor resolved and the answer is a real one.
        assertEquals("/mnt/omp/photos/sub/notes.md", sandbox.resolve("shortcut/notes.md").value)
        // The link itself is not handed out: this class never saw the target, so the name is not
        // one it can answer for, even though the name itself is inside the project.
        assertEquals(FsErrno.PERM_DENIED, errnoOf { sandbox.resolve("shortcut") })
        assertFalse(sandbox.contains("shortcut"))
    }

    @Test
    fun aProjectOutsideTheContainerIsRefusedWhereItIsBuilt() {
        // Outside the container, a sibling of it, a name that is not a path at all, and the
        // container itself: the last one because a project that *is* the container would make
        // "the agent may not touch the container" a rule with an exception in it.
        for (bad in listOf("/etc", "/mnt/ompx", "photos", "/mnt/omp", "/mnt/omp/")) {
            try {
                Sandbox(vfs, "/mnt/omp", bad)
                fail("a project of '$bad' should not have been accepted")
            } catch (e: FsException) {
                assertEquals(bad, FsErrno.PERM_DENIED, e.errno)
            }
        }
        // A project inside the container is the one shape that is not a refusal.
        assertEquals("/mnt/omp/videos", Sandbox(vfs, "/mnt/omp", "/mnt/omp/videos").project)
    }

    @Test
    fun noArgumentTheModelCanProduceMakesThisClassNameAnythingOutsideTheProject() {
        vfs.symlink("/mnt/omp/videos", "/mnt/omp/photos/out")
        vfs.symlink("/mnt/omp/videos/gone.txt", "/mnt/omp/photos/dangling")
        val hostile = listOf(
            "", ".", "..", "../..", "../../etc/passwd", "/etc/passwd", "/mnt",
            "/mnt/ompx/notes.md", "/mnt/omp/Photos/notes.md", "/mnt/omp/videos/notes.md",
            "/mnt/omp", "/mnt/omp/photos/../../videos", "//mnt/omp/photos/notes.md",
            "notes\u0000.md", "notes\nmd", "a/b/../../../c", "out/notes.md", "out",
            "dangling", "sub/../../videos/notes.md", "sub/../../notes.md",
        )
        for (raw in hostile) {
            val answer = try {
                box.resolve(raw)
            } catch (e: FsException) {
                null
            }
            if (answer != null) fail("'$raw' was answered as ${answer.value}")
            assertFalse(raw, box.contains(raw))
            assertNull(raw, box.projectRelative(raw))
        }
        // The container is untouched by any of it: no sibling was created, and the two
        // conversations this session found are the two that are still there.
        assertEquals(listOf("photos", "videos"), vfs.readDir("/mnt/omp").map { it.name })
    }

    /** The errno a call refused with; a call that succeeds fails the test instead. */
    private fun errnoOf(body: () -> Unit): FsErrno = try {
        body()
        throw AssertionError("expected an FsException")
    } catch (e: FsException) {
        e.errno
    }

    /** The errno and the path a refusal names, or the test fails for answering. */
    private fun refused(raw: String, inBox: Sandbox = box): Pair<FsErrno, String?> = try {
        inBox.resolve(raw)
        throw AssertionError("expected a refusal for '$raw'")
    } catch (e: FsException) {
        e.errno to e.path
    }

    private companion object {
        /** A state file as the app writes it: the `base_url` is what makes writing one dangerous. */
        const val STATE = """{"provider":"openai","base_url":"https://elsewhere.test/v1","model":"x"}"""
    }
}
