package omp.shell

import omp.agent.http.Sse
import java.io.IOException

/**
 * The read loop every [HttpStream] is made of: take a line, hand it to [Sse], return the event that
 * came back.
 *
 * It lives in `:core` rather than inside an Android transport because the one thing it decides that
 * a test cannot reach over a socket is what happens when the socket dies. A dropped connection or a
 * read timeout in the middle of a model reply arrives as an [IOException] out of [readLine], and
 * nothing else in the program is running at that moment: the caller is mid-`next()`, an exception is
 * unwinding out of it, and a `use` block or a `finally` is a good way to guess wrong about who gets
 * there first. The socket and its buffers are still open at that point, and leaving them to the
 * garbage collector means holding a radio until the collector happens to run. So the loop closes
 * the transport itself and then rethrows — [close] is idempotent, so whichever of the two callers
 * gets there second does nothing.
 *
 * There is no grammar here, and that is deliberate. [Sse] is the shipped grammar and is unit tested
 * on its own; what is left to get wrong is the transport's half of the job, and this is it in a form
 * a plain JVM test can drive: a line source that throws, and a close action that can be observed.
 */
class SseReader(
    private val readLine: () -> String?,
    private val release: () -> Unit,
) {

    private val sse = Sse()

    @Volatile
    private var closed = false

    /**
     * @return the next event's `data:` payload, or null at end of stream, or once this reader has
     *   been closed. Blocks until one arrives.
     * @throws IOException when the transport fails. [close] is called first, so the connection is
     *   already released by the time this reaches the caller.
     */
    fun next(): String? {
        if (closed) return null
        while (true) {
            // A server may hold the socket open after its last event, so the grammar's own idea of
            // being finished is what stops the loop — not waiting for an end-of-stream that may
            val line = try {
                readLine()
            } catch (e: IOException) {
                // The socket dies with the read, so this is the last chance to give it back, and a
                // release that itself fails must not displace the failure the caller came for.
                runCatching { close() }
                throw e
            } ?: return sse.finish().firstOrNull()
            val events = sse.feed(line)
            if (events.isNotEmpty()) return events[0]
            if (closed) return null
        }
    }

    /**
     * Release the connection rather than drain it, and be safe to call twice.
     *
     * The transport's own [release] is what unblocks a [next] already parked in a read on another
     * thread — a Ctrl-C arrives on a different thread from the one inside the agent — and closing
     * the reader is what actually gives the socket back. The flag alone would do neither: a thread
     * blocked in a read cannot see it until the read returns, which is the thing that is not
     * happening.
     */
    fun close() {
        if (closed) return
        closed = true
        release()
    }
}
