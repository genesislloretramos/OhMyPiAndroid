package omp.shell

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The HTTP seam against a real socket, and every rule about waiting on one.
 *
 * [HttpRequests] sends the request and owns the credential decisions. [SseStream] owns the rest of
 * what can be wrong with a connection that has gone quiet: the line assembly, the poll, the wall
 * clock and the order of the release. [SseReader] is the loop over the event grammar. All three
 * are the shipped classes — this file used to carry a copy of the transport, and a copy in a test
 * is a copy that stops being the thing the app runs the first time either is edited.
 *
 * A mock is worthless here, because every interesting thing about this seam happens in bytes that
 * no mock has: a token split across two TCP writes, a comment line, a 401 with a body, a redirect
 * that must not be followed, a connection reset in the middle of a reply, a read that has to be
 * interrupted by a close, and an endpoint that says nothing for several poll intervals. So this
 * starts a `com.sun.net.httpserver.HttpServer` on an ephemeral port and scripts the wire itself —
 * [Chunk] for a body that arrives in several packets with a flush between them, and [SilentServer]
 * for the one question only the far end can answer.
 *
 * The only thing not under test is the Android adapter, and there is almost nothing left of it:
 * four lines that call [SseStream.open] with a connect timeout. A JVM cannot run `:app`, so what
 * this file proves is that the class the adapter delegates to needs nothing but a connection.
 */
class HttpStreamTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var services: StubPlatformServices
    private val bodies = ArrayList<Pair<Int, List<Chunk>>>()

    /** What the server read off the socket, filled in by [serve]. */
    @Volatile
    private var received: Received? = null

    private class Received(val method: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** One TCP write: [text] goes out, then a flush, then a pause long enough to be its own packet. */
    private class Chunk(val text: String, val pauseMillis: Long = 40)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A thread per exchange, so a handler that writes in stages can still hold the response open.
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v1/chat", ::serve)
        server.start()
        val home = File(folder.root, "home").apply { mkdirs() }
        services = StubPlatformServices(home = home.path, initialDir = home.path)
        // The shipped factory, with the app's own poll interval and nothing else changed, so every
        // case below runs the class `:app` runs.
        services.stream = { url, method, headers, body ->
            SseStream.open(url, method, headers, body, CONNECT_TIMEOUT_MS)
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
        (server.executor as ExecutorService).shutdownNow()
    }

    private fun serve(exchange: HttpExchange) {
        val (status, chunks) = bodies.removeAt(0)
        // What the server actually received, which is the only honest place to read a request from.
        received = Received(
            exchange.requestMethod,
            exchange.requestHeaders.entries.associate { (k, v) -> k to v.joinToString(", ") },
            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
        )
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        // 0 means chunked, which is what a 200 SSE response is; an error response is a whole body.
        val length = if (status == 200) 0L else chunks.sumOf { it.text.toByteArray(StandardCharsets.UTF_8).size.toLong() }
        exchange.sendResponseHeaders(status, length)
        val out = exchange.responseBody
        for (chunk in chunks) {
            out.write(chunk.text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            if (chunk.pauseMillis > 0) Thread.sleep(chunk.pauseMillis)
        }
        out.close()
    }

    private fun script(status: Int, vararg chunks: Chunk) {
        bodies += status to chunks.toList()
    }

    private fun open(): HttpStream =
        services.httpStream("http://127.0.0.1:${server.address.port}/v1/chat", "POST", headers(), """{"a":1}""".toByteArray())

    private fun headers(): List<Pair<String, String>> = listOf(
        "Accept" to "text/event-stream",
        "Authorization" to "Bearer sk-test",
    )

    private fun drain(stream: HttpStream): List<String> {
        val out = ArrayList<String>()
        while (true) out += stream.next() ?: return out
    }

    // ---- the happy path ----------------------------------------------------------------

    @Test
    fun readsEventsThenDoneThenNull() {
        script(
            200,
            Chunk(": keep-alive\n\n"),
            Chunk("data: {\"delta\":\"Hel\"}\n\n"),
            Chunk("data: {\"delta\":\"lo\"}\n\n"),
            Chunk("data: [DONE]\n\n"),
        )
        services.httpStream("http://127.0.0.1:${server.address.port}/v1/chat", "POST", headers(), null).use { stream ->
            assertEquals(200, stream.status)
            assertEquals("{\"delta\":\"Hel\"}", stream.next())
            assertEquals("{\"delta\":\"lo\"}", stream.next())
            assertNull(stream.next())
            // [DONE] is the end, not a one-off: asking again keeps answering null.
            assertNull(stream.next())
        }
    }

    @Test
    fun aBodyWithNoDoneJustEnds() {
        script(200, Chunk("data: one\n\n"), Chunk("data: two\n\n"))
        open().use { assertEquals(listOf("one", "two"), drain(it)) }
    }

    @Test
    fun anEventSplitAcrossPacketsIsOneEvent() {
        // "par" + "tial" is one data line written in two flushes; a reader that treats one read()
        // as one event would answer "par" and then invent a second event.
        script(
            200,
            Chunk("data: par"),
            Chunk("tial\n"),
            Chunk("\n"),
            Chunk("data: [DONE]\n\n"),
        )
        open().use { stream ->
            assertEquals("partial", stream.next())
            assertNull(stream.next())
        }
    }

    @Test
    fun severalDataLinesMakeOneEvent() {
        script(200, Chunk("data: first\ndata: second\n\ndata: [DONE]\n\n"))
        open().use { stream ->
            assertEquals("first\nsecond", stream.next())
            assertNull(stream.next())
        }
    }

    @Test
    fun aDataLineWithNoSpaceAfterTheColonStillCarriesData() {
        script(200, Chunk("data:tight\n\ndata: [DONE]\n\n"))
        open().use { stream ->
            assertEquals("tight", stream.next())
            assertNull(stream.next())
        }
    }

    @Test
    fun fieldsOtherThanDataAreIgnored() {
        script(200, Chunk("event: message\nid: 42\nretry: 100\ndata: payload\n\ndata: [DONE]\n\n"))
        open().use { stream ->
            assertEquals("payload", stream.next())
            assertNull(stream.next())
        }
    }

    @Test
    fun anEmptyDataLineIsAnEmptyEvent() {
        script(200, Chunk("data:\n\ndata: [DONE]\n\n"))
        open().use { stream ->
            assertEquals("", stream.next())
            assertNull(stream.next())
        }
    }

    @Test
    fun theRequestCarriesItsMethodHeadersAndBody() {
        script(200, Chunk("data: [DONE]\n\n"))
        open().use { it.next() }
        val seen = received!!
        assertEquals("POST", seen.method)
        assertEquals("Bearer sk-test", seen.header("Authorization"))
        assertEquals("text/event-stream", seen.header("Accept"))
        assertEquals("""{"a":1}""", seen.body)
    }

    // ---- failures ----------------------------------------------------------------------

    @Test
    fun aRefusalCarriesTheStatus() {
        script(401, Chunk("""{"error":{"message":"Incorrect API key provided"}}"""))
        try {
            open().close()
            fail("expected an IOException for a 401")
        } catch (e: IOException) {
            assertEquals("HTTP 401", e.message!!.substringBefore(": "))
        }
    }

    @Test
    fun aRefusalCarriesTheBodyPrefix() {
        // 403 rather than 401: the JDK's HttpURLConnection hands back a null errorStream for a 401
        // because it goes down its authentication path first, so a 401 can only assert the status.
        val body = """{"error":{"message":"Incorrect API key provided: sk-test"}}"""
        script(403, Chunk(body))
        try {
            open().close()
            fail("expected an IOException for a 403")
        } catch (e: IOException) {
            assertTrue("status missing from: ${e.message}", e.message!!.contains("403"))
            assertTrue("body missing from: ${e.message}", e.message!!.contains("Incorrect API key provided"))
        }
    }

    @Test
    fun aLongErrorBodyIsCutAtFiveHundredAndTwelveBytes() {
        val body = "x".repeat(900)
        script(500, Chunk(body))
        try {
            open().close()
            fail("expected an IOException for a 500")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("500"))
            assertTrue(e.message!!.contains("x".repeat(512)))
            assertTrue("body was not truncated", !e.message!!.contains("x".repeat(513)))
        }
    }

    // ---- the socket's lifetime ----------------------------------------------------------

    @Test
    fun aThrowingLineSourceStillReleasesTheConnection() {
        // The defect this is here for: a dropped connection in the middle of a reply used to
        // propagate out of next() with the socket still open, and nothing else in the program runs
        // at that moment — the exception unwinds past the code that would have closed it. So
        // `released` is the whole assertion: what is being asked is not "did it throw" but "did the
        // connection go back".
        val released = AtomicReference(false)
        val lines = lineSource(listOf("data: one", "", "data: two"))
        val reader = SseReader(lines::next, { released.set(true) })
        assertEquals("one", reader.next())
        try {
            reader.next()
            fail("expected the transport failure to reach the caller")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertTrue("the connection was not released", released.get())
    }

    @Test
    fun aFailureAfterAnotherThreadClosedDoesNotReleaseASecondTime() {
        // The Ctrl-C case arriving in the other order: close() ran while a read was in flight, and
        // that read fails afterwards. Releasing again would be the second close of a connection the
        // first one already gave back, and the caller still has to be told the read failed.
        val releases = AtomicInteger(0)
        var reader: SseReader? = null
        val lines = object : Iterator<String> {
            private var at = 0
            override fun hasNext(): Boolean = true
            override fun next(): String = when (at++) {
                0 -> "data: one"
                1 -> ""
                // The other thread's Ctrl-C arriving while this read is in flight.
                else -> {
                    reader!!.close()
                    throw IOException("connection reset")
                }
            }
        }
        reader = SseReader(lines::next, { releases.incrementAndGet() })
        assertEquals("one", reader!!.next())
        try {
            reader!!.next()
            fail("expected the transport failure to reach the caller")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertEquals(1, releases.get())
        assertNull("a closed reader kept reading", reader!!.next())
    }

    @Test
    fun aFailingReleaseDoesNotDisplaceTheFailureTheCallerCame() {
        val reader = SseReader({ throw IOException("read timed out") }, { throw IllegalStateException("gone") })
        try {
            reader.next()
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals("read timed out", e.message)
        }
    }

    @Test
    fun aConnectionResetMidStreamThrowsAndReleasesTheSocket() {
        // The same failure over a real socket rather than a stub: a server that sends one event and
        // then resets the connection, which is what a proxy dropping a model reply looks like. The
        // reset is armed only once the first event has been read, so the event is never in doubt.
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val read = CountDownLatch(1)
        val serving = Thread {
            listener.accept().use { socket ->
                readRequest(socket)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\ndata: one\n\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
                read.await(10, TimeUnit.SECONDS)
                // A reset rather than a close: an RST, so the client's next read fails instead of
                // quietly reaching end of stream.
                socket.setSoLinger(true, 0)
            }
        }
        serving.isDaemon = true
        serving.start()

        val released = AtomicReference(false)
        val conn = HttpRequests.open(
            "http://127.0.0.1:${listener.localPort}/v1/chat", "GET", headers(), null, 5_000, 5_000,
        )
        val stream = SseStream(
            conn,
            HttpRequests.status(conn, "http://127.0.0.1/"),
            SseStream.STREAM_SILENCE_LIMIT_MS,
            System::currentTimeMillis,
            onRelease = { released.set(true) },
        )
        try {
            assertEquals("one", stream.next())
            read.countDown()
            try {
                stream.next()
                fail("expected an IOException for a reset connection")
            } catch (e: IOException) {
                // Connection reset, or a read timeout: both are the transport failing, and both are
                // what the caller has to be told.
            }
            assertTrue("the connection was not released", released.get())
        } finally {
            stream.close()
            listener.close()
        }
    }

    /** A line source that hands out [lines] and then throws the way a dying socket does. */
    private fun lineSource(lines: List<String>): Iterator<String> =
        object : Iterator<String> {
            private var at = 0
            override fun hasNext(): Boolean = true
            override fun next(): String =
                if (at < lines.size) lines[at++] else throw IOException("connection reset")
        }

    // ---- closing -----------------------------------------------------------------------

    @Test
    fun closeIsSafeToCallTwice() {
        script(200, Chunk("data: one\n\n"), Chunk("data: two\n\n"))
        val stream = open()
        assertEquals("one", stream.next())
        stream.close()
        stream.close()
        assertNull(stream.next())
    }

    @Test
    fun closingMidStreamStopsTheReader() {
        // A server that keeps writing after the reader walked away must not be able to wedge it.
        script(200, Chunk("data: one\n\n"), Chunk("data: two\n\n"), Chunk("data: [DONE]\n\n", 200))
        val stream = open()
        assertEquals("one", stream.next())
        stream.close()
        assertNull(stream.next())
    }

    @Test
    fun aStalledReadIsStoppedByCloseWhileTheEndpointIsStillSilent() {
        // What the user is actually promised: press Ctrl-C on an answer that is not coming, and it
        // stops. The agent's thread is parked in next() with the endpoint saying nothing, the
        // Ctrl-C arrives on another thread, and the read has to come back from the close rather
        // than from the server choosing to speak. The server's silence here is [LONG_SILENCE_MS]
        // and the window below is well inside it, so a read that only ended when the server got
        // round to sending something would fail this, and a read that could not be ended at all
        // would fail it by never returning. Everything here is a real socket: the lock and the read
        // timeout this is about belong to the JDK, and a double that recorded which call came first
        // would only be asserting the source text.
        script(200, Chunk("data: one\n\n"), Chunk("", LONG_SILENCE_MS))
        val stream = open()
        val parked = parkARead(stream)
        val closing = closeOnAnotherThread(stream)
        // The bound is the poll interval, and it is that rather than something looser because that
        // is the whole claim: a close() is noticed at the next checkpoint, so it lands within
        // [SseStream.STREAM_POLL_MS] plus the time to run the release. A wider bound would pass
        // against a transport that polled once a second, which is not the one that ships.
        parked.join(SeveralPollsMs)
        assertFalse("close() did not stop the stalled read", parked.isAlive)
        closing.join(SeveralPollsMs)
        assertFalse("the release itself is still stuck", closing.isAlive)
    }

    @Test
    fun closeBeforeDisconnectLeavesTheSocketHeldUntilTheReadReturns() {
        // The order, asked of the far end rather than of the code. Nothing can interrupt a read
        // that is already parked in a socket — `disconnect()` returns at once and the read stays
        // where it is — so what a release can still decide is *when* the connection goes back, and
        // a stream that is closing cannot be closed until the read returns, because the read is
        // holding the decoder's lock. Close first and the disconnect is still sitting on the line
        // after it: the socket is the far end's problem for as long as the endpoint stays quiet.
        // Both are built with a 30-second read timeout rather than the app's 250 ms poll, so the
        // read cannot come back on its own inside [PARKED_MS] and the only thing that can give the
        // connection back is the order. [SilentServer] can see it — a read on the server's side
        // returns end-of-file once the client has gone and times out while it is still there —
        // and both orders are run back to back against the same silence, because either one alone
        // would be a statement about this file rather than about the order.
        SilentServer().use { closesFirst ->
            val stream = openWith(closesFirst.url, readTimeoutMs = READ_TIMEOUT_MS, release = { close, _ -> close() })
            parkARead(stream)
            closeOnAnotherThread(stream)
            assertFalse(
                "the old order gave the connection back, so the order cannot be what matters",
                closesFirst.awaitClientWentAway(PARKED_MS),
            )
        }
        SilentServer().use { disconnectsFirst ->
            val stream = openWith(disconnectsFirst.url, readTimeoutMs = READ_TIMEOUT_MS)
            parkARead(stream)
            closeOnAnotherThread(stream)
            assertTrue(
                "the shipped order did not give the connection back",
                disconnectsFirst.awaitClientWentAway(PARKED_MS),
            )
        }
    }

    /**
     * An endpoint that sends one event and then says nothing for as long as the test takes, and
     * that can be asked the one question only the far end can answer: did the client give the
     * connection back? The answer is read off the socket — end-of-file once the client has gone,
     * a timeout while it is still there — rather than off anything this side can assert.
     */
    private class SilentServer : java.io.Closeable {

        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        @Volatile
        private var clientWentAway = false

        val url: String get() = "http://127.0.0.1:${listener.localPort}/v1/chat"

        init {
            val serving = Thread {
                try {
                    listener.accept().use { socket ->
                        readRequest(socket)
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\ndata: one\n\n"
                                    .toByteArray(StandardCharsets.UTF_8),
                            )
                            flush()
                        }
                        socket.soTimeout = WATCH_POLL_MS.toInt()
                        while (!clientWentAway) {
                            val gone = try {
                                socket.getInputStream().read() < 0
                            } catch (e: SocketTimeoutException) {
                                false
                            } catch (e: IOException) {
                                // A reset is the other way a dropped connection arrives.
                                true
                            }
                            if (gone) clientWentAway = true
                        }
                    }
                } catch (e: IOException) {
                    // The listener was closed under the test, which is the end of it either way.
                }
            }
            serving.isDaemon = true
            serving.start()
        }

        /** @return whether the client dropped the connection within [millis], waiting for it. */
        fun awaitClientWentAway(millis: Long): Boolean {
            val until = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < until) {
                if (clientWentAway) return true
                Thread.sleep(WATCH_POLL_MS)
            }
            return clientWentAway
        }

        override fun close() = listener.close()
    }

    @Test
    fun aSilenceLongerThanThePollIntervalStillDeliversEveryCharacter() {
        // Both halves of the poll loop at once, and the second is the one that would have caught
        // the bug. A delta is written here in two halves with a silence several poll intervals
        // long between them, so the first half is sitting in the reader when the checkpoint fires
        // and the second has not been sent. Under a `BufferedReader` the event arrives as
        // `rtial"}` — `fill()` zeroes its count before it reads, so the timeout takes the first
        // half with it — and that is not a token any grammar can recover from.
        //
        // [readChars] is the part that makes this a test rather than a description. A
        // `BufferedReader` only loses anything on the read path that goes through `fill()`, and
        // that is the path taken when the read is *smaller* than its buffer; a read of the class's
        // own 8 KiB is handed straight to the stream and loses nothing, so a
        // `BufferedReader` at that size would pass this test. A read of [TINY_READ_CHARS] cannot
        // avoid `fill()` at any buffer size a reader might have, so this fails if a buffering
        // reader comes back — and the shipped read size is not what is under test, only that the
        // assembly does not depend on it.
        //
        // The silence is not a limit either: the reply keeps coming, in full, long after the
        // poll fired, and [SseStream.STREAM_SILENCE_LIMIT_MS] is minutes away.
        script(
            200,
            Chunk("""data: {"delta":"pa""", SILENCE_OVER_POLL_MS),
            Chunk("""rtial"}""" + "\n\n", SILENCE_OVER_POLL_MS),
            Chunk("data: [DONE]\n\n", SILENCE_OVER_POLL_MS),
        )
        val stream = openWith(readTimeoutMs = SHORT_POLL_MS, readChars = TINY_READ_CHARS)
        assertEquals("""{"delta":"partial"}""", stream.next())
        assertNull("a silence was taken for the end of the stream", stream.next())
    }

    @Test
    fun aDeltaSplitAcrossACheckpointIsWholeAtTheShippedReadSizeToo() {
        // The same corruption, driven at the size the app actually reads, because a fix that only
        // held for one buffer size would be a fix for the test rather than for the stream. A line
        // long enough to need more than one read, with a checkpoint between the two reads, is the
        // case where a reader that drops what a timed-out read delivered loses the middle.
        val delta = "x".repeat(SseStream.READ_CHARS + 64)
        script(
            200,
            Chunk("data: {\"delta\":\"$delta", SILENCE_OVER_POLL_MS),
            Chunk("\"}\n\n", SILENCE_OVER_POLL_MS),
            Chunk("data: [DONE]\n\n", SILENCE_OVER_POLL_MS),
        )
        val stream = openWith(readTimeoutMs = SHORT_POLL_MS)
        assertEquals("""{"delta":"$delta"}""", stream.next())
    }

    @Test
    fun aReplySilentPastTheLimitIsToldToTheUserRatherThanWaitedOn() {
        // The other half of the design: the poll is a checkpoint, and this is the clock it checks.
        // A stream that says nothing for longer than a reply is worth waiting for has to end with
        // the user told and something to act on, not with a thread parked forever — and it has to
        // do that while the connection is still nominally fine, which is the case a socket-level
        // error would never reach.
        script(200, Chunk("data: one\n\n"), Chunk("", LONG_SILENCE_MS))
        val stream = openWith(readTimeoutMs = SHORT_POLL_MS, silenceLimitMs = REACHABLE_SILENCE_LIMIT_MS)
        assertEquals("one", stream.next())
        try {
            stream.next()
            fail("expected the limit to be reported rather than waited out")
        } catch (e: IOException) {
            // Three things a user can act on: which host went quiet, how long it was quiet, and
            // what to try. A `SocketTimeoutException` carrying the word "timed" has none of them.
            assertTrue("the host is not named: ${e.message}", e.message!!.contains("127.0.0.1"))
            assertTrue("the wait is not quantified: ${e.message}", e.message!!.contains("1 second"))
            assertTrue("nothing is offered to try: ${e.message}", e.message!!.contains("Try again"))
        }
        // And the wall clock, not the poll, ended it: the read timeout is a fifth of a second, so
        // a poll that had been treated as a limit would have thrown about then with a different
        // message and a thread that parked for a second.
        assertNull("the connection was not released with the failure", stream.next())
    }

    /**
     * The guard on the bound itself, and the reason the read above is not the whole of it.
     *
     * [aReplySilentPastTheLimitIsToldToTheUserRatherThanWaitedOn] calls [HttpStream.next] on the
     * test's own thread, so a transport that stopped consulting the wall clock would leave that
     * test parked inside a socket read forever: not red, not slow, hung — which is how a suite
     * stops finishing and nobody can tell which of a hundred tests did it. Here the read is on a
     * thread of its own that the test is willing to abandon, so the same regression is a failed
     * assertion naming the bound instead of a CI that never returns.
     *
     * **The bound is [SseStream.STREAM_POLL_MS] plus the limit, and the window is three times
     * that.** Generous on purpose: this asserts that a read against an endpoint that never speaks
     * comes back at all and comes back *bounded*, not that it comes back quickly, so a loaded
     * machine that took twice the stated bound would still pass. The constant it reads is the
     * shipped one, so lengthening the poll interval or the limit to hide a hang fails here.
     */
    @Test
    fun aReadThatOutlivesItsBoundIsAFailedAssertionAndNotAHungSuite() {
        val limit = 300L
        script(200, Chunk("data: one\n\n"), Chunk("", LONG_SILENCE_MS))
        val stream = openWith(readTimeoutMs = SHORT_POLL_MS, silenceLimitMs = limit)
        assertEquals("one", stream.next())

        val read = AtomicReference<Throwable?>()
        val parked = Thread {
            read.set(runCatching { stream.next() }.exceptionOrNull())
        }
        parked.isDaemon = true
        parked.start()

        val window = limit + SseStream.STREAM_POLL_MS
        parked.join(window * 3)
        try {
            assertFalse(
                "a read against a silent endpoint outlived ${window * 3}ms, which is three times " +
                    "the ${window}ms it is given: the wall clock is not ending this read",
                parked.isAlive,
            )
            // Bounded is the claim, and the bound is the class's: the read ends on the clock, so
            // it fails the way a user is told about, naming the host and the wait.
            val failure = read.get()
            assertTrue("the read came back without saying why: $failure", failure is IOException)
            assertTrue("the host is not named: $failure", failure!!.message!!.contains("127.0.0.1"))
        } finally {
            parked.join(SeveralPollsMs)
            stream.close()
        }
    }

    /**
     * Take the first event off [stream] and leave a second thread parked in [HttpStream.next]
     * with nothing left to read: the agent's thread, on an endpoint that has gone quiet. The
     * thread comes back still inside the read, which is the state a Ctrl-C has to be able to end.
     */
    private fun parkARead(stream: HttpStream): Thread {
        assertEquals("one", stream.next())
        val parked = Thread { runCatching { stream.next() } }
        parked.isDaemon = true
        parked.start()
        Thread.sleep(PARK_MS)
        assertTrue("the second read came back instead of parking on the socket", parked.isAlive)
        return parked
    }

    /** Close [stream] where a Ctrl-C arrives: on a different thread from the one inside the read. */
    private fun closeOnAnotherThread(stream: HttpStream): Thread =
        Thread { stream.close() }.apply { isDaemon = true; start() }

    /**
     * The shipped [SseStream], with what a case is allowed to vary and nothing else: the read
     * timeout it is built with, which is the poll interval, the order its release runs in, and the
     * size of one read. The line assembly, the checkpoint loop and the wall clock are the class's
     * own, which is the whole point of the class being in `:core`.
     */
    private fun openWith(
        url: String = "http://127.0.0.1:${server.address.port}/v1/chat",
        readTimeoutMs: Int = SseStream.STREAM_POLL_MS,
        silenceLimitMs: Long = SseStream.STREAM_SILENCE_LIMIT_MS,
        release: (close: () -> Unit, disconnect: () -> Unit) -> Unit = { close, disconnect ->
            disconnect()
            close()
        },
        onRelease: () -> Unit = {},
        readChars: Int = SseStream.READ_CHARS,
    ): HttpStream = overConnection(
        url = url,
        readTimeoutMs = readTimeoutMs,
        silenceLimitMs = silenceLimitMs,
        release = release,
        onRelease = onRelease,
        readChars = readChars,
    )

    /**
     * [SseStream] over a connection opened here rather than through [SseStream.open], so a case
     * can watch what the release does — the connection going back — from outside the class.
     */
    private fun overConnection(
        url: String = "http://127.0.0.1:${server.address.port}/v1/chat",
        readTimeoutMs: Int = SseStream.STREAM_POLL_MS,
        silenceLimitMs: Long = SseStream.STREAM_SILENCE_LIMIT_MS,
        release: (close: () -> Unit, disconnect: () -> Unit) -> Unit = { close, disconnect ->
            disconnect()
            close()
        },
        onRelease: () -> Unit = {},
        readChars: Int = SseStream.READ_CHARS,
        onStatus: (HttpURLConnection) -> Int = { HttpRequests.status(it, url) },
    ): HttpStream {
        val conn = HttpRequests.open(url, "POST", headers(), null, CONNECT_TIMEOUT_MS, readTimeoutMs)
        return try {
            SseStream(
                conn,
                onStatus(conn),
                silenceLimitMs,
                System::currentTimeMillis,
                release,
                onRelease,
                readChars,
            )
        } catch (e: Throwable) {
            conn.disconnect()
            throw e
        }
    }

    // ---- the request ---------------------------------------------------------------------


    @Test
    fun aRedirectIsReportedRatherThanFollowed() {
        // The credential case: this request carries an Authorization header, and the JDK re-sends
        // request headers when it follows a redirect. So the second server must receive *nothing* —
        // not a request without the header, no request at all — and the caller must be told the 3xx
        // and the host it pointed at, which is the part of a redirect a user can act on.
        //
        // The target is named `localhost` rather than `127.0.0.1` on purpose: both are this
        // machine, so the message can only say which one it was sent to if the two spellings
        // differ. The target server listens on every interface, so a regression that *did* follow
        // would reach it and be caught by the count rather than by a connect failure.
        val hits = AtomicInteger(0)
        val seen = AtomicReference<Map<String, List<String>>>(emptyMap())
        val elsewhere = HttpServer.create(InetSocketAddress(0), 0)
        elsewhere.executor = Executors.newCachedThreadPool()
        elsewhere.createContext("/") { exchange ->
            hits.incrementAndGet()
            seen.set(exchange.requestHeaders.mapValues { (_, v) -> v.toList() })
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        elsewhere.start()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.executor = Executors.newCachedThreadPool()
        origin.createContext("/v1/chat") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${elsewhere.address.port}/stolen")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        origin.start()
        try {
            try {
                services.httpStream(
                    "http://127.0.0.1:${origin.address.port}/v1/chat", "POST", headers(), null,
                ).close()
                fail("expected an IOException for a 302")
            } catch (e: IOException) {
                val message = e.message!!
                assertTrue("status missing from: $message", message.contains("302"))
                assertTrue("the host it pointed at is missing from: $message", message.contains("localhost"))
            }
            assertEquals("a request reached the redirect's host", 0, hits.get())
            assertFalse("the credential was re-sent", seen.get().containsKey("Authorization"))
        } finally {
            origin.stop(0)
            elsewhere.stop(0)
        }
    }

    @Test
    fun aRepeatedHeaderNameIsRefusedBeforeAnythingIsSent() {
        // setRequestProperty overwrites, so two values for one name would go out as one and the
        // caller would never know. The seam says so instead, naming the header.
        // (no script: the point is that nothing arrives, so there is nothing to answer)
        try {
            services.httpStream(
                "http://127.0.0.1:${server.address.port}/v1/chat", "POST",
                listOf("Accept" to "text/event-stream", "accept" to "application/json"),
                null,
            )
            fail("expected an IllegalArgumentException for a repeated header")
        } catch (e: IllegalArgumentException) {
            assertTrue("the header is not named: ${e.message}", e.message!!.contains("Accept"))
        }
        assertNull("a refused request was sent anyway", received)
    }

}

/** The connect timeout the app uses, spelled out rather than imported from an Android class. */
private const val CONNECT_TIMEOUT_MS = 15_000

/**
 * A read timeout long enough that a read parked on it cannot come back inside any window below —
 * used to show that a `close()` is what ended the read and not the connection's own timeout.
 */
private const val READ_TIMEOUT_MS = 30_000

/**
 * The app's real limit, in miniature, so the case that has to reach past it does not wait five
 * minutes. Every other case below runs with the shipped [SseStream.STREAM_SILENCE_LIMIT_MS].
 */
private const val REACHABLE_SILENCE_LIMIT_MS = 1_000L


/**
 * How long the server leaves a reply open after its first event. Longer than every window a test
 * below waits in, so a read that only ends when the server chooses to speak cannot pass one.
 */
private const val LONG_SILENCE_MS = 20_000L

/**
 * How long a `close()` is given to end a read parked on a socket: a few poll intervals, so a
 * slower poll than the one that ships would fail and the current one is not being timed by luck.
 */
private const val SeveralPollsMs = 2_000L

/** How long a release is watched before it is called stuck, against a read that will not time out. */
private const val PARKED_MS = 2_000L

/**
 * A read small enough that a [java.io.BufferedReader] between the stream and the socket would
 * have to go through `fill()` — the path that discards what a timed-out read had already taken.
 * The corruption is real at this size and absent at the shipped 8 KiB, which is why this is the
 * size under test rather than the one the app uses.
 */
private const val TINY_READ_CHARS = 32


/** A poll short enough that several of them fit inside the silences a test scripts. */
private const val SHORT_POLL_MS = 200

/** A gap the poll above fires in, several times over, before the next byte arrives. */
private const val SILENCE_OVER_POLL_MS = 900L

/** Long enough for a second `next()` to be inside the read, short enough not to be the test. */
private const val PARK_MS = 200L

/** How often [SilentServer] asks its socket whether the client is still there. */
private const val WATCH_POLL_MS = 100L


/** Reads an HTTP request's head, so the server side is not writing while the client is still sending. */
private fun readRequest(socket: Socket) {
    val body = socket.getInputStream()
    var seen = 0
    while (seen < 4) {
        val b = body.read()
        if (b < 0) return
        if (b == '\n'.code) seen++
    }
}
