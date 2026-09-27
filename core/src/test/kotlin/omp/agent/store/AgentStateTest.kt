package omp.agent.store

import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The per-conversation state file, over a real [RealVfs] and judged by `java.io.File`.
 *
 * Every interesting case here is a file somebody else wrote — a newer build, a text editor, a
 * process that died between the scratch write and the rename — so the test writes those files by
 * hand, in the exact bytes, rather than asking this class to produce them and then checking that
 * it can read back what it wrote.
 */
class AgentStateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var ompDir: File
    private lateinit var project: File
    private lateinit var vfs: Vfs
    private lateinit var state: AgentState

    @Before
    fun setUp() {
        ompDir = File(folder.newFolder("Documents"), "omp").apply { mkdirs() }
        project = File(ompDir, "notes").apply { mkdirs() }
        vfs = RealVfs("", listOf("/mnt/omp" to ompDir.path))
        state = AgentState(vfs, "/mnt/omp/notes")
    }

    /** The state file as the filesystem holds it, written by hand when it is not this build's. */
    private fun stateFile(): File = File(project, ".omp/state.json")

    private fun put(text: String) {
        stateFile().apply { parentFile.mkdirs() }.writeText(text, Charsets.UTF_8)
    }

    // ---- the round trip -----------------------------------------------------------------

    @Test
    fun noStateFileIsTheDefaultsAndNotAFailure() {
        val found = state.read()
        assertEquals(StateStatus.ABSENT, found.status)
        assertEquals(AgentConfig(), found.config)
        assertFalse(found.config.isConfigured)
        assertTrue(found.message, found.message.contains(state.path))
        assertFalse(stateFile().exists())
    }

    @Test
    fun aRoundTripPreservesTheProviderTheBaseUrlAndTheModel() {
        val config = AgentConfig(
            provider = "anthropic",
            baseUrl = "https://api.example.test/v1",
            model = "claude-opus-5",
        )
        state.write(config)

        val found = state.read()
        assertEquals(StateStatus.LOADED, found.status)
        assertEquals(config, found.config)
        assertTrue(found.config.isConfigured)
        assertTrue(found.message, found.message.contains(AgentState.FORMAT_VERSION.toString()))

        // The file itself, with java.io.File as the judge: a version key first, the three fields,
        // and no scratch left behind by the rename.
        val onDisk = stateFile()
        assertTrue(onDisk.path, onDisk.isFile)
        val text = onDisk.readText(Charsets.UTF_8)
        assertTrue(text, text.startsWith("""{"version":${AgentState.FORMAT_VERSION},"""))
        assertTrue(text, text.contains(""""provider":"anthropic""""))
        assertTrue(text, text.contains(""""base_url":"https://api.example.test/v1""""))
        assertTrue(text, text.contains(""""model":"claude-opus-5""""))
        assertEquals(listOf("state.json"), File(project, ".omp").list()!!.sorted())
    }

    @Test
    fun aConfigurationThatWasNeverChosenComesBackAsNullAndNotAsEmptyText() {
        state.write(AgentConfig(provider = "openai"))
        assertTrue(stateFile().readText(Charsets.UTF_8).contains(""""base_url":null"""))
        assertEquals(AgentConfig(provider = "openai"), state.read().config)
    }

    // ---- the versions -------------------------------------------------------------------

    @Test
    fun aStateFileFromANewerVersionIsReportedAndNotGuessedAt() {
        put("""{"version":9,"provider":"future","base_url":"https://x.test","model":"future-1"}""")

        val found = state.read()
        assertEquals(StateStatus.FUTURE_VERSION, found.status)
        // The defaults, not the three fields: those keys may mean something else in version 9, and
        // a provider guessed at here is a request the user never configured.
        assertEquals(AgentConfig(), found.config)
        assertTrue(found.message, found.message.contains("version 9"))
        assertTrue(found.message, found.message.contains(AgentState.FORMAT_VERSION.toString()))
    }

    @Test
    fun aStateFileFromANewerVersionIsNotOverwritten() {
        put("""{"version":9,"provider":"future","model":"future-1"}""")
        val before = stateFile().readBytes()

        try {
            state.write(AgentConfig(provider = "anthropic"))
            fail("expected a refusal to write over a newer state file")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("version 9"))
        }
        assertArrayEquals("the newer state file must be untouched", before, stateFile().readBytes())
        assertEquals(StateStatus.FUTURE_VERSION, state.read().status)
    }

    @Test
    fun aStateFileFromAnOlderVersionIsStillRead() {
        put("""{"version":0,"provider":"anthropic"}""")

        val found = state.read()
        assertEquals(StateStatus.LOADED, found.status)
        assertEquals(AgentConfig(provider = "anthropic"), found.config)
        assertTrue(found.message, found.message.contains("older"))
    }

    @Test
    fun aStateFileThatIsNotOneOfOursIsReportedAndLeftAlone() {
        put("""{"provider":"anthropic","model":"hand-written"}""")
        val before = stateFile().readBytes()

        val found = state.read()
        assertEquals(StateStatus.MALFORMED, found.status)
        assertEquals(AgentConfig(), found.config)
        assertTrue(found.message, found.message.contains(AgentState.VERSION))
        // Reading is not repairing: a file a person edited is theirs until somebody overwrites it.
        assertArrayEquals("a malformed state file must be left alone", before, stateFile().readBytes())
    }

    @Test
    fun aTruncatedStateFileIsReportedRatherThanHalfRead() {
        put("""{"version":1,"provider":"anthropic","mo""")

        val found = state.read()
        assertEquals(StateStatus.MALFORMED, found.status)
        assertEquals(AgentConfig(), found.config)
    }

    @Test
    fun aStateFileThatCannotBeReadIsUnreadableAndNotBrokenSettings() {
        // A directory where the file should be: something outside this app did it, and the answer
        // has to be "cannot read", not "these settings are malformed".
        stateFile().apply { parentFile.mkdirs(); mkdirs() }

        val found = state.read()
        assertEquals(StateStatus.UNREADABLE, found.status)
        assertEquals(AgentConfig(), found.config)
        assertTrue(found.message, found.message.contains("cannot read"))
    }

    // ---- the write discipline ------------------------------------------------------------

    @Test
    fun aWriteInterruptedBeforeTheRenameLeavesThePreviousStateIntact() {
        state.write(AgentConfig(provider = "anthropic", model = "claude-opus-5"))
        val good = stateFile().readBytes()

        // Exactly what a process killed between the scratch write and the rename leaves behind.
        File(project, ".omp/state.json.tmp").writeText("""{"version":1,"provider":"half""", Charsets.UTF_8)

        val found = state.read()
        assertEquals(StateStatus.LOADED, found.status)
        assertEquals(AgentConfig(provider = "anthropic", model = "claude-opus-5"), found.config)
        assertArrayEquals("the previous state must be intact", good, stateFile().readBytes())

        // And the next write is the one that cleans it up: the scratch is overwritten, never read.
        state.write(AgentConfig(provider = "openai", model = "gpt-5"))
        assertEquals(AgentConfig(provider = "openai", model = "gpt-5"), state.read().config)
        assertEquals(listOf("state.json"), File(project, ".omp").list()!!.sorted())
    }

    @Test
    fun theStateAndTheTranscriptShareOneHiddenDirectory() {
        val convo = Conversation(vfs, "/mnt/omp/notes")
        convo.append(Kind.USER, "hello")
        state.write(AgentConfig(provider = "anthropic"))

        assertEquals(
            listOf("state.json", "transcript.jsonl"),
            File(project, ".omp").list()!!.sorted(),
        )
        assertEquals(convo.metaDir, state.path.substringBeforeLast('/'))
        assertEquals("/mnt/omp/notes/.omp/transcript.jsonl", convo.path)
        assertEquals("/mnt/omp/notes/.omp/state.json", state.path)
    }
}
