package omp.vm.guestapi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A server that answers the whole [GuestApi] contract over a real loopback socket, built from the
 * contract rather than from a copy of it.
 *
 * **Why this class exists.** [GuestApi] is a description, and a description is a thing a class can
 * write whether or not anything can serve it. A field with a typo in its type, a status code that
 * cannot be sent, an event name that is not one a reader dispatches on — none of those are visible
 * in a table. So this fake answers the contract, and it answers it by **reading the contract**: the
 * route is found with [GuestApi.shapeOf], the body is built from the [Body] it declares, and a
 * field with no representative in [VALUES] is a request that fails loudly instead of quietly
 * returning a stub.
 *
 * **The route table is the part worth reading.** Every route the contract declares is served here,
 * which means a contract that cannot be served is a test that does not pass — and it is served over
 * a socket, through `com.sun.net.httpserver`, so the shapes are proven on the wire rather than
 * described in a comment.
 *
 * **It is a fake and it says so.** The values in [VALUES] are not anybody's conversations, and this
 * class has never run `omp`, PHP or MySQL. What it proves is exactly one thing: that the contract is
 * expressible as HTTP and as a byte stream, and that a reader of this project's own grammar can
 * take the stream apart.
 */
class GuestApiFake(
    /**
     * The events the streamed route sends, in order, when it is asked for a message.
     *
     * An empty script answers the stream with `[DONE]` and nothing else, which is the shape a turn
     * that was refused before the endpoint was reached would have.
     */
    private val script: List<Frame> = emptyList(),
    /**
     * Refusals to answer instead, keyed by method and **path shape**.
     *
     * The value is the status: the body is that [Route]'s own [Refusal] for it, filled with the
     * placeholder values in [PLACEHOLDERS], so a refusal is proven servable in the exact bytes the
     * guest would send rather than in a sentence this class made up.
     */
    private val refuse: Map<Pair<Method, String>, Int> = emptyMap(),
) : Closeable {

    /** One event on the scripted answer. */
    data class Frame(val name: String, val data: Map<String, Any?>)

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** How long the fake waits between two events, so incrementality is measurable. */
    private val gapMillis = GAP_MILLIS

    /** When each event of the last stream was written, for the test that proves it streamed. */
    val writtenAt: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())

    private val handled = AtomicLong(0)

    /** The port the kernel gave this fake. */
    val port: Int get() = server.address.port

    /** The origin a browser would be on. */
    fun baseUrl(): String = "http://127.0.0.1:$port"

    /** The number of requests answered, so a test can show a request reached a route. */
    fun requests(): Long = handled.get()

    fun start(): GuestApiFake {
        server.createContext("/") { exchange -> answer(exchange) }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        return this
    }

    private fun answer(exchange: HttpExchange) {
        try {
            handled.incrementAndGet()
            val method = Method.entries.firstOrNull { it.wire == exchange.requestMethod }
            val shape = method?.let { GuestApi.shapeOf(it, exchange.requestURI.path) }
            val route = if (method == null || shape == null) null else GuestApi.route(method, shape)
            if (method == null || shape == null || route == null) {
                // A path the contract does not name is the one answer the page can be expected to
                // cope with, and it is the one answer the contract does not describe.
                send(exchange, 404, "application/json; charset=utf-8", NOT_THE_CONTRACT)
                return
            }
            val refused = refuse[method to shape]?.let { route.refusal(it) }
            if (refused != null) {
                send(exchange, refused.status, GuestApi.JSON, refused.body(PLACEHOLDERS))
                return
            }
            if (route.streamed) stream(exchange, route) else send(exchange, route.success, route.mediaType, body(route.response))
        } catch (e: Exception) {
            // A contract that cannot be served fails here, and the message names what broke rather
            // than closing the connection on a reader that is entitled to an answer.
            send(exchange, 500, "text/plain; charset=utf-8", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The streamed route: no `Content-Length`, one event at a time, and `[DONE]` at the end. */
    private fun stream(exchange: HttpExchange, route: Route) {
        val out = exchange.responseBody
        exchange.responseHeaders.add("Content-Type", route.mediaType)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        // Zero means "chunked, of unknown length", which is the only way to answer a stream: a
        // Content-Length would be a promise about a length this fake does not know yet.
        exchange.sendResponseHeaders(route.success, 0)
        try {
            for (frame in script) {
                // The name twice, exactly as the guest's own Sse does it: a browser dispatches on
                // the `event:` line, and this project's reader reads the `t` in the payload.
                val payload = LinkedHashMap<String, Any?>(mapOf("t" to frame.name))
                payload.putAll(frame.data)
                out.write("event: ${frame.name}\ndata: ${json(payload)}\n\n".toByteArray())
                out.flush()
                writtenAt.add(System.nanoTime())
                if (frame != script.last()) Thread.sleep(gapMillis)
            }
            // The end of the stream, in the one word a reader of this grammar looks for, and then
            // the body ends — which is what the page's `stream()` is waiting for.
            out.write("data: ${GuestApi.DONE}\n\n".toByteArray())
            out.flush()
        } finally {
            out.close()
        }
    }

    private fun send(exchange: HttpExchange, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        // 204 carries no body, and a Content-Length on one is a protocol error rather than a header.
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    /** A body for [body], filled from [VALUES] by field name. */
    fun body(body: Body, skip: Set<String> = emptySet()): String {
        val out = StringBuilder("{")
        var first = true
        for (field in body.fields) {
            if (field.name in skip) continue
            if (!first) out.append(',')
            first = false
            out.append(quote(field.name)).append(':')
            out.append(
                when (field.type) {
                    JType.STRING, JType.INTEGER, JType.BOOLEAN ->
                        valueFor(field).let { json(it) }
                    JType.ARRAY -> "[" + valueFor(field).let { json(it) } + "]"
                    JType.OBJECT -> "{" + body(field.element ?: Body(emptyList())) + "}"
                    JType.OBJECT_ARRAY -> "[" + body(field.element ?: Body(emptyList())) + "]"
                },
            )
        }
        return out.append('}').toString()
    }

    /**
     * The representative value for one field, and the failure when there is none.
     *
     * The failure is the point: a field added to the contract with nothing to say what it looks
     * like on the wire cannot be served, and saying so is what stops the contract and the fake from
     * drifting apart quietly.
     */
    private fun valueFor(field: Field): Any {
        val found = VALUES[field.name]
        requireNotNull(found) {
            "the contract has a field named '${field.name}' and this fake has no representative " +
                "value for it, so the shape cannot be proven servable; add one to GuestApiFake.VALUES"
        }
        return found
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            quote(it.key.toString()) + ":" + json(it.value)
        }
        else -> quote(value.toString())
    }

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    override fun close() {
        server.stop(0)
    }

    companion object {

        /** What a path the contract does not name is answered with. */
        const val NOT_THE_CONTRACT = "{\"error\":\"no such route\"}"

        /**
         * The pause between two events of a scripted answer.
         *
         * Long enough that a reader taking the events one at a time can see the order it read them
         * in, and short enough that a test which failed to stream does not sit here for a minute.
         */
        const val GAP_MILLIS = 30L

        /** The values a refusal's placeholders are filled with, so the bytes are deterministic. */
        val PLACEHOLDERS = mapOf(
            "name" to "photos",
            "id" to "turn-1",
            "path" to "/mnt/omp/photos",
            "errno" to "No such file or directory",
        )

        /**
         * One representative value per field name in the contract.
         *
         * A field that is not in here makes the fake fail on the request that reaches it, which is
         * the only way a table and a server can be forced to agree about a shape.
         */
        val VALUES: Map<String, Any> = mapOf(
            "version" to "omp/18.3.5",
            "hostRoot" to "/storage/emulated/0/Documents/omp",
            "conversations" to emptyList<Any>(),
            "name" to "photos",
            "title" to "photos",
            "modified" to 1_757_000_000_000L,
            "hostPath" to "/storage/emulated/0/Documents/omp/photos",
            "configured" to true,
            "keyStored" to true,
            "provider" to "openai",
            "model" to "gpt-5.2",
            "path" to "/mnt/omp/photos",
            "conversation" to "photos",
            "transcriptPath" to "/mnt/omp/photos/.omp/transcript.jsonl",
            "total" to 2,
            "shown" to 2,
            "skipped" to emptyList<Any>(),
            "entries" to emptyList<Any>(),
            "role" to "user",
            "content" to "what is in here?",
            "tool" to "read_file",
            "approved" to "y",
            "offset" to 0L,
            "bytes" to 0L,
            "reason" to "a line a crash left half written",
            "turn" to "turn-1",
            "text" to "a line of the answer",
            "id" to "turn-1-approval-1",
            "lines" to emptyList<Any>(),
            "allow" to true,
            "message" to "the model finished, and the answer is in the transcript",
        )
    }
}
