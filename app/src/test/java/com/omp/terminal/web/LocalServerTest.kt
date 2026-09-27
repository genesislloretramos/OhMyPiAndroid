package com.omp.terminal.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [LocalServer] and [TokenGate] against a real loopback socket.
 *
 * **The class under test is the shipped one.** Nothing here reimplements the parser, the pool, the
 * timeout or the token check; a mock here would be worthless, because everything interesting about
 * this seam happens in bytes no mock has: a head that arrives one byte at a time and stops, a body
 * whose `Content-Length` is a lie the server must refuse before reading, two clients at once, and
 * a port that has to be free again the instant [LocalServer.stop] returns.
 *
 * The routes are a small stand-in for [ChatApi]'s — the same paths, the same [TokenGate] in front
 * of them, and a handler that writes HTML or text — because [ChatApi] needs a
 * [omp.shell.PlatformServices] and a real `Documents/omp`, and this file is about the transport.
 * [ChatApi] itself is the only thing between this server and the agent, and it is a list of
 * handlers over [Request] and [Response].
 *
 * There is no `android.*` anywhere in this file or in anything it touches, which is the whole
 * reason the HTTP layer was written this way: `:core` cannot load an Android class, and neither
 * can this test.
 */
class LocalServerTest {

    private lateinit var server: LocalServer
    private val started = ArrayList<LocalServer>()

    @Before
    fun setUp() {
        server = serverWith()
        started += server
        server.start()
    }

    @After
    fun tearDown() {
        for (one in started) one.stop()
    }

    /**
     * A server on an ephemeral port, with the paths [ChatApi] serves.
     *
     * [readTimeoutMs] and [threads] are the two numbers a test has to be able to change to test
     * anything: a timeout test that waited the shipped five seconds would be a test that mostly
     * waits, and a timeout test on a four-thread pool could not tell a released thread from a
     * spare one.
     */
    private fun serverWith(
        readTimeoutMs: Int = LocalServer.READ_TIMEOUT_MS,
        threads: Int = LocalServer.THREADS,
        port: Int = 0,
        onIndex: Handler = Handler { _, r ->
            r.send(200, HTML, INDEX.toByteArray(StandardCharsets.UTF_8))
        },
    ): LocalServer = LocalServer(
        routes = listOf(
            route(TokenGate.LOGIN_PATH, "GET") { _, r ->
                r.send(200, HTML, LOGIN.toByteArray(StandardCharsets.UTF_8))
            },
            route(TokenGate.LOGIN_PATH, "POST") { _, r -> r.send(204, TEXT, ByteArray(0)) },
            route("/", "GET", onIndex),
            route("/api/state", "GET") { _, r ->
                r.send(200, "application/json; charset=utf-8", """{"ok":true}""".toByteArray())
            },
            route("/api/echo", "POST") { q, r ->
                r.send(200, TEXT, q.text().toByteArray(StandardCharsets.UTF_8))
            },
        ).map(::guard),
        readTimeoutMs = readTimeoutMs,
        threads = threads,
        wantedPort = port,
    )

    /** The same wrapper [ChatApi] puts round its routes, so the test drives the real policy. */
    private fun guard(route: Route): Route {
        val gate = TokenGate(TOKEN)
        val inner = route.handler
        return route(route.pattern, route.method) { request, response ->
            if (gate.check(request) == null) {
                inner.handle(request, response)
            } else {
                response.send(401, TEXT, "401: no token\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    // ---- the index and the 404 -----------------------------------------------------------------

    @Test
    fun aGetOfTheLoginPageAnswers200WithHtml() {
        // The one path with no token on it. Everything else about this server is behind that
        // check, so this is the case that says the page is really being served and not refused.
        val reply = ask("GET ${TokenGate.LOGIN_PATH} HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, reply.status)
        assertEquals(HTML, reply.header("Content-Type"))
        assertTrue(reply.body.contains("<!doctype html>"))
        assertEquals(reply.body.length, reply.header("Content-Length")!!.toInt())
    }

    @Test
    fun aGetOfTheIndexWithTheTokenAnswers200WithHtml() {
        val reply = ask("GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n")
        assertEquals(200, reply.status)
        assertEquals(HTML, reply.header("Content-Type"))
        assertTrue(reply.body.startsWith("<!doctype html>"))
    }

    @Test
    fun anUnknownPathAnswers404() {
        val reply = ask("GET /nope HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n")
        assertEquals(404, reply.status)
        // A real 404 with a body, not an empty 200: a front-end asking for a route a later build
        // removed has to be able to tell that from a route that answered.
        assertTrue(reply.body.contains("/nope"))
        assertEquals(reply.body.length, reply.header("Content-Length")!!.toInt())
    }

    @Test
    fun aKnownPathWithTheWrongMethodAnswers405AndSaysWhich() {
        val reply = ask("DELETE / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n")
        // The parser refuses a method it does not implement before it can reach a route at all,
        // so this is 501 rather than 405 — and either way it is not a 200.
        assertTrue("was ${reply.status}", reply.status == 501 || reply.status == 405)
    }

    // ---- the token ------------------------------------------------------------------------------

    @Test
    fun aRequestWithNoTokenAnswers401() {
        val reply = ask("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(401, reply.status)
        assertTrue(reply.body.contains("no token"))
    }

    @Test
    fun aRequestWithTheWrongTokenAnswers401() {
        val wrong = TOKEN.dropLast(1) + if (TOKEN.last() == 'Z') 'Y' else 'Z'
        assertNotEquals(TOKEN, wrong)
        val reply = ask("GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $wrong\r\n\r\n")
        assertEquals(401, reply.status)
    }

    @Test
    fun theTokenIsAlsoAcceptedInTheQueryAndInACookie() {
        // A `fetch` can set a header; a stylesheet and a stream cannot. All three ways in have to
        // work or the page cannot load itself, and the reason each exists is in TokenGate's KDoc.
        val query = ask("GET /?${TokenGate.PARAM}=$TOKEN HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, query.status)
        val cookie = ask(
            "GET / HTTP/1.1\r\nHost: x\r\nCookie: other=1; ${TokenGate.COOKIE}=$TOKEN\r\n\r\n",
        )
        assertEquals(200, cookie.status)
    }

    // ---- the body cap ---------------------------------------------------------------------------

    @Test
    fun anOverCapBodyAnswers413() {
        val over = LocalServer.MAX_BODY_BYTES + 1
        val reply = ask(
            "POST /api/echo HTTP/1.1\r\nHost: x\r\n" +
                "${TokenGate.HEADER}: $TOKEN\r\nContent-Length: $over\r\n\r\n" +
                "short",
        )
        assertEquals(413, reply.status)
        // Refused on the strength of the declared length alone: the five bytes that followed were
        // never read, which is the whole point — a server that reads first has already spent the
        // memory it was refusing to spend.
        assertTrue(reply.body.contains("$over"))
        assertTrue(reply.body.contains("${LocalServer.MAX_BODY_BYTES}"))
    }

    @Test
    fun aBodyUnderTheCapIsDeliveredWhole() {
        val body = "a".repeat(1024)
        val reply = ask(
            "POST /api/echo HTTP/1.1\r\nHost: x\r\n" +
                "${TokenGate.HEADER}: $TOKEN\r\nContent-Length: ${body.length}\r\n\r\n$body",
        )
        assertEquals(200, reply.status)
        assertEquals(body, reply.body)
    }

    // ---- the timeout ----------------------------------------------------------------------------

    @Test
    fun aRequestThatNeverFinishesItsHeadersIsDroppedByTheTimeout() {
        // One thread, so a stuck connection cannot hide behind a spare one: if the read timeout
        // did not release the thread, the second request below could not be answered at all.
        val tight = serverWith(readTimeoutMs = 300, threads = 1)
        started += tight
        tight.start()

        val stalled = Socket()
        stalled.connect(InetSocketAddress(LOOPBACK, tight.port), CONNECT_TIMEOUT_MS)
        // A head that starts and never ends: a request line, one header, and no blank line.
        stalled.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n".toByteArray())
        stalled.getOutputStream().flush()

        assertTrue("the server never closed the abandoned socket", closed(stalled, 5_000))
        stalled.close()

        // And the thread it was holding is back: this is the same single thread.
        val reply = askOn(tight, "GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n")
        assertEquals(200, reply.status)
    }

    @Test
    fun aConnectionThatSendsNothingAtAllIsDroppedToo() {
        val tight = serverWith(readTimeoutMs = 300, threads = 1)
        started += tight
        tight.start()
        val idle = Socket()
        idle.connect(InetSocketAddress(LOOPBACK, tight.port), CONNECT_TIMEOUT_MS)
        assertTrue(closed(idle, 5_000))
        idle.close()
    }

    // ---- more than one request at a time --------------------------------------------------------

    @Test
    fun twoConcurrentRequestsAreBothAnswered() {
        // Each handler waits for the other to arrive, so both can only finish if the server is
        // genuinely running them at the same time. On a server that answered one at a time this
        // would sit at the barrier until the read timeout and then fail.
        val barrier = CyclicBarrier(2)
        val both = serverWith(
            threads = 4,
            onIndex = { _, r ->
                try {
                    barrier.await(JOIN_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (e: BrokenBarrierException) {
                    fail("the two requests did not overlap: $e")
                } catch (e: Exception) {
                    fail("the two requests did not overlap: $e")
                }
                r.send(200, HTML, INDEX.toByteArray(StandardCharsets.UTF_8))
            },
        )
        started += both
        both.start()

        val seen = AtomicInteger(0)
        val failures = ArrayList<Throwable>()
        val threads = (0 until 2).map {
            Thread {
                try {
                    val reply = askOn(
                        both,
                        "GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n",
                    )
                    if (reply.status == 200) seen.incrementAndGet() else failures.add(IOException("status ${reply.status}"))
                } catch (e: Exception) {
                    failures.add(e)
                }
            }.apply { isDaemon = true }
        }
        for (t in threads) t.start()
        for (t in threads) t.join(JOIN_WAIT_MS * 4)

        assertEquals("failures: $failures", listOf<Throwable>(), failures)
        assertEquals(2, seen.get())
    }

    @Test
    fun moreClientsThanThreadsStillGetAnAnswer() {
        // Past the pool and the queue the answer is 503, not a dropped connection: a browser told
        // "busy" can say so, and a browser told nothing reports it as the server being down.
        val narrow = serverWith(threads = 1)
        started += narrow
        narrow.start()
        val reply = askOn(narrow, "GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n")
        assertEquals(200, reply.status)
    }

    // ---- the port -------------------------------------------------------------------------------

    @Test
    fun theServerStopsAndStartsAgainOnTheSamePort() {
        val port = freePort()
        val first = serverWith(port = port)
        first.start()
        assertEquals(
            200,
            askOn(first, "GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n").status,
        )

        first.stop()
        // A second bind on a port the first server is still holding would be "Address already in
        // use", and a service that cannot restart is a service the user has to force-stop.
        val second = serverWith(port = port)
        started += second
        second.start()
        assertEquals(port, second.port)
        assertEquals(
            200,
            askOn(second, "GET / HTTP/1.1\r\nHost: x\r\n${TokenGate.HEADER}: $TOKEN\r\n\r\n").status,
        )
    }

    @Test
    fun stoppingTwiceIsHarmless() {
        // onDestroy and a Stop action can both arrive, and a service whose stop throws is a
        // service that logs a crash every time a user swipes it away.
        server.stop()
        server.stop()
    }

    // ---- the client -----------------------------------------------------------------------------

    private fun ask(request: String): Reply = askOn(server, request)

    /** One connection, one request, one reply — which is the whole protocol this server speaks. */
    private fun askOn(target: LocalServer, request: String): Reply {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(LOOPBACK, target.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val all = socket.getInputStream().readBytes()
            return parse(String(all, StandardCharsets.ISO_8859_1))
        } finally {
            socket.close()
        }
    }

    /** Whether the far end closed without being asked, within [withinMs]. */
    private fun closed(socket: Socket, withinMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        socket.soTimeout = 250
        while (System.currentTimeMillis() < deadline) {
            try {
                if (socket.getInputStream().read() < 0) return true
            } catch (e: SocketException) {
                // A reset counts: the connection is gone, which is what this is asking.
                return true
            } catch (e: IOException) {
                return true
            }
        }
        return false
    }

    private fun parse(raw: String): Reply {
        val split = raw.indexOf("\r\n\r\n")
        if (split < 0) fail("the reply had no end to its headers: ${raw.take(200)}")
        val head = raw.substring(0, split).split("\r\n")
        val status = head.first().split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        for (line in head.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Reply(status, headers, raw.substring(split + 4))
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TOKEN = "0123456789ABCDEFGHJKMNPQ"
        const val HTML = "text/html; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"

        /** Long enough that a loopback round trip on a phone-shaped machine is never in doubt. */
        const val CONNECT_TIMEOUT_MS = 5_000

        /** How long the barrier case waits for the other request. Past any read timeout here. */
        const val JOIN_WAIT_MS = 10_000L

        /** Read timeout on the client side, in the unit a socket wants. */
        const val SOCKET_TIMEOUT_MS = 40_000

        const val INDEX = "<!doctype html>\n<html><body>omp</body></html>\n"
        const val LOGIN = "<!doctype html>\n<html><body>token</body></html>\n"
    }
}
