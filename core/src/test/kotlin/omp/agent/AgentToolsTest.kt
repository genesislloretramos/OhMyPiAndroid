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

    private fun serve(exchange: HttpExchange) {
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
    }
}
