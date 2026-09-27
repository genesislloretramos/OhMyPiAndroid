package omp.shell

/**
 * One server-sent-events request, read on the caller's thread.
 *
 * This is a **pull** API, and that is the whole design. A phone has no room for a callback thread:
 * the agent's reader would have to hand events to whatever queue the UI drains, keep a second
 * thread alive for the life of a reply that can run for minutes, and decide what happens to an
 * event that arrives after Ctrl-C. The shell already blocks — a command runs on the REPL's
 * thread and the screen updates from it — so the reader is written as a loop that asks for the
 * next event and prints it, exactly like `grep` reading a file. One thread, one socket, one
 * owner, and a `Ctrl-C` is the same flag it already polls.
 *
 * [next] blocks until a whole event has arrived, because the caller cannot do anything useful with
 * half a token, and a partial event is a parse error nobody wants to see.
 *
 * The caller is expected to close the stream — a `use` block that stops on Ctrl-C and a `finally`
 * that stops on an exception both call [close], and neither can know which one got there first — and
 * the two of them together are what the phrase "expected" has to mean, because a caller is a
 * convention rather than something an interface can enforce. A stream nobody closes holds a socket
 * the phone's radio would rather have back, and so is a stream whose transport failed: [next]
 * closes the connection before it throws, because the exception unwinds past the code that would
 * otherwise have closed it.
 */
interface HttpStream : java.io.Closeable {

    /**
     * @return the next event's `data:` payload, or null at end of stream, or once this stream has
     *   been closed. Blocks until one arrives.
     * @throws java.io.IOException if the transport fails — a dropped connection, a read timeout, a
     *   reset. The stream is closed before this is thrown, so a caller that is mid-reply can treat
     *   the exception as the end of the stream and does not have to close to release the socket.
     */
    fun next(): String?

    /** The HTTP status, once the response has been received. */
    val status: Int
}
