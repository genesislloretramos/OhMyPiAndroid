package omp.agent.http

/**
 * The part of the server-sent-events grammar this app speaks, as a state machine with no I/O in it:
 * the `data` field, the blank line that ends an event, and the `[DONE]` that ends the stream.
 *
 * It lives in `:core` rather than beside the socket because the grammar is the part that decides
 * what counts as an event, and a rule that is only ever exercised through a live connection is a
 * rule nobody has tested. A transport reads lines and hands them here; [feed] answers the events
 * those lines completed, and [SseTest] drives the whole grammar — comments, blank lines, a payload
 * split over two `data:` lines, CRLF, `[DONE]` — with no server and no thread.
 *
 * **`event:`, `id:` and `retry:` are read and dropped**, and this is not a shortcut through the
 * specification: a model endpoint's stream is one logical reply arriving a token at a time, so an
 * event *name* has no reader on the far side of it, and a field this app cannot act on is a rule
 * that can only ever be wrong — a full implementation would carry state for reconnection and
 * resumption that nothing here asks for. A line carrying one of them is not an error and does not
 * end the event it appears in: the payload keeps being built from the `data` lines around it.
 *
 * One connection's state, and it is an instance rather than an `object` because two streams can be
 * open at once — the agent reading an answer while `curl` fetches something else — and shared
 * mutable grammar state would interleave one reply's events into the other's. The state is one
 * [StringBuilder] and two flags, and it is deliberately not thread-safe: one stream is read by one
 * thread, which is the whole argument for a pull API in the first place.
 *
 * **An event is capped at [MAX_EVENT_CHARS] characters.** Without a cap a server decides how much
 * of the phone's heap one event takes, and a `data:` line with no blank line after it is a loop
 * that appends forever: this is the machine's only unbounded input, and the input it has least
 * control over. Past the cap the stream is closed with a sentence saying so, which is the one event
 * a reader will see that is not a payload.
 *
 * **Several `data:` lines in one event are joined with a newline**, which is what the SSE
 * specification says and is the only choice that cannot corrupt a payload. A server splits a long
 * value across lines; it cannot be reporting a newline *inside* one, because a raw newline inside a
 * JSON string is not legal JSON and a model endpoint emitting one would be broken. So a newline
 * arriving here is a line break the server introduced, and the reader puts it back. A payload that
 * really does contain a newline arrives as the two characters `\` and `n` inside a JSON string, and
 * those two characters are passed through untouched — that is the case a naive "strip the newlines
 * and concatenate" reader gets wrong, by gluing two halves of a token together.
 *
 * A line ending in CR is trimmed — a CR that is the other half of a CRLF a transport stripped only
 * the LF of, and one left on the end would put a raw control character at the end of every payload,
 * which this project's own JSON parser refuses. Only at the very end: a CR in the middle of a line
 * is the server's text and is passed through as it arrived, because rewriting it would change the
 * bytes of the answer the model is in the middle of. [feed] never throws: a malformed line is a
 * line the grammar has no rule for, and inventing an event out of it would be worse than ignoring it.
 */
class Sse {

    private val data = StringBuilder()
    private var seen = false
    private var finished = false

    /**
     * True once `[DONE]` has arrived or [finish] has run. A transport needs this to stop reading:
     * a server is free to hold the socket open after the last event, and a loop that only stops on
     * end-of-stream would sit there waiting for a close that never comes.
     */
    val done: Boolean get() = finished

    /**
     * Hands one line — without its terminator — to the machine.
     *
     * @return the events that line completed: empty for almost every line, and one entry when a
     *   blank line closed an event. A list rather than a nullable event because "this line finished
     *   nothing" and "this line finished an empty event" are different answers, and collapsing them
     *   loses the second one. The one entry that is not a payload is the sentence handed over when
     *   an event outgrew [MAX_EVENT_CHARS], and it arrives with [done] already true.
     */
    fun feed(line: String): List<String> {
        if (finished) return emptyList()
        // Trimmed only at the very end of the line: this is the CR of a CRLF a transport that
        // splits on LF alone left attached, and it is the difference between a payload this
        // project's JSON parser can read and one it refuses.
        val text = when {
            line.endsWith("\r\n") -> line.dropLast(2)
            line.endsWith('\r') -> line.dropLast(1)
            else -> line
        }
        if (text.isEmpty()) return if (seen) take() else emptyList()
        return field(text)
    }

    /**
     * The body ended without a blank line, and without `[DONE]`.
     *
     * @return the event that was still open, or nothing. A dropped connection is not a reason to
     *   throw away the text the server did send, and a body that ends on a clean blank line has
     *   already been taken, so this is normally empty.
     */
    fun finish(): List<String> {
        if (finished) return emptyList()
        finished = true
        return if (seen) take() else emptyList()
    }

    /** A non-empty line, which is a comment, a field, or something the grammar does not know. */
    private fun field(text: String): List<String> {
        if (text[0] == ':') return emptyList()
        val colon = text.indexOf(':')
        val name = if (colon < 0) text.length else colon
        if (!isData(text, name)) return emptyList()
        val value = if (colon < 0) "" else text.substring(colon + 1).let {
            if (it.startsWith(" ")) it.substring(1) else it
        }
        if (data.length + value.length > MAX_EVENT_CHARS) return tooLarge()
        data.append(value).append('\n')
        seen = true
        return emptyList()
    }

    /**
     * The event outgrew the cap, so the stream ends here with a sentence saying why.
     *
     * Ending the stream is the point: the alternative is a reader parked on a socket that will keep
     * sending, and a phone whose heap is going somewhere a message cannot explain. The partial
     * event is dropped rather than delivered, because half a JSON document is the one thing a
     * caller cannot use, and the sentence is delivered *as* the last event so that a reader which
     * only ever reports payloads still has something to report.
     */
    private fun tooLarge(): List<String> {
        finished = true
        seen = false
        data.setLength(0)
        return listOf("an event of more than $MAX_EVENT_CHARS characters arrived, was not delivered, and the stream is closed")
    }

    /**
     * A line with no colon at all is a field name with an empty value, so a bare `data` is an empty
     * payload and a bare `event` is nothing. `isData` compares the four characters rather than
     * taking a substring, so an unknown field costs no allocation on a line that arrives per token.
     */
    private fun isData(s: String, end: Int): Boolean =
        end == 4 && s[0] == 'd' && s[1] == 'a' && s[2] == 't' && s[3] == 'a'

    private fun take(): List<String> {
        seen = false
        val text = data.toString()
        data.setLength(0)
        val payload = text.substring(0, text.length - 1)
        // Compared trimmed: an endpoint that pads its terminator with a space or a tab is still
        // saying the model has finished, and a payload of "[DONE] " is not a thing a model sends.
        // Getting this wrong costs the reader the whole point of `done` — five minutes parked on a
        // socket that is never going to send anything else.
        if (payload.trim() == DONE) {
            finished = true
            return emptyList()
        }
        return listOf(payload)
    }


    companion object {
        /** What every streaming endpoint sends instead of an empty body when the model is finished. */
        private const val DONE = "[DONE]"

        /**
         * The most one event may accumulate, in characters. Far above a reply — a long tool result
         * or a base64 image is a few hundred kilobytes — and far below what an unterminated `data:` line
         * would otherwise take from a heap nobody sized for it.
         */
        const val MAX_EVENT_CHARS = 1 shl 20
    }
}
