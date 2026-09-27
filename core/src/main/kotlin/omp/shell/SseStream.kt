package omp.shell

import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/**
 * One `text/event-stream` response, and the two decisions that are worth getting right about a
 * socket that has gone quiet.
 *
 * It lives in `:core` because those decisions cannot be run anywhere else. [omp.shell.PlatformServices.httpStream]
 * in `:app` is now four lines long — open the connection, read the status, hand both here — and
 * everything a test can be asked about a silent endpoint is in this class rather than in a copy of
 * it that only an Android build could reach. A line assembled over the raw reader, a poll, and a
 * clock that only exists between two checkpoints are all here, and the JVM drives them against a
 * real `HttpServer`.
 *
 * **A read timeout on this connection is a checkpoint, not a limit.** [open] sets
 * [STREAM_POLL_MS] — a quarter of a second — and the loop below treats the `SocketTimeoutException`
 * it produces as a place to look at a clock and a flag rather than as a failure. The two halves of
 * that are measured, not assumed, and they are the reason for everything else here:
 *
 * - **`conn.disconnect()` does not end a read that is already parked in the socket.** It returns
 *   at once, and the read stays where it is inside the JDK's socket implementation. What it *does*
 *   do is drop the socket: the far end sees end-of-file within a few hundred milliseconds. So the
 *   release order below is `disconnect` then close — the order makes the connection the server's
 *   problem the instant a user walks away, and closing the reader's stream first would block on the
 *   decoder's lock that the parked read is holding, so the disconnect would still be sitting on
 *   the line afterwards.
 * - **What does end the parked read is the read timing out.** Nothing this class can call from
 *   another thread reaches a thread inside a socket read, so the poll interval *is* the
 *   cancellation latency: a `close()` on the Ctrl-C thread is noticed at the next checkpoint, and
 *   at worst [STREAM_POLL_MS] later. A quarter of a second is below the threshold at which a user
 *   notices a stop, and costs four wake-ups a second on one connection, which is nothing on a
 *   phone. This is the only reason [STREAM_POLL_MS] is short, and the reason the limit below is
 *   not: shortening the limit to make a cancel feel faster would trade a real feature for a
 *   cosmetic one.
 *
 * **The real limit is a wall clock, and it is long.** [STREAM_SILENCE_LIMIT_MS] is measured from
 * the moment the stream is opened, not from the last byte, so it bounds the reply as a whole: a
 * stream that keeps producing for longer than this is ended as surely as one that says nothing at
 * all. A reasoning model can be silent for the better part of a minute between tokens and a long
 * answer can run for minutes, and failing either would be trading a feature for a convenience. It
 * is checked at each checkpoint and thrown as an [IOException] naming the host and the wait, so
 * what a user is shown is the endpoint that went quiet and roughly how long it said nothing — not
 * a parked thread and not a bare `Read timed out`.
 *
 * **Lines are assembled here, over the raw reader. The class never asks anybody else for a
 * line**, and the corruption that prevents was measured on this JDK rather than assumed — because
 * the first account of it was wrong in an instructive way.
 *
 * A buffering reader keeps a *partial line* in its own buffer and re-fills when it wants a
 * newline. `BufferedReader.fill()` zeroes its count before it reads, so a
 * `SocketTimeoutException` thrown out of that read takes the partial line with it. Measured here,
 * against a real socket with a 64-character `BufferedReader` and the poll interval as the read
 * timeout: a delta written in two halves with a silence between them arrives as `rtial"}` instead
 * of `partial"}` — a token no grammar can recover from, which nothing downstream can report and
 * which no assertion on the stream's own return value can explain. Reassembling the loop around
 * `readLine()` turns twelve of the twenty-four cases in [omp.shell.HttpStreamTest] red, which is
 * the size of the mistake.
 *
 * What the same measurement also showed is that the corruption is **not** a property of a
 * `BufferedReader` being there. That reader, read with `read(char[], off, len)` instead of
 * `readLine()`, delivered `partial"}` whole at every size tried — because a caller assembling its
 * own lines is handed every character the reader decoded and a timeout costs it nothing. So the
 * rule this class follows is the accurate one: **the loop owns the line.** A timeout means "the
 * buffer has no newline in it yet" and nothing else, and no buffer between here and the socket can
 * be holding a line of its own.
 *
 * [readChars] is a constructor parameter rather than a literal so a test can drive a read smaller
 * than any buffering reader's own buffer — the configuration in which a `readLine()` anywhere in
 * this path would be caught — and [omp.shell.HttpStreamTest] does exactly that.
 *
 * **A server may hold the socket open after its last event**, so what ends the read is the
 * grammar's own idea of being finished, in [SseReader] — not an end-of-stream that may never come.
 *
 * The caller is expected to close the stream, and cannot be made to; see [HttpStream] for why a
 * `use` block and a `finally` together are the only honest reading of "expected". [next] closes
 * the connection before it throws, because the exception unwinds past the code that would
 * otherwise have closed it.
 */
class SseStream internal constructor(
    private val conn: HttpURLConnection,
    override val status: Int,
    /**
     * How long the reply may last before a checkpoint ends it, and the clock that says how long it
     * has been. A test reaches the limit by passing a short one; the clock is a parameter so the
     * limit can be driven without waiting for it, and the app passes [System::currentTimeMillis].
     */
    private val silenceLimitMs: Long,
    private val now: () -> Long,
    /**
     * The order the two halves of the release run in, as `close the stream` and `disconnect`. The
     * shipped order is `disconnect` then close, for the reason in this class's KDoc; a test that has
     * to show what the *other* order does passes its own, so the contrast is exercised rather than
     * described.
     */
    private val release: (close: () -> Unit, disconnect: () -> Unit) -> Unit = { close, disconnect ->
        disconnect()
        close()
    },
    /** What the release does before it runs, so a test can watch the connection go back. */
    private val onRelease: () -> Unit = {},
    /**
     * How many characters one read asks for. The shipped value is a whole SSE frame's worth and
     * nothing depends on it, which is the claim a test checks by driving a value *below* any
     * buffering reader's own buffer: a [java.io.BufferedReader] only loses a timed-out read when
     * the read is small enough to go through its `fill()`, and a 32-character read goes through it
     * every time.
     */
    private val readChars: Int = READ_CHARS,
) : HttpStream {

    private val chars = CharArray(readChars)

    private val unread = StringBuilder()

    /** Set by [close] on the Ctrl-C thread, read by the read loop at its next checkpoint. */
    @Volatile
    private var cancelled = false

    /** When this reply stops being worth waiting for at all: [silenceLimitMs] from now. */
    private val giveUpAt = now() + silenceLimitMs

    private val source: java.io.Reader = InputStreamReader(conn.inputStream, Charsets.UTF_8)

    private val events = SseReader(
        readLine = { readLineThroughSilence() },
        release = {
            onRelease()
            release({ runCatching { source.close() } }, { conn.disconnect() })
        },
    )

    override fun next(): String? = events.next()

    override fun close() {
        // Before the release, because the release is what the loop is parked behind and the flag is
        // what it comes back up to read. A quarter of a second later at worst.
        cancelled = true
        events.close()
    }

    /**
     * The next line, or null at end of stream — and while the endpoint is quiet, the answer is
     * neither: it is a checkpoint, and only two questions are worth asking of one.
     *
     * The line comes out of [unread] when there is a newline in it, which may be immediately and
     * may be after any number of checkpoints. A checkpoint asks whether this reader has been closed
     * and whether the reply has run out its wall clock, and neither answer is "is the model slow":
     * a model being slow is [silenceLimitMs] away from mattering.
     */
    private fun readLineThroughSilence(): String? {
        while (true) {
            val eol = unread.indexOf("\n")
            if (eol >= 0) {
                val line = unread.substring(0, eol)
                unread.delete(0, eol + 1)
                // An SSE line ends LF or CRLF, and the CR belongs to the terminator, not the line.
                return if (line.endsWith("\r")) line.dropLast(1) else line
            }
            val read = try {
                source.read(chars)
            } catch (e: SocketTimeoutException) {
                // The checkpoint, and the only moment this thread can act on a world it cannot
                // otherwise see: nothing arrived for the poll interval, which says nothing about
                // whether the reply is still coming — it says only that a flag set on another
                // thread can finally be read, and that the wall clock can finally be read.
                if (cancelled) return null
                if (now() >= giveUpAt) throw IOException(tooQuiet())
                continue
            }
            if (read < 0) {
                // End of stream: whatever is left has no terminator, and it is still a line.
                return if (unread.isEmpty()) null else unread.toString().also { unread.clear() }
            }
            unread.append(chars, 0, read)
        }
    }

    /**
     * The refusal a wall clock past its limit produces: which host, how long, and what to do about
     * it. Every part of that is something a user can act on, which is the whole difference between
     * this and a `SocketTimeoutException` carrying the word "timed".
     */
    private fun tooQuiet(): String {
        val wait = if (silenceLimitMs % 60_000L == 0L) {
            val minutes = silenceLimitMs / 60_000L
            "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        } else {
            val seconds = silenceLimitMs / 1000L
            "$seconds ${if (seconds == 1L) "second" else "seconds"}"
        }
        return "nothing from ${conn.url.host} for $wait — the model may have stopped sending, or " +
            "the connection dropped. Try again, or check that host is answering."
    }

    companion object {
        /**
         * How often a parked read comes up for air, and therefore how long a `close()` can take to
         * be noticed. Not a limit on anything; see this class's KDoc for the measurement that
         * makes it the cancellation latency and [STREAM_SILENCE_LIMIT_MS] the limit.
         */
        const val STREAM_POLL_MS = 250

        /**
         * How long one reply may last, measured as a wall clock from the moment the stream is
         * opened. Generous on purpose, and not to be shortened to make a cancel feel quicker.
         */
        const val STREAM_SILENCE_LIMIT_MS = 300_000L

        /**
         * How many characters one read asks for: a whole SSE frame, so a line in one packet is
         * usually one read and the loop is not run for every token. Nothing depends on the value.
         */
        const val READ_CHARS = 8 * 1024

        /**
         * Sends the request, judges the status, and hands back a stream that is already reading
         * through checkpoints.
         *
         * @param connectTimeoutMs bounds the connection. The read timeout is [pollMs], which is a
         *   checkpoint and not a limit — see this class's KDoc — and the reply is bounded by
         *   [silenceLimitMs] instead.
         * @throws IllegalArgumentException if [headers] carries one name twice; nothing is sent.
         * @throws IOException for a non-2xx, carrying [omp.agent.http.HttpRefusal]'s wording. The
         *   connection is released before this is thrown.
         */
        @Throws(IOException::class)
        fun open(
            url: String,
            method: String,
            headers: List<Pair<String, String>>,
            body: ByteArray?,
            connectTimeoutMs: Int,
            pollMs: Int = STREAM_POLL_MS,
            silenceLimitMs: Long = STREAM_SILENCE_LIMIT_MS,
            now: () -> Long = System::currentTimeMillis,
        ): SseStream {
            val conn = HttpRequests.open(url, method, headers, body, connectTimeoutMs, pollMs)
            return try {
                SseStream(conn, HttpRequests.status(conn, url), silenceLimitMs, now)
            } catch (e: Throwable) {
                conn.disconnect()
                throw e
            }
        }
    }
}
