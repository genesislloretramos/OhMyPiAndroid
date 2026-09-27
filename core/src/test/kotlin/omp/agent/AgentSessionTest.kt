package omp.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import omp.agent.json.Json
import omp.agent.tools.Tools
import omp.agent.store.AgentConfig
import omp.agent.store.AgentState
import omp.agent.store.Conversation
import omp.agent.store.KeyStore
import omp.shell.HttpStream
import omp.shell.HttpRequests
import omp.shell.SseReader
import omp.shell.ScreenOutput
import omp.vm.VmHarness
import omp.vm.VmPhone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `omp` as the agent, driven the way a user drives it, over a real socket.
 *
 * Every assertion here is something a person could type. Keystrokes go into one
 * [omp.shell.InputChannel], answers come off one [omp.term.Screen], and every fact about a
 * conversation is read back out of the real directory under `Documents/omp` with `java.io.File` —
 * because the promise this feature makes is not "the command prints an answer", it is "the bytes
 * are in a folder the user can open". A test that only went through the [omp.shell.fs.Vfs] could
 * not tell a bind from a copy, and one that only checked a return value could not tell a printed
 * key from an unprinted one.
 *
 * The endpoint is a real `com.sun.net.httpserver.HttpServer` and the transport is a real
 * [HttpURLConnection] ([omp.shell.HttpRequests] + [SseReader]): the interesting facts are all in the bytes — a token
 * split across two writes, a role-only first chunk, a 401 whose body the JDK eats, a stream that
 * has to be closed the moment a Ctrl-C lands — and no mock has any of them.
 */
class AgentSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var harness: VmHarness
    private lateinit var phone: VmPhone

    /** The loopback endpoint, which is where every test but one talks to. */
    private lateinit var server: HttpServer

    /** The same server on a non-loopback address of this machine, for the plaintext warning. */
    private var remote: HttpServer? = null
    private var remoteHost: String? = null

    /** What the conversations folder is on this machine. */
    private lateinit var container: File

    private val scripts = Collections.synchronizedList(ArrayList<Script>())
    private val requests = Collections.synchronizedList(ArrayList<Request>())
    private val closes = AtomicInteger(0)

    /** Distinctive enough that a substring search for it cannot pass by accident. */
    private val secret = "sk-omp-7Qf2Xc9Vb4Lw0ZrTk"

    private class Script(val status: Int, val chunks: List<Chunk>)

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
        // A second one on a non-loopback address, so the plaintext warning can be watched doing its
        // job rather than asserted about in the abstract.
        lanAddress()?.let { host ->
            val other = HttpServer.create(InetSocketAddress(host, 0), 0)
            other.executor = Executors.newCachedThreadPool()
            other.createContext("/v1/chat/completions") { serve(it) }
            other.start()
            remote = other
            remoteHost = host
        }
        harness.stub.stream = { url, method, headers, body ->
            requests += Request(
                url,
                method,
                headers.associate { (k, v) -> k to v },
                body?.toString(StandardCharsets.UTF_8) ?: "",
            )
            val conn = HttpRequests.open(url, method, headers, body, CONNECT_MS, READ_MS)
            // One reader for the life of the connection: a BufferedReader built per call would read
            // a packet, hand back its first line and throw the rest of the buffer away.
            val lines = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            // The same order the shipped transports use — reader first, then disconnect — so this
            // double behaves like the app and not like a transport that could be woken.
            val events = SseReader(
                { lines.readLine() },
                { closes.incrementAndGet(); runCatching { lines.close() }; conn.disconnect() },
            )
            object : HttpStream {
                override val status: Int = HttpRequests.status(conn, url)
                override fun next(): String? = events.next()
                override fun close() = events.close()
            }
        }
        // What [omp.shell.ShellSession.run] does for a REPL and `executeLine` alone does not: a
        // Ctrl-C byte cancels the foreground job. Without it there is no Ctrl-C in a test but the
        // one kind that matters, which is the one that arrives while a command is blocked.
        phone.input.onInterrupt = { phone.shell.session.foreground?.cancel() }
    }

    @After
    fun tearDown() {
        server.stop(0)
        remote?.stop(0)
        (server.executor as ExecutorService).shutdownNow()
        (remote?.executor as? ExecutorService)?.shutdownNow()
    }

    private fun serve(exchange: HttpExchange) {
        val script = synchronized(scripts) { scripts.removeAt(0) }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        // 0 is chunked, which is what a 200 SSE response is; an error response is a whole body.
        val length = if (script.status == 200) {
            0L
        } else {
            script.chunks.sumOf { it.text.toByteArray(StandardCharsets.UTF_8).size.toLong() }
        }
        exchange.sendResponseHeaders(script.status, length)
        val out = exchange.responseBody
        for (chunk in script.chunks) {
            out.write(chunk.text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            if (chunk.pauseMillis > 0) Thread.sleep(chunk.pauseMillis)
        }
        out.close()
    }

    // ---- the answer, and the folder that remembers it -----------------------------------------

    @Test
    fun theAnswerStreamsToTheScreenInTheDeltasTheServerSentAndTheFolderRemembersIt() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        script(200, Chunk(roleOnly()), Chunk(delta("three files")), Chunk(delta(", one of them a")), Chunk(finish()), Chunk(done()))

        converse(turn("what is in here?", "one of them a"))

        val screen = phone.screenText()
        // Every delta is on the screen, in the order the server sent it.
        var at = -1
        for (piece in listOf("three files", ", one of them a")) {
            val found = screen.indexOf(piece, at)
            assertTrue("'$piece' never reached the screen:\n$screen", found > at)
            at = found
        }
        // A role-only first chunk and a bare finish_reason printed nothing at all.
        assertFalse("a non-text delta printed something:\n$screen", screen.contains("{"))

        // The user's line and the model's answer are both on disk, in the conversation's folder.
        val entries = Conversation(phone.shell.session.vfs, project.path).read()
        assertEquals(listOf("user", "assistant"), entries.map { it.kind.wire })
        assertEquals("what is in here?", entries[0].content)
        val answer = "three files, one of them a"
        assertEquals(answer, entries[1].content)
        assertEquals("test-model", entries[1].extra["model"])
        assertEquals("openai", entries[1].extra["provider"])

        // And a NEW session in the same folder is sent the whole of it. This is the feature: a
        // conversation is a folder, and the folder remembers.
        script(200, Chunk(delta("and a readme")), Chunk(done()))
        converse(turn("and the second thing?", "and a readme"))
        val sent = messagesOf(requests.last().body)
        assertEquals("system", sent[0].str("role"))
        assertTrue(sent[0].str("content")!!.contains(project.path))
        // The prompt names the tools and the boundary, and the request offers exactly the five
        // this build has: a request that described tools it does not carry is the failure the
        // system prompt and the `tools` array are two halves of.
        val system = sent[0].str("content")!!
        for (tool in Tools.ALL) {
            assertTrue("the system prompt does not name ${tool.name}", system.contains(tool.name))
        }
        assertTrue("the system prompt does not say where the tools reach", system.contains("outside it is refused"))
        val offered = (Json.parse(requests.last().body) as Json.Obj).arr("tools")!!
        assertEquals(
            Tools.ALL.map { it.name },
            offered.map { (it as Json.Obj).field("function").let { f -> (f as Json.Obj).str("name") } },
        )
        assertEquals(listOf("user", "assistant", "user"), sent.drop(1).map { it.str("role") })
        assertEquals(answer, sent[2].str("content"))
        assertEquals("and the second thing?", sent[3].str("content"))
    }

    @Test
    fun theKeyIsInTheHeaderAndInNothingElse() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        script(200, Chunk(delta("the answer")), Chunk(done()))
        converse(turn("hello", "the answer"))

        // The header, and the header only.
        assertEquals("Bearer $secret", requests.last().header("Authorization"))
        assertEquals("text/event-stream", requests.last().header("Accept"))
        // Not in the body: a request body is the one part of a request a provider logs whole.
        assertFalse(requests.last().body.contains(secret))

        // Not in the transcript and not on the screen.
        assertFalse(transcriptFile().readText().contains(secret))
        assertFalse(phone.screenText().contains(secret))
        // And not in the exception a refusal produces: the 401 is a sentence, and nothing else.
        script(401, Chunk(""))
        converse(turn("again", "rejected the key"))
        val screen = phone.screenText()
        assertTrue(screen.contains("rejected the key"))
        assertFalse("the key was in a refusal:\n$screen", screen.contains(secret))
        assertFalse(transcriptFile().readText().contains(secret))
    }

    @Test
    fun aRefusedRequestSaysWhatTheRefusalSaysAndLeavesTheConversationAlone() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        val before = transcriptFile().exists()
        script(401, Chunk(""))

        // 130: the session is left with a Ctrl-C at an empty prompt, which is how a user leaves it.
        assertEquals(130, converse(turn("will you answer?", "HTTP 401")))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("rejected the key"))
        assertTrue(screen, screen.contains("127.0.0.1"))
        assertFalse("a stack trace reached the screen:\n$screen", screen.contains("Exception"))
        assertFalse("a stack trace reached the screen:\n$screen", screen.contains("at omp."))
        // The question never reached a model, so it is not part of the conversation with one.
        assertEquals(before, transcriptFile().exists())
    }

    @Test
    fun ctrlCMidStreamStopsTheAnswerClosesTheStreamAndKeepsWhatArrived() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        val opens = closes.get()
        script(
            200,
            Chunk(delta("the first half ")),
            // A gap long enough for the Ctrl-C to land in, and an event after it: the loop notices
            // a cancel *between* events, which is the only place it can.
            Chunk("", 1_500),
            Chunk(delta("and the second half")),
            Chunk(done()),
        )

        val status = converse(turn("go on", "the first half", interrupt = true))

        assertEquals(130, status)
        assertTrue("the stream was not closed", closes.get() > opens)
        val screen = phone.screenText()
        assertTrue(screen, screen.contains("interrupted"))
        assertFalse("the answer did not stop where it was told to:\n$screen", screen.contains("second half"))
        val entry = Conversation(phone.shell.session.vfs, project.path).read().last()
        assertEquals("the first half ", entry.content)
        assertEquals("interrupted", entry.extra["stopped"])
    }

    /**
     * A Ctrl-C belongs to the answer it stopped, and to nothing after it.
     *
     * It is still a byte in the channel when the next session starts, and a session that read it
     * as its own would be over before it printed a prompt — so it is dropped at the start of a
     * session. Dropping it must not cost anything else: the keystrokes of the *new* question are
     * in the same channel, and a drain that took the first byte it found would eat the first
     * letter of a question a user had already begun typing.
     */
    @Test
    fun aSessionAfterAnInterruptedOneKeepsTheFirstKeystrokeOfTheNextQuestion() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        script(200, Chunk(delta("the first half ")), Chunk("", 1_500), Chunk(delta("and the rest")), Chunk(done()))
        script(200, Chunk(delta("still here")), Chunk(done()))

        converse(turn("go on", "the first half", interrupt = true))
        converse(turn("was the first letter eaten?", "still here"))

        val screen = phone.screenText()
        assertTrue("the first keystroke of the next question was eaten:\n$screen", screen.contains("was the first letter eaten?"))
        assertEquals(listOf("user", "assistant", "user", "assistant"), Conversation(phone.shell.session.vfs, project.path).read().map { it.kind.wire })
    }

    /**
     * A Ctrl-C during a silent gap: nothing the model said afterwards reaches the screen, and what
     * it had said is kept and marked.
     *
     * The gap is the case that matters for the *record* — the whole of the answer is the first
     * delta, and everything after the cancel must not be shown or written. It is deliberately not
     * a test that the cancel wakes a parked read: today it does not, because the transports release
     * by closing the reader first and `BufferedReader.close()` blocks on the lock the blocked
     * `readLine()` is holding. This double keeps that order on purpose, so the day the order is
     * fixed the gap gets shorter without this test's having claimed otherwise, and the note on the
     * waiting line is removed with the same change.
     */
    @Test
    fun ctrlCInASilentGapShowsNothingThatArrivedAfterItAndKeepsWhatCameBefore() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        val opens = closes.get()
        script(
            200,
            Chunk(delta("the whole answer so far")),
            Chunk("", 1_500),
            Chunk(delta(" and this arrived after the Ctrl-C")),
            Chunk(done()),
        )

        val status = converse(turn("keep thinking", "the whole answer so far", interrupt = true))

        assertEquals(130, status)
        assertTrue("the stream was not closed", closes.get() > opens)
        val screen = phone.screenText()
        assertTrue(screen, screen.contains("interrupted"))
        assertFalse("the answer did not stop where it was told to:\n$screen", screen.contains("arrived after"))
        val entry = Conversation(phone.shell.session.vfs, project.path).read().last()
        assertEquals("the whole answer so far", entry.content)
        assertEquals("interrupted", entry.extra["stopped"])
    }

    @Test
    fun aPipedOrExecedRunDoesNotPromptAndReturns() {
        val project = open("fotos")
        configure(project)
        script(200, Chunk(delta("nobody is listening")), Chunk(done()))

        // A pipe on stdin and a pipe on stdout, which is a script or a redirect.
        val out = ScreenOutput(phone.screen)
        val err = ScreenOutput(phone.screen)
        val piped = phone.shell.shell.executeLine("omp", ByteArrayInputStream(ByteArray(0)), out, err, false)
        out.flush()
        err.flush()
        assertEquals(2, piped)
        var screen = phone.screenText()
        assertTrue(screen, screen.contains("stdin is not the terminal this command owns"))
        assertTrue(screen, screen.contains("omp update"))
        assertFalse("a piped run asked a question:\n$screen", screen.contains("omp[fotos] >"))
        assertTrue("a piped run reached the network", requests.isEmpty())
        assertFalse("a piped run wrote to the transcript", transcriptFile().exists())

        // `vm exec` is the other case: a throwaway session in the namespace over a channel nothing
        // will ever write to. It opens a conversation of its own and then runs the agent in it.
        val execed = phone.run("vm exec 'omp fotos && omp'")
        assertEquals(2, execed.status)
        assertTrue(execed.out, execed.out.contains("stdin is not the terminal this command owns"))
        assertTrue("a vm exec run reached the network", requests.isEmpty())
        screen = phone.screenText()
        assertTrue("nothing asked anything:\n$screen", !screen.contains("omp[fotos] >"))
    }

    // ---- the credential ---------------------------------------------------------------------

    @Test
    fun ompKeyEchoesNothingAndTheOnlyCopyIsInTheKeyFile() {
        val project = open("fotos")
        configure(project)

        phone.input.feed("$secret\r".toByteArray(StandardCharsets.UTF_8))
        assertEquals(0, type("omp key"))

        val screen = phone.screenText()
        assertFalse("a character of the key reached the screen:\n$screen", screen.contains(secret))
        // Nothing of it at all: what is on the screen is the provider, the warning and the file.
        assertTrue(screen, screen.contains("openai"))
        assertTrue(screen, screen.contains("nothing is shown as you type it"))
        assertTrue(screen, screen.contains(keyFile().path))
        assertEquals(secret, keyFile().readText())
        // And it is in the app's own storage, nowhere near the folder the user can see.
        assertFalse(keyFile().path.startsWith(container.path))
    }

    @Test
    fun ompKeyShowPrintsTheFileAndAByteCountAndNoPartOfTheKey() {
        val project = open("fotos")
        configure(project)
        storeKey(project)

        assertEquals(0, type("omp key --show"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("file:"))
        assertTrue(screen, screen.contains(keyFile().path))
        assertTrue(screen, screen.contains("bytes:   ${secret.length}"))
        assertFalse("a prefix of the key is on the screen:\n$screen", screen.contains(secret.take(6)))
    }

    @Test
    fun ompKeyForgetRemovesExactlyOneProvider() {
        val project = open("fotos")
        configure(project)
        storeKey(project)
        val other = KeyStore(File(harness.services.appFilesDir())) { harness.services.wallClockMillis() }
        other.put("local", "llama-needs-no-key")

        assertEquals(0, type("omp key --forget openai"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("forgot the openai key"))
        assertFalse(keyFile().exists())
        assertEquals(listOf("local"), other.providers())
        // A provider that had no key is the state the user asked for, not a failure.
        assertEquals(0, type("omp key --forget openai"))
        assertTrue(phone.screenText().contains("there was no openai key"))
    }

    // ---- what the agent is -------------------------------------------------------------------

    @Test
    fun ompUpdatePrintsTheIdentityAndClaimsNoDownload() {
        val project = open("fotos")
        configure(project)
        storeKey(project)

        assertEquals(0, type("omp update"))
        val screen = phone.screenText()
        assertTrue(screen, screen.contains("omp agent ${Agent.VERSION}"))
        assertTrue(screen, screen.contains("provider:   openai"))
        assertTrue(
            screen,
            screen.contains("endpoint:   http://127.0.0.1:${server.address.port}/v1/chat/completions"),
        )
        assertTrue(screen, screen.contains("model:      test-model"))
        assertTrue(screen, screen.contains(keyFile().path))
        // [flat] and not the screen's own rows, and the reason is worth stating rather than
        // working around silently: these lines are longer than the terminal's width, so the text
        // is on the screen and the *rows* it is in do not contain it whole. What is asserted is
        // that the line was printed — [flat] puts the rows back into the line that was printed and
        // asserts on that. **Nothing here says where the wrap fell, and nothing should**: a wrap
        // point is a fact about the terminal's width, not about what `omp update` reported, and
        // pinning one would be a test of the screen rather than of the command.
        // Nothing in it claims anything travelled.
        for (claim in listOf("downloading", "Downloading", "checking for", "up to date", "updated to", "100%")) {
            assertFalse("'update' claims '$claim':\n$screen", screen.contains(claim))
        }
        assertTrue(screen, screen.contains("does not download anything and cannot"))
    }

    /**
     * `omp run` from a folder the session has walked out of is refused, exactly as bare `omp` is.
     *
     * The environment still names the conversation after a `cd ..` out of it, so the verb and the
     * bare command have to ask the same question of the same thing. They used not to: bare `omp`
     * asked the session where it was standing and `omp run` asked the environment, and the second
     * opened an agent whose prompt and system prompt named a folder the shell had left.
     */
    @Test
    fun ompRunFromOutsideTheFolderIsRefusedRatherThanOpeningAnAgentThere() {
        val project = open("fotos")
        configure(project)
        val outside = project.parentFile
        assertEquals(0, phone.run("cd $outside").status)

        assertEquals(2, type("omp run"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("the agent runs in a conversation folder"))
        assertTrue("an agent opened in a folder the session had left:\n$screen", !screen.contains("omp[fotos] >"))
        assertTrue("a refused omp run reached the endpoint", requests.isEmpty())
        // A bare `omp` from the same place is the launcher, which is the other half of the rule.
        val launcher = type("omp", tty = false)
        assertEquals(0, launcher)
        assertTrue(phone.screenText().contains("fotos"))
    }

    @Test
    fun ompUpdateOutsideAConversationExplainsItselfAndOpensNothing() {
        val before = container.list()?.sorted() ?: emptyList()

        assertEquals(0, type("omp update"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("this is not a conversation"))
        assertTrue(screen, screen.contains("omp new NAME"))
        assertTrue(screen, screen.contains("does not download anything and cannot"))
        assertEquals("an update outside a conversation made a folder", before, container.list()?.sorted())
        assertEquals(null, phone.shell.session.env["OMP_WORKSPACE"])
    }

    @Test
    fun ompRunOutsideAConversationExplainsItselfAndOpensNothing() {
        val before = container.list()?.sorted() ?: emptyList()

        assertEquals(2, type("omp run"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("the agent runs in a conversation folder"))
        assertTrue(screen, screen.contains("omp new NAME"))
        assertEquals(before, container.list()?.sorted())
    }

    // ---- the endpoint ------------------------------------------------------------------------

    @Test
    fun anEndpointThatIsNotHttpIsRefusedWithTheReasonAndNothingIsSent() {
        val project = open("fotos")
        configure(project, baseUrl = "ftp://files.example.com/v1")
        storeKey(project)

        converse(turn("hello", "not a scheme the agent can speak"))

        assertTrue("the key was put on a refused endpoint", requests.isEmpty())
        assertFalse(transcriptFile().exists())
    }

    @Test
    fun aBaseUrlThatIsNotAUrlIsRefusedWithTheReason() {
        val project = open("fotos")
        configure(project, baseUrl = "api.example.com/v1")
        storeKey(project)

        converse(turn("hello", "is not a URL at all"))

        assertTrue(requests.isEmpty())
    }

    @Test
    fun aPlaintextEndpointToARemoteHostWarnsOnceAndProceeds() {
        val host = remoteHost
        assumeTrue("this machine has no non-loopback IPv4 address to warn about", host != null)
        val project = open("fotos")
        configure(project, baseUrl = "http://$host:${remote!!.address.port}/v1")
        storeKey(project)
        script(200, Chunk(delta("first")), Chunk(done()))
        script(200, Chunk(delta("second")), Chunk(done()))

        converse(turn("one", "first"), turn("two", "second"))

        val screen = phone.screenText()
        assertEquals(
            "the warning was not said exactly once:\n$screen",
            1,
            Regex("plaintext").findAll(screen).count(),
        )
        // It carried on rather than refusing: both questions reached the endpoint, and both were
        // answered — which is the half a user running a local llama.cpp needs.
        assertEquals(2, requests.size)
        assertTrue(screen.contains("first"))
        assertTrue(screen.contains("second"))
    }

    /**
     * The loopback rule, stated and then actually stated.
     *
     * "This device" is what decides whether the plaintext warning is printed, and it is the one
     * check standing between a user and a key going out in the clear. Every shape is here that a
     * real configuration produces, and the ones that matter most are the ones a prefix match gets
     * wrong: `127.0.0.1.nip.io` and `127.0.0.1.evil.test` are names anybody can register and point
     * anywhere, so they are **not** this device and they are warned about. The short forms are here
     * for the other direction — `http://127.1:11434` is a real thing a user types for a model on
     * this machine, and warning about it is the noise that teaches a user to ignore the warning
     * that matters.
     */
    @Test
    fun whatTheEndpointCheckKnowsAboutThisDeviceAndAboutSchemes() {
        val here = listOf(
            "http://127.0.0.1:8080/v1",
            "http://127.0.0.7/v1",
            "http://127.255.255.255/v1",
            "http://localhost:8080/v1",
            "http://localhost.:8080/v1",
            "http://0.0.0.0:11434/v1",
            // The short forms a socket reads as this device, with the missing bytes zero-filled.
            "http://127:11434/v1",
            "http://127.1:11434/v1",
            "http://127.0.1:11434/v1",
        )
        val elsewhere = listOf(
            "http://192.168.1.10:8080/v1",
            "http://10.0.0.5/v1",
            "http://example.com/v1",
            // The two a prefix match would wave through: names, not addresses.
            "http://127.0.0.1.nip.io/v1",
            "http://127.0.0.1.evil.test/v1",
            "http://127.999.0.1/v1",
            "http://127x0.0.1/v1",
            // And the shapes that look like the short forms without being an address: five labels,
            // a label that is not a byte, and a block that is not 127/8.
            "http://127.1.2.3.4/v1",
            "http://127.0.300/v1",
            "http://128.1/v1",
        )
        for (url in here) {
            val ready = Endpoint.of(url) as Endpoint.Ready
            assertTrue(url, ready.secure)
            assertEquals(url, null, ready.warning)
        }
        for (url in elsewhere) {
            val ready = Endpoint.of(url) as Endpoint.Ready
            assertFalse(url, ready.secure)
            assertTrue(url, ready.warning!!.contains("plaintext"))
        }
        val secure = Endpoint.of("https://api.example.com/v1") as Endpoint.Ready
        assertTrue(secure.secure)
        assertEquals(null, secure.warning)
        assertEquals("api.example.com", secure.host)
        // A trailing slash joins rather than doubling, which is the mistake a user makes by hand.
        assertEquals("https://api.example.com", (Endpoint.of("https://api.example.com/") as Endpoint.Ready).url)
        assertEquals("https://api.example.com/v1/chat/completions", secure.url + Endpoint.CHAT)

        assertTrue(Endpoint.of("ftp://x.example/v1") is Endpoint.Refused)
        assertTrue(Endpoint.of("file:///etc/passwd") is Endpoint.Refused)
        assertTrue(Endpoint.of("not a url at all") is Endpoint.Refused)
        assertTrue(Endpoint.of("") is Endpoint.Refused)
        assertTrue(Endpoint.of("https://") is Endpoint.Refused)
    }

    /**
     * A credential in the base URL is refused, and the refusal does not quote it back.
     *
     * The state file is inside the conversation folder and every message here ends up on a screen,
     * so a URL a user pasted with a key in it would put that key in shared storage and in a
     * terminal. The refusal therefore names the field it was about, not its value.
     */
    @Test
    fun aBaseUrlCarryingACredentialIsRefusedAndTheRefusalDoesNotQuoteIt() {
        val project = open("fotos")
        configure(project, baseUrl = "https://sk-omp-7Qf2Xc9Vb4Lw0ZrTk@api.example.com/v1")
        storeKey(project)

        converse(turn("hello", "carries a password or a token"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("omp key"))
        assertFalse("the credential was echoed back:\n$screen", screen.contains("sk-omp-7Qf2Xc9Vb4Lw0ZrTk"))
        assertTrue("the key went out to an endpoint with credentials in its URL", requests.isEmpty())
        assertFalse(transcriptFile().exists())
        // `omp update` is the other place the URL is printed, and it prints the stripped one.
        assertEquals(0, type("omp update"))
        assertFalse(phone.screenText().contains("sk-omp-7Qf2Xc9Vb4Lw0ZrTk"))
    }

    @Test
    fun aConversationWithNoStateSaysHowToSetOneAndInventsNothing() {
        val project = open("fotos")
        script(200, Chunk(delta("never asked")), Chunk(done()))

        converse(turn("who are you?", "state.json"))

        val screen = phone.screenText()
        assertTrue(screen, screen.contains("state.json"))
        assertTrue(screen, screen.contains("provider"))
        assertTrue(screen, screen.contains("base_url"))
        assertTrue(screen, screen.contains("model"))
        assertTrue("no endpoint was invented", requests.isEmpty())
        assertFalse(transcriptFile().exists())
    }

    // ---- the helpers --------------------------------------------------------------------------

    /**
     * A conversation, entered: the folder on the phone and the session standing in it, which is
     * the two facts bare `omp` needs to become the agent.
     */
    private fun open(name: String): File {
        assertEquals(0, phone.run("omp new $name").status)
        return File(container, name)
    }

    /** The state file, written through the shipped [AgentState] rather than as bytes. */
    private fun configure(
        project: File,
        baseUrl: String = "http://127.0.0.1:${server.address.port}/v1",
    ) {
        AgentState(phone.shell.session.vfs, project.path)
            .write(AgentConfig(provider = "openai", baseUrl = baseUrl, model = "test-model"))
    }

    /**
     * The one route a test has to a stored key: the same `omp key` a user types, from inside the
     * conversation it is a key for.
     *
     * **Both halves of this are the difference between a red test and a suite that never
     * finishes.** The session is put into [project] first, because `omp key` takes its provider
     * from the conversation it is standing in and asks for a provider name anywhere else — so a
     * test that had left the shell in some other folder had the provider prompt eat the key fed
     * below, and then sat in `readSecret` waiting for a key that was never coming, with the test
     * thread inside an unbounded `JobGroup.join()` behind it. And the key is typed only once the
     * prompt asking for it is on the screen, the way [converse] types a question, so prompts that
     * arrive in another order fail the wait below instead of parking a read on a channel nothing
     * will ever feed again.
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

    private fun transcriptFile(): File = File(File(container, "fotos"), ".omp/transcript.jsonl")

    /**
     * One line through the shell, onto the real [omp.term.Screen] and with the session's own
     * channel as stdin, so anything the command asks reaches the reader `less` and `omp` use.
     */
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
     * **Every step waits for the prompt to come back before the next one is typed.** Waiting for
     * the answer to be on the screen is not enough: the stream is still open then, and a Ctrl-C
     * fed into the tail of a finished answer would be recorded as an interrupted one — a test that
     * pressed a key where a person could not have pressed it, and got a different transcript for
     * it. A person waits for the prompt; so does this. [turn]'s `interrupt` is the one case where
     * nobody waits, because that is the case being tested.
     *
     * The Ctrl-C at the end is the empty prompt, which is how a session is left.
     */
    private fun converse(vararg turns: Turn): Int {
        val status = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val running = Thread({
            status.set(type("omp"))
            done.countDown()
        }, "omp-agent-test")
        running.isDaemon = true
        running.start()
        for (step in turns) {
            phone.input.feed("${step.ask}\r".toByteArray(StandardCharsets.UTF_8))
            await("'${step.wait}' on the screen") { phone.screenText().contains(step.wait) }
            if (!step.interrupt && !step.ends) awaitPromptAfter(step.wait)
        }
        phone.input.feed(byteArrayOf(CTRL_C))
        assertTrue("the agent never gave the terminal back", done.await(20, TimeUnit.SECONDS))
        return status.get()
    }

    /**
     * Waits for the agent's own prompt to be written *after* [answered].
     *
     * Counting prompts cannot work here: a turn that ends quickly is already finished by the time
     * a 10ms poll looks, so the count the test sampled after the answer is sometimes the count it
     * is waiting for. Position is the reliable thing — the prompt that follows the answer is the
     * one that means the user could type again.
     */
    private fun awaitPromptAfter(answered: String) {
        val at = phone.screenText().indexOf(answered)
        assertTrue("'$answered' never reached the screen:\n${phone.screenText()}", at >= 0)
        await("the prompt after '$answered'") {
            phone.screenText().indexOf(PROMPT, at + answered.length) >= 0
        }
    }

    private fun script(status: Int, vararg chunks: Chunk) {
        scripts += Script(status, chunks.toList())
    }

    /**
     * The screen's rows joined into one string, for asserting that a long line was **printed**.
     *
     * `omp update` prints lines wider than the terminal, so a single line occupies several rows and
     * no one row contains it whole — which is a property of the screen, not of what the command
     * printed. This helper is the right tool for the question "did that text reach the screen at
     * all" and the wrong tool for "how did it wrap": a wrap point is not asserted here, and adding
     * one would be asserting the terminal's width rather than the agent's output. The test that
     * uses it says so at the point of use.
     */
    private fun flat(screen: String): String = screen.replace("\n", " ")

    private fun event(json: String) = "data: $json\n\n"

    private fun delta(content: String) = event("""{"choices":[{"index":0,"delta":{"content":"$content"}}]}""")

    private fun roleOnly() = event("""{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""")

    private fun finish() = event("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")

    private fun done() = event("[DONE]")

    /** A `tool_calls` delta, the shape an OpenAI-compatible endpoint uses to ask for one. */
    private fun toolCall(name: String) = event(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
            """"type":"function","function":{"name":"$name","arguments":"{}"}}]}}]}""",
    )

    /** The `messages` array of a recorded request body, parsed by the shipped parser. */
    private fun messagesOf(body: String): List<Json> = (Json.parse(body) as Json.Obj).arr("messages")!!

    /** Waits for [what], and says so rather than letting a silent timeout read as a pass. */
    private fun await(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("timed out after 20s waiting for $what; the screen says:\n${phone.screenText()}", until())
    }

    /** The first non-loopback IPv4 of an interface that is up, or null on a host that has none. */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<InetAddress>()
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull()

    /**
     * One question in a session: what to type, the text that means it has been answered, and
     * whether the Ctrl-C is meant to land while the answer is still arriving.
     */
    private class Turn(
        val ask: String,
        val wait: String,
        /** The Ctrl-C is meant to land while the answer is still arriving. */
        val interrupt: Boolean = false,
        /** The verb ends the session itself, so there is no prompt coming back. */
        val ends: Boolean = false,
    )

    /** One question: what to type, and the text that means it has been answered. */
    private fun turn(
        ask: String,
        wait: String,
        interrupt: Boolean = false,
        ends: Boolean = false,
    ) = Turn(ask, wait, interrupt, ends)

    private companion object {
        const val CTRL_C: Byte = 0x03

        /** The agent's prompt for the one conversation these tests make. */
        const val PROMPT = "omp[fotos] > "

        /** What the app uses: 15s to connect, 5min between events of one reply. */
        const val CONNECT_MS = 15_000
        const val READ_MS = 300_000
    }
}
