package com.omp.terminal.web

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One request, as the server understood it.
 *
 * Header names are matched case-insensitively through [header], because HTTP says they are and a
 * hand-written parser that compares them exactly is a server that works against its own tests and
 * against nothing else. [query] holds the decoded pairs, and the raw query string is not kept:
 * there is no caller that wants the escaped form, and keeping it would be a second spelling of
 * every value the server already has.
 */
data class Request(
    val method: String,
    /** The percent-decoded path, with no query string: `/api/conversations/notes/transcript`. */
    val path: String,
    /** The query string's pairs, decoded, plus any `{name}` segment the path carried. */
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    /** The body, exactly as many bytes as `Content-Length` said. Never null; zero-length if none. */
    val body: ByteArray,
) {

    /** The value of [name], whatever case it arrived in, or null. */
    fun header(name: String): String? = headers[name.lowercase()]

    /** The value of [name] in the query string, or null. */
    fun param(name: String): String? = query[name]

    /** The body as UTF-8 text, which is what every JSON route on this server sends. */
    fun text(): String = String(body, StandardCharsets.UTF_8)

    /**
     * The value of the cookie called [name], or null.
     *
     * A browser attaches a cookie to everything it fetches from an origin, including the two
     * things a page cannot put a header on: a stylesheet and a streaming response. That is the
     * whole reason this exists rather than a header alone, and it is why the cookie is only ever
     * a second way in and never the only one.
     */
    fun cookie(name: String): String? {
        val jar = headers["cookie"] ?: return null
        for (pair in jar.split(';')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq).trim() == name) return pair.substring(eq + 1).trim()
        }
        return null
    }
}

/**
 * What a route writes its answer to.
 *
 * **Two shapes, and the difference is the whole design.** A route that knows its whole answer calls
 * [send] and the bytes go out with a `Content-Length`; a route that is producing something as it
 * happens — one answer to a model, arriving a token at a time — calls [beginStream] and then
 * [chunk] as often as it likes.
 *
 * **A stream is framed by the connection closing, not by a length.** There is no `Content-Length`
 * on it and no chunked encoding, because a chunked body means writing a chunk parser by hand and a
 * browser's `fetch` reader does it for us while `curl` does not. HTTP/1.1 §6.3 allows a response
 * body delimited by connection close, every client accepts it, and this server sends
 * `Connection: close` on every reply anyway — one request per connection, which is the right trade
 * for a loopback server whose clients are a handful of tabs and which has no pipelining to gain by
 * holding a socket open.
 *
 * The two are mutually exclusive on one response and [send] after [beginStream] is a programming
 * error rather than something to paper over: a route that has already promised a stream cannot
 * afterwards claim a length it does not know.
 */
class Response internal constructor(private val out: OutputStream) {

    /** True once the status line has gone out. A route that wrote nothing is a 500. */
    var started: Boolean = false
        private set

    /** A whole body, with its length. */
    fun send(
        status: Int,
        contentType: String,
        body: ByteArray,
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        check(!started) { "response: the status line has already been written" }
        started = true
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonFor(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    /** The 200 and the headers of a stream, with no length. Everything after this is a [chunk]. */
    fun beginStream(contentType: String, headers: List<Pair<String, String>> = emptyList()) {
        check(!started) { "response: the status line has already been written" }
        started = true
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        // Two headers, both about a proxy that is not here. This server binds to loopback and a
        // browser on the phone is not behind one, but a user who puts one there to reach the phone
        // from a desktop should not get a "stream" that arrives all at once at the end, nor a
        // cached copy of their own conversation.
        head.append("Cache-Control: no-store\r\n")
        head.append("X-Accel-Buffering: no\r\n")
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    /** One piece of a stream. Flushed every time, because the point of one is that it arrives. */
    fun chunk(text: String) {
        check(started) { "response: chunk before beginStream" }
        out.write(text.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }
}

/** Answers one request. Runs on a pool thread, owns that socket, and writes the whole reply. */
fun interface Handler {
    fun handle(request: Request, response: Response)
}

/**
 * One path, one method, one handler.
 *
 * **A `{name}` segment is captured rather than matched literally**, and it arrives as a query
 * parameter under the same name, so a route reads `request.param("name")` and does not care that
 * the name came out of the path rather than out of a question mark. That is the whole templating
 * this server has, and it is enough because every route on it is a fixed set of segments with
 * names in them — a conversation's folder is one segment, and [omp.vm.workspace.Workspace.open]
 * is the thing that decides whether that segment is a conversation or a `..`.
 */
class Route internal constructor(
    /** The path, with `{name}` for a segment that is captured. */
    val pattern: String,
    val method: String,
    val handler: Handler,
) {

    private val segments: List<String> = pattern.trim('/').split('/')

    /**
     * Whether [path] is this route's, writing any captured segments into [into].
     *
     * Compared whole and exactly, in both directions: a pattern only matches a path with the same
     * number of segments, and a literal segment is compared with `==`. A path that arrives with a
     * trailing slash, an empty segment or a doubled one matches nothing, which is what makes the
     * set of routes a closed list rather than something a client can walk around.
     */
    internal fun accepts(path: String, into: MutableMap<String, String>): Boolean {
        val parts = path.trim('/').split('/')
        if (parts.size != segments.size) return false
        for (i in parts.indices) {
            val want = segments[i]
            when {
                want.startsWith("{") && want.endsWith("}") ->
                    into[want.substring(1, want.length - 1)] = parts[i]
                want != parts[i] -> return false
            }
        }
        return true
    }

    override fun toString(): String = "$method $pattern"
}

/** Declares one route. The path may carry `{name}` segments, which arrive as query parameters. */
fun route(pattern: String, method: String, handler: Handler): Route =
    Route(pattern, method, handler)

/**
 * A refusal raised while reading a request, and the status it is answered with.
 *
 * Its own type because every one of these is a decision about a *request* rather than about a
 * route, and all of them happen before a route has been chosen: a head that never ends, a body
 * over the cap, a line that is not a request at all.
 */
private class Refused(val status: Int, val detail: String) : IOException(detail)

/**
 * A loopback HTTP/1.1 server, written here because this project takes no dependencies and a
 * library would be the only one it has.
 *
 * ### What it does, and what it deliberately does not
 *
 * `GET` and `POST`, a body, a fixed set of routes, and correct status codes, `Content-Type` and
 * `Content-Length` on every reply. **No WebSocket**: the one thing that streams here is a model
 * answer arriving as text, and server-sent events are a byte stream over an ordinary HTTP response
 * — no upgrade handshake, no framing this file would have to invent, and reconnection the browser
 * does for free. **No keep-alive**, because one request per connection is the whole cost model of
 * a server on a phone, and a client that wants a second request opens a second socket.
 *
 * **A path it does not know is a real 404 with a body**, not an empty 200 and not a stack trace. A
 * front-end asking for a route a later build removed has to be able to tell that from a route that
 * answered.
 *
 * ### The three ways this could hang, and what stops each
 *
 *  - **A client that connects and says nothing.** [READ_TIMEOUT_MS] on the socket. A request on
 *    loopback is one write of a few hundred bytes and arrives in well under a millisecond, so five
 *    seconds is five thousand times that and short enough that a handful of abandoned sockets
 *    cannot pin [THREADS] threads for long. It is a *read* timeout, so it bounds the head and the
 *    body and nothing else: an answer that takes four minutes to stream is unaffected, because by
 *    then the server is writing and is not reading this socket at all.
 *  - **A body over [MAX_BODY_BYTES].** `413`, before a byte of it is read. A phone is not going to
 *    be sent a gigabyte by anything honest, and a server that reads first and complains afterwards
 *    has already spent the memory it was refusing to spend.
 *  - **More clients than threads.** A fixed pool with a bounded queue, and `503` past that rather
 *    than an unbounded backlog. A pool that grows to the number of open sockets is a pool a
 *    browser tab left open all afternoon has made enormous.
 *
 * ### Shutdown
 *
 * [stop] closes the listening socket, closes every connection still held — which is what ends a
 * [Response.chunk] in flight — and shuts the pool down. A closed listening socket has released its
 * port, and `SO_REUSEADDR` is set before the bind so the `TIME_WAIT` of the connections it served
 * cannot block the next one, so a service that stops and starts takes the same port back.
 *
 * There is no `android.*` in this file and no platform type in anything it touches, which is what
 * lets [LocalServerTest] drive it over a real loopback socket on the JVM.
 */
class LocalServer(
    routes: List<Route>,
    /** The address to bind. Loopback by default, and the default is the only value this app uses. */
    private val bind: InetAddress = InetAddress.getByName(LOOPBACK),
    /** 0 asks the OS for a free port; [port] then reports the one it got. */
    private val wantedPort: Int = 0,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    private val threads: Int = THREADS,
) {

    private val routes: List<Route> = routes
    private val server: ServerSocket = ServerSocket()
    private val live = ConcurrentHashMap.newKeySet<Socket>()
    private val running = AtomicBoolean(false)
    private val pool = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(QUEUE),
    )
    private var acceptor: Thread? = null

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(bind, wantedPort))
    }

    /** The port this server is on: the one it asked for, or the one the OS gave it. */
    val port: Int get() = server.localPort

    /** The address a browser on this device should be pointed at. */
    fun url(): String = "http://${bind.hostAddress}:$port"

    /**
     * Starts accepting, and returns immediately. The acceptor is its own daemon thread, so a
     * server that was started and never stopped does not by itself keep a JVM alive — and
     * [LocalServerTest] can therefore leave one running if a test fails before its `@After`.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread({ accept() }, "omp-web-accept")
        thread.isDaemon = true
        thread.start()
        acceptor = thread
    }

    /**
     * Stops, and does not return until the port is free.
     *
     * Every connection in `live` is closed as well as the listener, because a stream being written
     * to a socket nobody is reading would otherwise keep its pool thread — and with it the turn of
     * a conversation — alive for as long as the model took to finish.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            server.close()
        } catch (e: IOException) {
            // Already closed, so there is nothing left to release.
        }
        for (socket in live) {
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
        live.clear()
        pool.shutdownNow()
        pool.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        acceptor?.join(SHUTDOWN_WAIT_MS)
        acceptor = null
    }

    private fun accept() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                // stop() closed the listener, which is the only way this loop should end.
                if (running.get()) continue else return
            }
            live.add(socket)
            try {
                pool.execute { serve(socket) }
            } catch (e: RuntimeException) {
                // The queue is full: everyone who could answer is already answering. Saying so is
                // better than dropping the connection, which a browser reports as a network error
                // with nothing in it.
                live.remove(socket)
                try {
                    val out = BufferedOutputStream(socket.getOutputStream())
                    Response(out).send(503, TEXT, BUSY.toByteArray(StandardCharsets.UTF_8))
                    socket.close()
                } catch (e2: IOException) {
                }
            }
        }
    }

    /** One connection: one request, one reply, and then the socket is closed whatever happened. */
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream())
            val response = Response(out)
            val request = try {
                readRequest(input)
            } catch (e: Refused) {
                response.send(e.status, TEXT, (e.detail + "\n").toByteArray(StandardCharsets.UTF_8))
                return
            }
            try {
                dispatch(request, response)
            } finally {
                try {
                    out.flush()
                } catch (e: IOException) {
                    // The client is gone. The reply either landed or it did not, and neither is
                    // something this thread can do anything about now.
                }
            }
        } catch (e: SocketTimeoutException) {
            // A client that stopped mid-head. No reply: there is no request to answer, and a 408
            // written to a socket the client has abandoned is bytes nobody will read.
        } catch (e: IOException) {
            // A reset, a broken pipe, a client that closed before it finished asking. All normal,
            // and none of them a reason to take the server down.
        } finally {
            live.remove(socket)
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
    }

    private fun dispatch(request: Request, response: Response) {
        val here = HashMap(request.query)
        var methodMismatch = false
        for (route in routes) {
            if (!route.accepts(request.path, here)) continue
            if (route.method != request.method) {
                methodMismatch = true
                continue
            }
            route.handler.handle(request.copy(query = here), response)
            if (!response.started) {
                // A route that returned without answering. Better a 500 that says so than a
                // connection closed with nothing on it, which a browser reports as a network error
                // and a person as "it just did not load".
                response.send(500, TEXT, CRASH.toByteArray(StandardCharsets.UTF_8))
            }
            return
        }
        if (methodMismatch) {
            val allow = routes.filter { it.accepts(request.path, HashMap()) }
                .joinToString(", ") { it.method }
                .ifEmpty { "GET" }
            response.send(
                405,
                TEXT,
                "405: $allow is what this path answers\n".toByteArray(StandardCharsets.UTF_8),
                listOf("Allow" to allow),
            )
            return
        }
        response.send(
            404,
            TEXT,
            "404: there is no route '${request.path}' on this server\n"
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    // ---- the request parser ---------------------------------------------------------------------

    /**
     * The head, then the body, and nothing more.
     *
     * **Byte at a time for the head.** It is at most [MAX_HEAD_BYTES] and normally arrives in one
     * packet, and it is delimited by a blank line rather than by a length, so the bytes either
     * side of that line have to be found where they are. A `BufferedReader` in between would add
     * a `fill()` whose size decides how much of a partially-sent head this method sees, which is
     * exactly the sort of thing that passes on a fast machine and fails on a slow one.
     *
     * **The declared length is believed, and checked against the cap before anything is read.**
     * `Content-Length` is what the client says it will send; a body over the cap is refused on the
     * strength of the claim alone.
     *
     * **A chunked request body is refused with 411.** A `fetch` sending a string body sets
     * `Content-Length`, which is the only client this server has, and a chunked *request* parser
     * would be code no route here could ever reach.
     */
    private fun readRequest(input: InputStream): Request {
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                throw Refused(400, if (head.isEmpty()) EMPTY else TRUNCATED)
            }
            head.append(b.toChar())
            if (head.length >= 4 && head.endsWith("\r\n\r\n")) break
            if (head.length > MAX_HEAD_BYTES) throw Refused(431, HEAD_TOO_BIG)
        }
        val lines = head.toString().split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) throw Refused(400, BAD_REQUEST_LINE)
        val method = parts[0]
        if (method !in METHODS) throw Refused(501, "'$method' is not a method this server answers")
        val target = parts[1]
        if (!target.startsWith("/")) throw Refused(400, NOT_ABSOLUTE)
        val question = target.indexOf('?')
        val rawPath = if (question < 0) target else target.substring(0, question)
        val rawQuery = if (question < 0) "" else target.substring(question + 1)

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw Refused(400, BAD_HEADER)
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw Refused(411, NO_CHUNKED)
        val declared = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (declared < 0) throw Refused(400, BAD_LENGTH)
        if (declared > maxBodyBytes) {
            throw Refused(413, "$declared bytes is over this server's $maxBodyBytes cap")
        }

        val body = ByteArray(declared)
        var read = 0
        while (read < declared) {
            val n = input.read(body, read, declared - read)
            if (n < 0) throw EOFException("the body ended after $read of $declared bytes")
            read += n
        }
        return Request(method, decode(rawPath, plusIsSpace = false), query(rawQuery), headers, body)
    }

    /** `%XX` and nothing else. A byte that is not an escape is itself; a bad escape is a 400. */
    private fun decode(raw: String, plusIsSpace: Boolean): String {
        if (raw.indexOf('%') < 0 && !(plusIsSpace && raw.indexOf('+') >= 0)) return raw
        val bytes = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '%' -> {
                    if (i + 2 >= raw.length) throw Refused(400, BAD_ESCAPE)
                    val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                        ?: throw Refused(400, BAD_ESCAPE)
                    bytes.write(hex)
                    i += 3
                }
                c == '+' && plusIsSpace -> {
                    bytes.write(' '.code)
                    i++
                }
                else -> {
                    for (b in c.toString().toByteArray(StandardCharsets.UTF_8)) bytes.write(b.toInt())
                    i++
                }
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun query(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            out[decode(name, plusIsSpace = true)] = decode(value, plusIsSpace = true)
        }
        return out
    }

    companion object {
        /**
         * The address every socket in this app binds. A server reachable from the network is a
         * different product with a different threat model; this one is a phone talking to a browser
         * on the same phone.
         */
        const val LOOPBACK = "127.0.0.1"

        /**
         * Five seconds, for the head and the body of a request.
         *
         * Long enough that a browser on loopback, a `curl`, and a phone waking from sleep all get
         * through: a request is a few hundred bytes in one write. Short enough that a socket which
         * was opened and then abandoned is back in the pool before a person has noticed it hung.
         */
        const val READ_TIMEOUT_MS = 5_000

        /**
         * 64 KiB: about a very long question with a very long file name in it, and four orders of
         * magnitude above what this front-end ever sends. It is here so that a request cannot
         * decide how much of the phone's heap it gets.
         */
        const val MAX_BODY_BYTES = 64 * 1024

        /** 8 KiB of request line and headers. Generous for a `fetch`; unreachable by accident. */
        const val MAX_HEAD_BYTES = 8 * 1024

        /**
         * How many requests are answered at once. Four is a phone: one person, one browser, one
         * conversation being streamed. A thread streaming an answer is held for the whole answer,
         * which is why this is a small number and not one thread per client.
         */
        const val THREADS = 4

        /** Requests waiting for a thread. Past this the answer is `503`, not a longer queue. */
        const val QUEUE = 16

        /** How long [stop] waits for the acceptor and the pool before it returns anyway. */
        const val SHUTDOWN_WAIT_MS = 2_000L

        private val METHODS = setOf("GET", "POST")

        private const val TEXT = "text/plain; charset=utf-8"

        private const val EMPTY = "400: the connection closed before a request was sent"
        private const val TRUNCATED = "400: the request ended in the middle of its headers"
        private const val BAD_REQUEST_LINE = "400: that is not a request line"
        private const val NOT_ABSOLUTE = "400: the request target must be an absolute path"
        private const val BAD_HEADER = "400: a header line has no colon in it"
        private const val BAD_LENGTH = "400: Content-Length is not a number"
        private const val BAD_ESCAPE = "400: a percent escape is not two hex digits"
        private const val NO_CHUNKED = "411: this server reads Content-Length, not chunked bodies"
        private const val HEAD_TOO_BIG = "431: the request headers are over $MAX_HEAD_BYTES bytes"
        private const val BUSY = "503: every connection this server can hold is busy; try again shortly"
        private const val CRASH = "500: the route returned without writing a reply"
    }
}

/** The reason phrase for [status], so a status line is never a bare number. */
private fun reasonFor(status: Int): String = when (status) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    411 -> "Length Required"
    413 -> "Content Too Large"
    431 -> "Request Header Fields Too Large"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    503 -> "Service Unavailable"
    else -> "Status"
}
