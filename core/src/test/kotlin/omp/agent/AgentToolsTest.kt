package omp.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import omp.agent.json.Json
import omp.agent.store.AgentConfig
import omp.agent.store.AgentState
import omp.agent.store.Conversation
import omp.agent.store.Kind
import omp.agent.store.KeyStore
import omp.agent.tools.Tools
import omp.shell.HttpRequests
import omp.shell.HttpStream
import omp.shell.ScreenOutput
import omp.shell.SseReader
import omp.vm.VmHarness
import omp.vm.VmPhone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The agent's tools, driven the way a user drives them, over a real socket.
 *
 * **The assertion that matters is the cross-namespace one.** A file the model asked for and the
 * user approved has to turn up in `Documents/omp/<conversation>` as an ordinary file on the phone's
 * own filesystem — read here with [java.io.File] and through the shell's `cat`, never only through
 * the [omp.shell.fs.Vfs] the tools themselves act on. A test that went through the Vfs could not
 * tell a bind from a copy, and the whole promise of this feature is that the bytes are in a folder
 * the user can open, cable out, and delete with a file manager.
 *
 * **The rest is the boundary, the question and the transcript.** Every refusal is a tool result
 * the model reads, never an exception out of the loop; every write is a question the user answers
 * with one keypress; and the transcript says which of the two happened, so a folder read
 * afterwards can tell a silent write from an approved one.
 *
 * The endpoint is a real `com.sun.net.httpserver.HttpServer` and the transport is a real
 * [java.net.HttpURLConnection] ([omp.shell.HttpRequests] + [SseReader]): the interesting facts are
 * all in the bytes — a tool call split across two writes, a role-only first chunk — and no mock
 * has any of them.
 */
class AgentToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var harness: VmHarness
    private lateinit var phone: VmPhone
    private lateinit var server: HttpServer
    private lateinit var container: File

    private val scripts = Collections.synchronizedList(ArrayList<Script>())
    private val requests = Collections.synchronizedList(ArrayList<Request>())

    /** Distinctive enough that a substring search for it cannot pass by accident. */
    private val secret = "sk-omp-3B8nQ2wR7tY1uI9o"

    private class Script(val chunks: List<Chunk>)

    /** One TCP write: [text] goes out, then a flush, then a pause long enough to be its own packet. */
    private class Chunk(val text: String, val pauseMillis: Long = 25)

    private class Request(val url: String, val method: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    @Before
    fun setUp() {
        harness = VmHarness(folder.root)
        phone = VmPhone(harness.services)
        container = File(harness.externalDir!!, "Documents/omp")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v1/chat/completions") { serve(it) }
        server.start()
        harness.stub.stream = { url, method, headers, body ->
            requests += Request(
                url,
                method,
                headers.associate { (k, v) -> k to v },
                body?.toString(StandardCharsets.UTF_8) ?: "",
            )
            val conn = HttpRequests.open(url, method, headers, body, CONNECT_MS, READ_MS)
            val lines = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            val events = SseReader(
                { lines.readLine() },
                { runCatching { lines.close() }; conn.disconnect() },
            )
            object : HttpStream {
                override val status: Int = HttpRequests.status(conn, url)
                override fun next(): String? = events.next()
                override fun close() = events.close()
            }
        }
        // What the shell does for a REPL: a Ctrl-C byte cancels the foreground job. Without this
        // there is no Ctrl-C in a test, and the one that matters is the one at an approval prompt.
        phone.input.onInterrupt = { phone.shell.session.foreground?.cancel() }
    }

    @After
    fun tearDown() {
        server.stop(0)
        (server.executor as ExecutorService).shutdownNow()
    }

    /**
     * The endpoint every OpenAI-compatible service is, including the two rules about a tool
     * exchange: a `tool` message must answer a call, and a call must be answered.
     *
     * **It is a 400 rather than a shrug, because that is what a real endpoint does**, and because
     * a stub that answers everything proves nothing about a request's shape: a session whose
     * history cannot be sent would look exactly like one that worked. It checks the *shape* — the
     * count of calls against the count of results — and not whether the ids match, because a
     * transcript a text editor has been through can carry a result whose id was lost and the app
     * sends one anyway, by design and by its own KDoc.
     */
    private fun serve(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val unpaired = unpairable(body)
        if (unpaired != null) {
            val answer = """{"error":{"message":"$unpaired"}}"""
            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
            return
        }
        val script = synchronized(scripts) { scripts.removeAt(0) }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        // 0 is chunked, which is what a 200 SSE response is.
        exchange.sendResponseHeaders(200, 0L)
        val out = exchange.responseBody
        for (chunk in script.chunks) {
            out.write(chunk.text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            if (chunk.pauseMillis > 0) Thread.sleep(chunk.pauseMillis)
        }
        out.close()
    }

    /** @return what a real endpoint would refuse this body for, or null when it is a shape it takes. */
    private fun unpairable(body: String): String? {
        val messages = ((Json.parse(body) as? Json.Obj)?.arr("messages")) ?: return null
        var unanswered = 0
        for (message in messages) {
            val one = message as? Json.Obj ?: return "a message is not an object"
            when (one.str("role")) {
                "assistant" -> unanswered = one.arr("tool_calls")?.size ?: 0
                "tool" -> {
                    if (unanswered == 0) return "a tool message answers no call"
                    unanswered--
                }

                else -> if (unanswered > 0) return "an assistant asked for tools nothing answered"
            }
        }
        return if (unanswered > 0) "an assistant asked for tools nothing answered" else null
    }

    // ---- a write the user said yes to ---------------------------------------------------------

    /**
     * A `write_file` the user approved with `y` lands in the conversation folder, as a real file on
     * the phone, visible through the shell and not only through the seam the tools act on.
     *
     * The call is split across two chunks on purpose: the name in one and the arguments in the
     * next, which is what every endpoint does and what a reader that stopped on the first
     * `tool_calls` delta would turn into half a call.
     */
    @Test
    fun aWriteFileApprovedWithYLandsInTheConversationFolderOnThePhone() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCallChunk(0, "write_file", null, "")),
            Chunk(toolCallChunk(0, null, "{\"path\":\"hola.txt\",\"content\":\"hola\\n\"}")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("He escrito hola.txt.")), Chunk(done()))

        val status = converse(
            Turn("write hola.txt", "approve?", answer = "y"),
            Turn("", "He escrito hola.txt."),
        )

        assertEquals(130, status)
        // The cross-namespace assertion: the phone's own filesystem, with java.io.File.
        val onDisk = File(project, "hola.txt")
        assertTrue("nothing was written to ${project.path}", onDisk.exists())
        assertEquals("hola\n", onDisk.readText())
        // And through the shell, in the namespace the user types in.
        assertEquals(0, phone.run("cat hola.txt").status)
        assertTrue(phone.run("cat hola.txt").out.contains("hola"))

        val screen = phone.screenText()
        // The approval said what was about to happen: the path in the conversation, the real path
        // on the phone, and the size. A question a user cannot answer is not an approval.
        assertTrue("no real path in the approval:\n$screen", screen.contains(File(project, "hola.txt").path))
        assertTrue("no size in the approval:\n$screen", screen.contains("5 bytes"))
        assertTrue("nothing was written out loud:\n$screen", screen.contains("wrote hola.txt"))

        // The transcript says the user said yes — not that it happened silently.
        val result = toolResult()
        assertEquals("wrote hola.txt: 5 bytes.", result.content)
        assertEquals("write_file", result.extra["tool"])
        assertEquals("y", result.extra["approved"])
    }

    /**
     * The same call, declined, leaves nothing on disk and tells the model it was declined.
     *
     * The refusal has to reach the *model* and not only the screen: a model that was told nothing
     * and simply saw no file would conclude the write had worked, and the next thing it says about
     * the file would be a thing about a file that is not there.
     */
    @Test
    fun theSameCallDeclinedLeavesNoFileAndTheModelIsToldItWasDeclined() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "write_file", """{"path":"hola.txt","content":"hola\n"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Entendido, no escribo nada.")), Chunk(done()))

        converse(
            Turn("write hola.txt", "approve?", answer = "n"),
            Turn("", "Entendido"),
        )

        assertFalse("a declined write put a file on the phone", File(project, "hola.txt").exists())
        val result = toolResult()
        assertTrue("the model was not told it was declined:\n${result.content}", result.content.contains("declined"))
        assertEquals("declined", result.extra["approved"])
        // And the next request carried that sentence to the model, in a `tool` message paired with
        // the call that produced it.
        val sent = messagesOf(requests.last().body)
        val answered = sent.first { it.str("role") == "tool" }
        assertTrue(answered.str("content")!!.contains("declined"))
        val asked = sent.first { it.field("tool_calls") != null }
        val callId = (asked.arr("tool_calls")!!.first() as Json.Obj).str("id")
        assertEquals(callId, (answered as Json.Obj).field("tool_call_id").let { (it as Json.Str).value })
    }

    /**
     * `omp --yes` approves without asking, and says so on the screen and in the transcript.
     *
     * The point of a separate value is that a folder read afterwards must not be able to mistake
     * a write nobody looked at for one a person did.
     */
    @Test
    fun ompYesApprovesEveryCallWithoutAskingAndRecordsThatItDid() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "write_file", """{"path":"auto.txt","content":"x\n"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("done")), Chunk(done()))

        val status = converse(
            Turn("write auto.txt", "done", yes = true),
        )

        assertEquals(130, status)
        assertEquals("x\n", File(project, "auto.txt").readText())
        val screen = phone.screenText()
        assertTrue("the approval was not said to be automatic:\n$screen", screen.contains("without asking"))
        assertFalse("--yes still asked:\n$screen", screen.contains("approve?"))
        assertEquals("auto", toolResult().extra["approved"])
    }

    // ---- the boundary -------------------------------------------------------------------------

    /** A path outside the conversation is refused, and the model is handed the refusal. */
    @Test
    fun aPathOutsideTheConversationIsRefusedAndTheModelIsTold() {
        val project = open("notas")
        val other = open("fotos")
        configure(project)
        storeKey(project)
        File(other, "privado.txt").writeText("not yours")
        script(
            Chunk(toolCall(0, "read_file", """{"path":"../fotos/privado.txt"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("No puedo.")), Chunk(done()))

        converse(
            Turn("read the other one", "No puedo."),
        )

        val result = toolResult()
        assertEquals("read_file", result.extra["tool"])
        assertTrue("the refusal did not say it was one:\n${result.content}", result.content.startsWith("refused:"))
        assertTrue(result.content.contains("Permission denied"))
        // The next conversation in the folder still has its file: the refusal happened before any
        // read, which is the only order in which it is a boundary rather than a warning.
        assertEquals("not yours", File(other, "privado.txt").readText())
    }

    /**
     * A read of a file outside the container is refused, and so is a read of the container itself.
     *
     * The second half is the stricter of the two possible answers and it is the one this test is
     * here for: one folder was granted, so one folder is the answer, and listing the user's other
     * conversations is a capability the agent was not given.
     */
    @Test
    fun aReadOutsideTheContainerIsRefusedAndSoIsTheContainerItself() {
        val project = open("notas")
        val other = open("fotos")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "read_file", """{"path":"${other.path}"}""")),
            Chunk(toolCall(1, "list_dir", """{"path":".."}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("No.")), Chunk(done()))

        converse(Turn("look around", "No."))

        val results = toolResults()
        assertEquals(2, results.size)
        for (result in results) {
            assertTrue("not refused:\n${result.content}", result.content.contains("Permission denied"))
        }
        assertTrue(results[1].content.contains("not even a read of the conversations folder"))
    }

    /**
     * The conversation's own bookkeeping is refused, in every spelling, for both tools that change
     * the disk — and the two files in it are byte-for-byte what they were.
     *
     * A model that could write `.omp/state.json` has the **next** request sent to a `base_url` of
     * its own choosing, through the same endpoint check, with a user who approved what the prompt
     * called "editing a config file in your project". The prompt cannot carry that warning: it
     * names a path, and a path called `.omp/state.json` looks like bookkeeping a person does. So
     * the boundary is the only place that can answer it, and it answers once for both tools.
     */
    @Test
    fun theConversationsOwnMetadataFolderIsNotTheModelsToWrite() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        val state = File(project, ".omp/state.json")
        val transcript = transcriptFile()
        val stateBefore = state.readBytes()
        // An earlier exchange in the transcript, so "unchanged" can be said of the transcript too
        // and not only of the state file: these are the bytes the session is about to append to.
        val earlier = Conversation(phone.shell.session.vfs, project.path)
        earlier.append(Kind.USER, "hola")
        earlier.append(Kind.ASSISTANT, "antes de nada")
        val transcriptBefore = transcript.readBytes()
        val spellings = listOf(
            ".omp",
            ".omp/state.json",
            ".omp/transcript.jsonl",
            ".omp/x/y.json",
            // The absolute form, spelled the way this namespace spells the conversation: on the
            // phone the shared-storage path *is* the real one, so this is the one a model sends.
            "${File(project, ".omp").path}/state.json",
        )
        val calls = ArrayList<Chunk>()
        for ((at, spelling) in spellings.withIndex()) {
            calls += Chunk(
                toolCall(at, "write_file", """{"path":"$spelling","content":"$HIJACKED"}""", "call_$at"),
            )
        }
        for ((at, spelling) in spellings.withIndex()) {
            calls += Chunk(
                toolCall(
                    at + spellings.size,
                    "edit_file",
                    """{"path":"$spelling","old":"provider","new":"openai"}""",
                    "call_e$at",
                ),
            )
        }
        script(*calls.toTypedArray(), Chunk(finishTools()), Chunk(done()))
        script(Chunk(delta("No toco eso.")), Chunk(done()))

        converse(Turn("aim the next request at an endpoint you like", "No toco eso."))

        val results = toolResults()
        assertEquals(spellings.size * 2, results.size)
        for (result in results) {
            assertTrue("not refused:\n${result.content}", result.content.startsWith("refused:"))
            assertTrue(
                "the refusal did not say what the folder is:\n${result.content}",
                result.content.contains("this conversation's own .omp folder"),
            )
            assertTrue(
                "the refusal did not say who it belongs to:\n${result.content}",
                result.content.contains("is yours to read or write"),
            )
        }
        // Nobody was asked: the path is refused before the question, so there was nothing to say yes to.
        assertFalse(
            "an approval was asked for a write that could not happen:\n${phone.screenText()}",
            phone.screenText().contains("approve?"),
        )
        assertTrue("the state file was rewritten", stateBefore.contentEquals(state.readBytes()))
        val transcriptAfter = transcript.readBytes()
        assertTrue(
            "the transcript this session was replaying from was rewritten",
            transcriptAfter.size > transcriptBefore.size &&
                transcriptBefore.contentEquals(transcriptAfter.copyOf(transcriptBefore.size)),
        )
        assertFalse("a folder appeared inside the metadata folder", File(project, ".omp/x").exists())
    }

    /**
     * The folder is listed because it is there, and refused when a tool is sent into it.
     *
     * Both halves are decisions and this is the test for each. Hiding `.omp` from a listing of the
     * conversation would be a claim about the filesystem that the filesystem does not support — the
     * user opens this same folder in a file manager and sees it. Refusing the model's read of it is
     * the answer [omp.agent.tools.Search] already gives about searching it, and it is a *read* the
     * app declines: the transcript is this conversation in the model's own words. The decision lives
     * in the boundary's KDoc, and the verdict is the same one a refused write gets, because a
     * verdict that depended on which tool asked for it would be two verdicts.
     */
    @Test
    fun theMetadataFolderIsListedBecauseItIsThereAndNotOpenedBecauseItIsNotTheModels() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "list_dir", "{}", "call_0")),
            Chunk(toolCall(1, "list_dir", """{"path":".omp"}""", "call_1")),
            Chunk(toolCall(2, "read_file", """{"path":".omp/transcript.jsonl"}""", "call_2")),
            Chunk(toolCall(3, "read_file", """{"path":".omp/state.json"}""", "call_3")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Listo.")), Chunk(done()))

        converse(Turn("abre esto $MARKER", "Listo."))

        val results = toolResults()
        assertEquals(4, results.size)
        assertTrue(
            "the listing hid a folder that is really there:\n${results[0].content}",
            results[0].content.contains(".omp"),
        )
        for (result in results.drop(1)) {
            assertTrue(
                "the read was not refused:\n${result.content}",
                result.content.contains("this conversation's own .omp folder"),
            )
            // The question is in the transcript, so a transcript handed back would carry it.
            assertTrue("the transcript came back anyway:\n${result.content}", !result.content.contains(MARKER))
        }
    }

    // ---- refusing rather than guessing --------------------------------------------------------

    /**
     * An `edit_file` whose `old` is not in the file is refused, and nothing is written.
     *
     * The alternative — inserting the text, or editing the nearest thing to it — is how a file
     * ends up with a change nobody asked for, and the refusal is what a model can recover from.
     */
    @Test
    fun anEditWhoseOldDoesNotOccurIsRefusedRatherThanGuessedAt() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        File(project, "notas.txt").writeText("primera\nsegunda\n")
        script(
            Chunk(
                toolCall(
                    0,
                    "edit_file",
                    """{"path":"notas.txt","old":"tercera","new":"CUARTA"}""",
                ),
            ),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("No estaba.")), Chunk(done()))

        converse(Turn("change the third line", "No estaba."))

        // The user was **not** asked. The check runs before the question precisely so nobody is
        // asked to approve a change that was always going to be refused.
        assertFalse(
            "an approval was asked for an edit that could not happen:\n${phone.screenText()}",
            phone.screenText().contains("approve?"),
        )
        assertEquals("primera\nsegunda\n", File(project, "notas.txt").readText())
        val result = toolResult()
        assertTrue(result.content.contains("is not in notas.txt"))
        assertTrue(result.content.contains("Nothing was changed"))
    }

    /** An `old` that is in the file twice is refused too, with the count that says why. */
    @Test
    fun anEditWhoseOldOccursTwiceIsRefusedAndSaysHowMany() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        File(project, "notas.txt").writeText("x = 1\ny = 2\nx = 1\n")
        script(
            Chunk(toolCall(0, "edit_file", """{"path":"notas.txt","old":"x = 1","new":"x = 3"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Ambiguo.")), Chunk(done()))

        converse(Turn("change x", "Ambiguo."))

        assertEquals("x = 1\ny = 2\nx = 1\n", File(project, "notas.txt").readText())
        val result = toolResult()
        assertTrue(result.content, result.content.contains("2 times"))
    }

    /**
     * An `old` or a `new` carrying a character a terminal acts on is refused, and the question is
     * never asked.
     *
     * **This is the one prompt in the app a human answers with a single keystroke, and it is drawn
     * partly in the model's own words.** The file here has an escape sequence, a carriage return
     * and a bell in it, and the model is quoting it back: put a CR in `new` and `approve? [y/N]`
     * is no longer the last thing on a line, and put an ESC in it and the line above can be erased
     * and replaced with something else. So the refusal happens in [omp.agent.tools.EditFile]'s
     * `check`, before the preview is built, and the way out is `write_file` — whose prompt is made
     * of the path and the size and shows no text at all.
     */
    @Test
    fun anEditWhoseTextCarriesAControlCharacterIsRefusedBeforeTheQuestionIsAsked() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        // The file the model read: one line carrying an escape sequence, one a carriage return, one
        // a bell. Built out of code points rather than pasted, because a literal ESC in a source
        // file is invisible and a test whose subject is invisible is a test nobody reads.
        val notes = File(project, "notas.txt")
        notes.writeText("uno$ESC[31m\ndos${CR}tres\ncuatro${BEL}cinco\n")
        val before = notes.readBytes()
        val script = listOf(
            // In `old`: three texts that really are in the file, each carrying one character. Each
            // is a JSON escape, because a raw control byte is not legal inside a JSON string.
            """{"path":"notas.txt","old":"uno$J_ESC[31m","new":"x"}""",
            """{"path":"notas.txt","old":"dos${J_CR}tres","new":"x"}""",
            """{"path":"notas.txt","old":"cuatro${J_BEL}cinco","new":"x"}""",
            // And in `new`, on three `old`s that are in the file exactly once each.
            """{"path":"notas.txt","old":"uno","new":"nuevo$J_ESC[31m"}""",
            """{"path":"notas.txt","old":"dos","new":"otro$J_CR"}""",
            """{"path":"notas.txt","old":"cuatro","new":"otro$J_BEL"}""",
        )
        val chunks = ArrayList<Chunk>()
        for ((at, arguments) in script.withIndex()) {
            chunks += Chunk(toolCall(at, "edit_file", arguments, "call_$at"))
        }
        script(*chunks.toTypedArray(), Chunk(finishTools()), Chunk(done()))
        script(Chunk(delta("No se puede.")), Chunk(done()))

        converse(Turn("change every line", "No se puede."))

        val results = toolResults()
        assertEquals(script.size, results.size)
        for (result in results) {
            assertTrue(
                "not refused as a control character:\n${result.content}",
                result.content.contains("a character a terminal acts on"),
            )
            assertTrue(
                "the way out was not named:\n${result.content}",
                result.content.contains("write_file"),
            )
        }
        // Each one names the character it found, so a model knows which of its own two texts to
        // rewrite rather than only that it was refused.
        val named = listOf("ESC (0x1B)", "CR (0x0D)", "BEL (0x07)")
        for (at in results.indices) {
            assertTrue(
                "result $at did not name ${named[at % 3]}:\n${results[at].content}",
                results[at].content.contains(named[at % 3]),
            )
        }
        // The question was never asked: this is the whole difference between a refusal and a
        // rigged question, and the screen is the only place a rigged one can be seen.
        assertFalse(
            "the user was asked to approve a prompt the model drew:\n${phone.screenText()}",
            phone.screenText().contains("approve?"),
        )
        assertTrue("the file was changed anyway", before.contentEquals(notes.readBytes()))
    }

    /**
     * A result carrying a character a terminal acts on is *shown* with it spelled out, and the
     * line naming the tool is still a line of its own above it.
     *
     * A read is the case that matters: a file the user wrote can hold anything, and a carriage
     * return in one moves the cursor and rewrites `omp: read_file returned:` into whatever came
     * after it. The transcript and the result the model reads keep the bytes exactly as they are —
     * this is about the screen and only about the screen, and it is the same rule
     * [omp.agent.tools.Sandbox] applies to a path, for the same reason.
     */
    @Test
    fun aResultWithAControlCharacterIsShownWithItSpelledOut() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        File(project, "raro.txt").writeText("antes${CR}despues${BEL}${ESC}[2Jfin\n")
        script(
            Chunk(toolCall(0, "read_file", """{"path":"raro.txt"}""", "call_0")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Leido.")), Chunk(done()))

        converse(Turn("read the odd one", "Leido."))

        val screen = phone.screenText()
        assertTrue(
            "a control character was sent to the screen:\n$screen",
            screen.contains("""antes\rdespues\a\e[2Jfin"""),
        )
        assertTrue("the tool line was rewritten:\n$screen", screen.contains("read_file returned:"))
        // And the model's own copy is the file's bytes, unchanged: the screen is not a filter.
        assertTrue(toolResult().content.contains("\r"))
    }

    /** A file too large to return whole is refused with its size, never cut. */
    @Test
    fun aFileTooLargeToReturnIsRefusedWithItsSizeRatherThanTruncated() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        val big = StringBuilder()
        while (big.length <= Tools.MAX_RESULT_BYTES) big.append("linea de relleno\n")
        File(project, "grande.txt").writeText(big.toString())
        script(
            Chunk(toolCall(0, "read_file", """{"path":"grande.txt"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Grande.")), Chunk(done()))

        converse(Turn("read the big one", "Grande."))

        val result = toolResult()
        assertTrue(result.content, result.content.contains("${Tools.MAX_RESULT_BYTES}-byte limit"))
        assertTrue(result.content, result.content.contains("Nothing was truncated"))
        assertTrue("the refusal did not name an offset:\n${result.content}", result.content.contains("offset"))
    }

    // ---- the loop's own limits ------------------------------------------------------------------

    /**
     * The tool-round cap is reached, and it is said out loud and written to the transcript.
     *
     * A loop that stopped for a reason nobody could see is the same failure as one that stopped
     * for no reason at all, so both the user and whoever reads the folder are told.
     */
    @Test
    fun theToolRoundCapIsReachedAndSaidOutLoud() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        // One script per round: the model asks for the same harmless read every time, and only
        // the cap can end it.
        repeat(Tools.MAX_ROUNDS + 1) {
            script(
                Chunk(toolCall(0, "list_dir", "{}")),
                Chunk(finishTools()),
                Chunk(done()),
            )
        }

        val status = converse(Turn("list it", "tool rounds", ends = true))

        assertEquals(130, status)
        val screen = phone.screenText()
        assertTrue("the cap was not said out loud:\n$screen", screen.contains("all ${Tools.MAX_ROUNDS} tool rounds"))
        assertEquals("one request too many was made", Tools.MAX_ROUNDS + 1, requests.size)
        val entry = Conversation(phone.shell.session.vfs, project.path).read().last()
        assertEquals(Kind.SYSTEM, entry.kind)
        assertEquals("tool-cap", entry.extra["stopped"])
    }

    /**
     * A Ctrl-C at the approval prompt cancels that call and gives the terminal back.
     *
     * The status is `^C`'s and the user is at their own prompt, which is what a Ctrl-C anywhere
     * else in this app does; a tool call that ignored it would leave a session waiting for a
     * keypress that is never coming.
     */
    @Test
    fun ctrlCAtTheApprovalPromptCancelsTheCallAndReturns() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "write_file", """{"path":"nunca.txt","content":"x\n"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )

        val status = converse(Turn("write it", "approve?", interrupt = true))

        assertEquals(130, status)
        assertFalse("a cancelled write put a file on the phone", File(project, "nunca.txt").exists())
        val screen = phone.screenText()
        assertTrue("the cancellation was not said:\n$screen", screen.contains("cancelled"))
        val entry = Conversation(phone.shell.session.vfs, project.path).read().last()
        assertEquals(Kind.SYSTEM, entry.kind)
        assertEquals("cancelled", entry.extra["stopped"])
    }

    /**
     * A cancelled approval leaves a history the *next* question can be sent, and the endpoint
     * accepts it.
     *
     * The cancellation happens after the model's request was written down and before any result
     * was, so the transcript holds an assistant line asking for a tool that nothing answers. Every
     * OpenAI-compatible endpoint refuses that — and this one does too, in [unpairable], which is
     * why the test can say "without a 400" rather than "the second question got an answer": the
     * stub 400s the shape a real one would, and the answer before it is the app's own.
     *
     * The repair is to leave the exchange out whole — the calls and the results together — rather
     * than to invent a result the user never agreed to. What the model is shown is a conversation
     * with a question in it and an answer it did give, which is what happened.
     */
    @Test
    fun aCancelledApprovalLeavesAHistoryTheNextQuestionCanBeSent() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "write_file", """{"path":"nunca.txt","content":"x\n"}""", "call_0")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        assertEquals(130, converse(Turn("write it", "approve?", interrupt = true)))
        assertFalse("a cancelled write put a file on the phone", File(project, "nunca.txt").exists())

        // The same folder, a new session, one question later: the transcript now holds the
        // exchange the user stopped in the middle of.
        script(Chunk(delta("Entendido.")), Chunk(done()))
        val status = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val again = Thread({ status.set(type("omp")); done.countDown() }, "omp-second-question")
        again.isDaemon = true
        again.start()
        phone.input.feed("otra vez\r".toByteArray(StandardCharsets.UTF_8))
        await("the second answer, or a refusal") {
            val screen = phone.screenText()
            screen.contains("Entendido.") || screen.contains("rejected the request")
        }
        phone.input.feed(byteArrayOf(CTRL_C))
        assertTrue("the second session never gave the terminal back", done.await(30, TimeUnit.SECONDS))
        assertEquals(130, status.get())

        val screen = phone.screenText()
        assertFalse("the endpoint refused the second request:\n$screen", screen.contains("rejected the request"))
        assertTrue("the second question was not answered:\n$screen", screen.contains("Entendido."))
        assertEquals(2, requests.size)
        val sent = messagesOf(requests.last().body)
        assertTrue(
            "a tool message with no call above it went out:\n$sent",
            sent.none { it.str("role") == "tool" },
        )
        assertTrue(
            "a call nothing answered went out:\n$sent",
            sent.none { it.field("tool_calls") != null },
        )
    }

    /**
     * Two results whose id was lost in the transcript go out with two ids, not one.
     *
     * The transcript here is one a text editor has been through: the assistant line kept its calls
     * and both results lost the `tool_call_id` that paired them. One constant for the fallback
     * would give both the same id, which pairs with nothing twice instead of once, and a reader of
     * the folder could not tell a recovered line from a genuine one. **The exchange itself is kept**,
     * because a lost id on one line is a reason to recover the line and not to throw the round away.
     */
    @Test
    fun twoResultsWhoseIdWasLostAreSentWithTwoIds() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        val conversation = Conversation(phone.shell.session.vfs, project.path)
        conversation.append(Kind.ASSISTANT, "voy a leer dos cosas", mapOf("tool_calls" to TWO_CALLS))
        conversation.append(Kind.TOOL_RESULT, "notas.txt: hola")
        conversation.append(Kind.TOOL_RESULT, "otro.txt: adios")
        script(Chunk(delta("Listo.")), Chunk(done()))

        converse(Turn("sigue", "Listo."))

        val sent = messagesOf(requests.last().body)
        val answered = sent.filter { it.str("role") == "tool" }
        assertEquals(2, answered.size)
        val ids = answered.map { (it.field("tool_call_id") as Json.Str).value }
        assertEquals("two orphans shared one id: $ids", 2, ids.distinct().size)
        for (id in ids) assertTrue("not a minted id: $id", id.startsWith("call_omp_"))
    }

    // ---- what the transcript and the requests may never carry -----------------------------------

    /** A tool round does not put the key in the transcript, the request body, or the screen. */
    @Test
    fun theKeyIsStillNowhereAfterAToolRound() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "write_file", """{"path":"notas.txt","content":"hola\n"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("listo")), Chunk(done()))

        converse(Turn("write notas.txt", "approve?", answer = "y"), Turn("", "listo"))

        assertEquals("Bearer $secret", requests.last().header("Authorization"))
        for (request in requests) {
            assertFalse("a request body carried the key", request.body.contains(secret))
        }
        val transcript = transcriptFile().readText()
        assertFalse("the transcript carried the key", transcript.contains(secret))
        assertFalse("the screen carried the key", phone.screenText().contains(secret))
        // The transcript did carry the tool round, which is the other half of the promise.
        assertTrue(transcript.contains("\"tool_call_id\""))
        assertTrue(transcript.contains("\"approved\":\"y\""))
    }

    // ---- a name this build does not have --------------------------------------------------------

    /**
     * A model that asks for `run` gets a refusal naming the tools there are, and the session goes
     * on. `omp run` is named in the same breath, because a model that has read a help screen has
     * seen the word and would otherwise conclude the two are the same thing.
     */
    @Test
    fun aModelThatAsksForAToolThisBuildDoesNotHaveIsToldWhatThereIs() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "run", """{"command":"vm reset --force"}""")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Entendido.")), Chunk(done()))

        val status = converse(Turn("reset the vm", "Entendido."))

        assertEquals("the refusal ended the session", 130, status)
        // Nothing was put to the user, because nothing was ever going to be written.
        assertFalse(
            "an approval was asked for a tool that does not exist:\n${phone.screenText()}",
            phone.screenText().contains("approve?"),
        )
        val result = toolResult()
        assertTrue(result.content, result.content.contains("no tool called 'run'"))
        for (tool in Tools.ALL) {
            assertTrue("'${tool.name}' is not in the list the model was given", result.content.contains(tool.name))
        }
        assertTrue(result.content, result.content.contains("vm reset"))
    }

    /** Arguments that are not a JSON object are a tool result, not an exception out of the loop. */
    @Test
    fun argumentsThatAreNotAJsonObjectAreRefusedAndTheLoopGoesOn() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        script(
            Chunk(toolCall(0, "read_file", "notas.txt")),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Eso no es un objeto.")), Chunk(done()))

        val status = converse(Turn("read notas", "Eso no es un objeto."))

        assertEquals(130, status)
        assertTrue(toolResult().content.contains("not a JSON object"))
    }

    /** A tool call whose name never arrived is refused with a sentence, not looked up. */
    @Test
    fun aCallWithNoNameIsRefusedWithASentence() {
        val project = open("notas")
        configure(project)
        storeKey(project)
        // The same helper every other tool call here goes through, with the name left out — a call
        // whose `function` never carried one is what a truncated stream looks like.
        script(
            Chunk(toolCallChunk(0, null, "{}", id = null)),
            Chunk(finishTools()),
            Chunk(done()),
        )
        script(Chunk(delta("Sin nombre.")), Chunk(done()))

        val status = converse(Turn("do something", "Sin nombre."))

        assertEquals(130, status)
        assertTrue(toolResult().content.contains("carried no tool name"))
    }

    // ---- the helpers ----------------------------------------------------------------------------

    private fun open(name: String): File {
        assertEquals(0, phone.run("omp new $name").status)
        return File(container, name)
    }

    private fun configure(project: File) {
        AgentState(phone.shell.session.vfs, project.path)
            .write(AgentConfig(provider = "openai", baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "test-model"))
    }

    /**
     * The one route a test has to a stored key: the same `omp key` a user types, from inside the
     * conversation it is a key for.
     *
     * **Both halves of this are the difference between a red test and a suite that never
     * finishes.** The session is put into [project] first, because `omp key` takes its provider
     * from the conversation it is standing in and asks for a provider name anywhere else — so a
     * test that ran `omp new` twice and left the shell in the second folder had the provider
     * prompt eat the key fed below, and then sat in `readSecret` waiting for a key that was never
     * coming, with the test thread inside an unbounded `JobGroup.join()` behind it. And the key
     * is typed only once the prompt asking for it is on the screen, the way [converse] types a
     * question, so prompts that arrive in another order fail the wait below instead of parking a
     * read on a channel nothing will ever feed again.
     */
    private fun storeKey(project: File) {
        assertEquals(0, type("omp ${project.name}"))
        val status = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val storing = Thread({
            status.set(type("omp key"))
            done.countDown()
        }, "omp-key-test")
        storing.isDaemon = true
        storing.start()
        await("the prompt for the openai key") { phone.screenText().contains("paste the openai key now") }
        phone.input.feed("$secret\r".toByteArray(StandardCharsets.UTF_8))
        assertTrue("omp key never gave the terminal back", done.await(30, TimeUnit.SECONDS))
        assertEquals(0, status.get())
    }

    private fun keyFile(): File =
        File(File(harness.services.appFilesDir(), KeyStore.SUBDIR), "openai${KeyStore.EXT}")

    private fun transcriptFile(): File = File(File(container, "notas"), ".omp/transcript.jsonl")

    private fun entries() = Conversation(phone.shell.session.vfs, File(container, "notas").path).read()

    private fun toolResults() = entries().filter { it.kind == Kind.TOOL_RESULT }

    private fun toolResult() = toolResults().last()

    private fun type(line: String, tty: Boolean = true): Int {
        val out = ScreenOutput(phone.screen)
        val err = ScreenOutput(phone.screen)
        val status = phone.shell.shell.executeLine(line, phone.shell.stdin, out, err, tty)
        out.flush()
        err.flush()
        return status
    }

    /**
     * A whole agent session, on its own thread, driven the way a person drives it.
     *
     * Every step waits for the prompt to come back before the next one is typed, and the text a
     * turn waits for is one a person could see: the approval prompt, or the last line of an answer.
     * A test that typed `y` before the question was on screen would be testing a race.
     *
     * **The status a test gets back is `^C`'s, 130, because that is how a session is left**: the
     * Ctrl-C at the end lands on an empty prompt. So the number is not the answer to "did the
     * session survive" on its own — the transcript and the folder are — but a session that hung
     * or died would show up here as something else.
     */
    private fun converse(vararg turns: Turn): Int {
        val status = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val running = Thread({
            status.set(type(if (turns.first().yes) "omp --yes" else "omp"))
            done.countDown()
        }, "omp-agent-tools-test")
        running.isDaemon = true
        running.start()
        for (step in turns) {
            if (step.ask.isNotEmpty()) {
                phone.input.feed("${step.ask}\r".toByteArray(StandardCharsets.UTF_8))
            }
            if (step.answer != null) {
                await("'${step.wait}' on the screen") { phone.screenText().contains(step.wait) }
                if (step.interrupt) {
                    phone.input.feed(byteArrayOf(CTRL_C))
                } else {
                    phone.input.feed("${step.answer}\r".toByteArray(StandardCharsets.UTF_8))
                }
            } else {
                await("'${step.wait}' on the screen") { phone.screenText().contains(step.wait) }
                if (step.interrupt) {
                    phone.input.feed(byteArrayOf(CTRL_C))
                } else if (!step.ends) {
                    awaitPromptAfter(step.wait)
                }
            }
        }
        if (turns.none { it.interrupt }) phone.input.feed(byteArrayOf(CTRL_C))
        assertTrue("the agent never gave the terminal back", done.await(30, TimeUnit.SECONDS))
        return status.get()
    }

    private fun awaitPromptAfter(answered: String) {
        val at = phone.screenText().indexOf(answered)
        assertTrue("'$answered' never reached the screen:\n${phone.screenText()}", at >= 0)
        await("the prompt after '$answered'") {
            phone.screenText().indexOf(PROMPT, at + answered.length) >= 0
        }
    }

    private fun script(vararg chunks: Chunk) {
        scripts += Script(chunks.toList())
    }

    private fun event(json: String) = "data: $json\n\n"

    private fun delta(content: String) = event("""{"choices":[{"index":0,"delta":{"content":"$content"}}]}""")

    private fun finishTools() =
        event("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")

    private fun done() = event("[DONE]")

    /** One `tool_calls` delta, with each piece left out that the test wants left out. */
    private fun toolCallChunk(
        index: Int,
        name: String?,
        arguments: String?,
        id: String? = "call_1",
    ): String {
        val parts = ArrayList<String>()
        if (id != null) parts += """"id":"$id""""
        parts += """"type":"function""""
        val function = ArrayList<String>()
        if (name != null) function += """"name":"$name""""
        if (arguments != null) function += """"arguments":${quote(arguments)}"""
        parts += """"function":{${function.joinToString(",")}}"""
        return event("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":$index,${parts.joinToString(",")}}]}}]}""")
    }

    /** A whole call in one chunk, which is what an endpoint that is not splitting does. */
    private fun toolCall(index: Int, name: String, arguments: String, id: String = "call_1"): String =
        toolCallChunk(index, name, arguments, id)

    /** JSON string escaping for the handful of arguments these tests hand-write. */
    private fun quote(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun messagesOf(body: String): List<Json> = (Json.parse(body) as Json.Obj).arr("messages")!!

    private fun await(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 25_000
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("timed out after 25s waiting for $what; the screen says:\n${phone.screenText()}", until())
    }

    /**
     * One question in a session: what to type, the text that means it has got there, and the key
     * to type once it is on the screen.
     */
    private class Turn(
        val ask: String,
        val wait: String,
        /** Typed at the approval prompt once [wait] is on the screen. */
        val answer: String? = null,
        /** The Ctrl-C is meant to land at the approval prompt. */
        val interrupt: Boolean = false,
        /** The verb ends the session itself, so there is no prompt coming back. */
        val ends: Boolean = false,
        /** The session is `omp --yes` rather than `omp`. */
        val yes: Boolean = false,
    )

    private companion object {
        const val CTRL_C: Byte = 0x03

        /** The agent's prompt for the one conversation these tests make. */
        const val PROMPT = "omp[notas] > "

        /** What the app uses: 15s to connect, 5min between events of one reply. */
        const val CONNECT_MS = 15_000
        const val READ_MS = 300_000

        /** Distinctive, so a substring search for it cannot find the user's question by accident. */
        const val MARKER = "ZQ7-marca-del-usuario"

        /** What a model would put in the state file to have the key sent somewhere else. */
        const val HIJACKED = "base_url=https://elsewhere.test/v1"

        /** The three characters an edit must not carry, as the characters themselves. */
        val ESC: String = 27.toChar().toString()
        val CR: String = 13.toChar().toString()
        val BEL: String = 7.toChar().toString()

        /** And the same three as a JSON string has to spell them, because a raw byte is not legal. */
        val J_ESC: String = "\\" + "u001b"
        val J_CR: String = "\\" + "r"
        val J_BEL: String = "\\" + "u0007"

        /**
         * Two calls as the transcript holds them: the array an assistant line is replayed with, and
         * the two ids results would pair with if they still had their own.
         */
        const val TWO_CALLS =
            """[{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{}"}},""" +
                """{"id":"call_b","type":"function","function":{"name":"read_file","arguments":"{}"}}]"""
    }
}
