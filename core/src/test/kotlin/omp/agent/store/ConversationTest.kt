package omp.agent.store

import omp.shell.ShellHarness
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The transcript, over a real [RealVfs] on a real directory, with `java.io.File` as the judge.
 *
 * The arrangement is the phone's: `Documents/omp/notes` on the device, bound at `/mnt/omp/notes` in
 * the namespace, and the conversation written through the seam. Every assertion about damage is
 * made by writing the damaged bytes with a plain `File` and reading them back through the same
 * code an agent would use — because "recovers from a partial line" is a claim about a file on a
 * disk, and a test that produced the damage through the same writer could not tell a recovery from
 * a coincidence.
 */
class ConversationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var ompDir: File
    private lateinit var project: File
    private lateinit var vfs: Vfs
    private lateinit var convo: Conversation

    @Before
    fun setUp() {
        ompDir = File(folder.newFolder("Documents"), "omp").apply { mkdirs() }
        project = File(ompDir, "notes").apply { mkdirs() }
        vfs = RealVfs("", listOf("/mnt/omp" to ompDir.path))
        convo = Conversation(vfs, "/mnt/omp/notes")
    }

    /** The transcript as the filesystem really holds it, not as the class last remembered it. */
    private fun transcriptFile(): File = File(project, ".omp/transcript.jsonl")

    // ---- the round trip -----------------------------------------------------------------

    @Test
    fun appendThenReadComesBackInOrderWithItsOffsets() {
        assertEquals(0L, convo.append(Kind.USER, "what is in this folder?"))
        convo.append(Kind.ASSISTANT, "three files and a readme")
        convo.append(Kind.TOOL_RESULT, "notes.md: 1 match", mapOf("tool" to "grep", "status" to "0"))

        val entries = convo.read()
        assertEquals(
            listOf(Kind.USER, Kind.ASSISTANT, Kind.TOOL_RESULT),
            entries.map { it.kind },
        )
        assertEquals("what is in this folder?", entries[0].content)
        assertEquals("three files and a readme", entries[1].content)
        assertEquals(mapOf("tool" to "grep", "status" to "0"), entries[2].extra)
        assertEquals(emptyMap<String, String>(), entries[0].extra)

        // The offsets are the file's, not a running total this class kept in memory: each one
        // addresses the bytes a reader holding nothing else can go and read.
        val lines = transcriptFile().readBytes().toString(Charsets.UTF_8).split("\n").dropLast(1)
        val at = ArrayList<Long>()
        val lengths = ArrayList<Long>()
        var running = 0L
        for (line in lines) {
            at += running
            val bytes = line.toByteArray(Charsets.UTF_8).size.toLong()
            lengths += bytes + 1L
            running += bytes + 1L
        }
        assertEquals(at, entries.map { it.offset })
        assertEquals(lengths, entries.map { it.byteLength })
        assertEquals(transcriptFile().length(), running)
    }

    @Test
    fun oneObjectPerLineSoATextEditorCanReadTheTranscript() {
        convo.append(Kind.USER, "hello")
        convo.append(Kind.ASSISTANT, "hi")
        val lines = transcriptFile().readLines()
        assertEquals(2, lines.size)
        assertEquals("""{"role":"user","content":"hello"}""", lines[0])
        assertEquals("""{"role":"assistant","content":"hi"}""", lines[1])
    }

    @Test
    fun contentThatWouldBreakTheLineFormatIsEscapedNotDropped() {
        convo.append(Kind.USER, "line one\nline two\ttabbed")
        // One line, still, and it comes back with the newline and the tab in it.
        assertEquals(1, transcriptFile().readLines().size)
        assertEquals("line one\nline two\ttabbed", convo.read().single().content)
    }

    @Test
    fun aConversationNobodyHasSpokenInReadsAsEmpty() {
        assertEquals(emptyList<Entry>(), convo.read())
        assertEquals(0, convo.transcript().skippedCount)
        assertFalse(transcriptFile().exists())
    }

    // ---- damage -------------------------------------------------------------------------

    @Test
    fun aTrailingPartialLineIsSkippedAndTheCompleteOnesStillComeBack() {
        convo.append(Kind.USER, "first")
        convo.append(Kind.ASSISTANT, "second")
        val good = transcriptFile().readBytes().size.toLong()

        // Exactly what a process killed mid-write leaves behind: a line with no terminator.
        File(transcriptFile().path).appendBytes("""{"role":"assistant","conte""".toByteArray())

        val t = convo.transcript()
        assertEquals(listOf("first", "second"), t.entries.map { it.content })
        assertEquals(1, t.skippedCount)
        assertEquals(good, t.skipped.single().offset)
        assertTrue(t.skipped.single().reason, t.skipped.single().reason.contains("incomplete"))
    }

    @Test
    fun aLineThatIsNotJsonIsSkippedWithItsOffset() {
        convo.append(Kind.USER, "first")
        val firstLen = transcriptFile().length()
        File(transcriptFile().path).appendBytes("this is not json\n".toByteArray())
        convo.append(Kind.ASSISTANT, "third")

        val t = convo.transcript()
        assertEquals(listOf("first", "third"), t.entries.map { it.content })
        assertEquals(1, t.skippedCount)
        val skip = t.skipped.single()
        assertEquals(firstLen, skip.offset)
        assertEquals("this is not json\n".length.toLong(), skip.byteLength)
        assertTrue(skip.reason, skip.reason.contains("not JSON"))
    }

    @Test
    fun aLineWhoseRoleThisBuildDoesNotKnowIsSkipped() {
        convo.append(Kind.USER, "first")
        val firstLen = transcriptFile().length()
        // A transcript from a newer app, with a kind this one has no branch for. Reading it as a
        // system message would be a guess; skipping it with its offset is an answer.
        transcriptFile().appendBytes("""{"role":"reviewer","content":"looks fine"}""".toByteArray() + "\n".toByteArray())

        val t = convo.transcript()
        assertEquals(listOf("first"), t.entries.map { it.content })
        assertEquals(firstLen, t.skipped.single().offset)
        assertTrue(t.skipped.single().reason, t.skipped.single().reason.contains("unknown role"))
    }

    @Test
    fun appendingAfterACutWriteKeepsBothTheOldEntriesAndTheNewOne() {
        convo.append(Kind.USER, "first")
        val partial = """{"role":"assistant","conte"""
        File(transcriptFile().path).appendBytes(partial.toByteArray())
        val before = convo.transcript().skipped.single().offset

        val at = convo.append(Kind.ASSISTANT, "recovered")

        // The new line starts after the one byte that terminated the wreck, and the wreck is still
        // there to be reported rather than having been spliced into it.
        assertEquals(before + partial.length + 1, at)
        val t = convo.transcript()
        assertEquals(listOf("first", "recovered"), t.entries.map { it.content })
        assertEquals(1, t.skippedCount)
        assertTrue(t.skipped.single().reason, t.skipped.single().reason.contains("not JSON"))
    }

    // ---- where the file lives ------------------------------------------------------------

    @Test
    fun theBookkeepingIsInAHiddenDirectoryAndNotInTheProjectListing() {
        File(project, "README.md").writeText("notes\n", Charsets.UTF_8)
        File(project, "src").mkdirs()
        convo.append(Kind.USER, "hello")
        AgentState(vfs, "/mnt/omp/notes").write(AgentConfig(provider = "anthropic"))

        // The project folder holds the project and one hidden directory, and nothing else: no
        // transcript, no state file, no scratch.
        assertEquals(
            listOf(".omp", "README.md", "src"),
            project.list()!!.sorted(),
        )
        assertEquals(
            listOf("state.json", "transcript.jsonl"),
            File(project, ".omp").list()!!.sorted(),
        )

        // And the listing a user runs shows the project: `tree` leaves dot names out unless asked.
        val harness = ShellHarness(folder.root)
        assertEquals(0, harness.run("tree ${project.path}"))
        assertFalse(harness.stdout(), harness.stdout().contains(".omp"))
        assertTrue(harness.stdout(), harness.stdout().contains("README.md"))
        assertTrue(harness.stdout(), harness.stdout().contains("src"))
        harness.reset()
        assertEquals(0, harness.run("tree -a ${project.path}"))
        assertTrue(harness.stdout(), harness.stdout().contains(".omp"))
    }

    // ---- the cap ------------------------------------------------------------------------

    @Test
    fun theCapIsEnforcedWithAMessageAndTruncatesNothing() {
        // A megabyte a line, so the cap is reached in eight appends rather than eight thousand.
        val chunk = "x".repeat(1024 * 1024)
        repeat(7) { convo.append(Kind.TOOL_RESULT, chunk) }
        val before = transcriptFile().length()

        try {
            convo.append(Kind.TOOL_RESULT, chunk)
            fail("expected a TranscriptFullException")
        } catch (e: TranscriptFullException) {
            assertEquals(convo.path, e.path)
            assertEquals(before, e.bytes)
            assertEquals(Conversation.MAX_TRANSCRIPT_BYTES, e.limit)
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("nothing was truncated"))
        }
        // Refused, not truncated: every entry that was written is still there.
        assertEquals(before, transcriptFile().length())
        assertEquals(7, convo.read().size)
    }

    // ---- clear ---------------------------------------------------------------------------

    @Test
    fun clearSaysHowManyEntriesWentAndLeavesTheProjectAlone() {
        File(project, "README.md").writeText("notes\n", Charsets.UTF_8)
        val state = AgentState(vfs, "/mnt/omp/notes")
        state.write(AgentConfig(provider = "anthropic", model = "claude"))
        convo.append(Kind.USER, "one")
        convo.append(Kind.ASSISTANT, "two")
        convo.append(Kind.TOOL_RESULT, "three")

        assertEquals(3, convo.clear())
        assertEquals(emptyList<Entry>(), convo.read())
        assertFalse(transcriptFile().exists())
        // This is a reset of the conversation, not of the project: the files and the configuration
        // beside it are not this class's to delete.
        assertTrue(File(project, "README.md").isFile)
        assertEquals(AgentConfig(provider = "anthropic", model = "claude"), state.read().config)
    }

    @Test
    fun clearOnAConversationThatNeverSpokeIsZeroAndNotAFailure() {
        assertEquals(0, convo.clear())
        assertEquals(0, convo.clear())
    }

    // ---- the refusals --------------------------------------------------------------------

    @Test
    fun anExtraFieldMayNotTakeTheRoleOrTheContent() {
        try {
            convo.append(Kind.USER, "hi", mapOf("role" to "system"))
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("role"))
        }
        assertFalse(transcriptFile().exists())
    }

    @Test
    fun aTranscriptPathThatIsADirectoryFailsTheWayTheSeamFails() {
        // Not a defensive test: the same errno a user gets from a folder they made by hand, and the
        // message the class lets through unchanged.
        File(project, ".omp/transcript.jsonl").apply { parentFile.mkdirs(); mkdirs() }
        try {
            convo.append(Kind.USER, "hi")
            fail("expected an FsException")
        } catch (e: FsException) {
            assertEquals(FsErrno.IS_A_DIRECTORY, e.errno)
            assertNotNull(e.path)
        }
    }
}
