package omp.shell

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The single input path into the shell thread. Both the hardware keyboard (through the app's
 * `InputEncoder`) and the extra-keys bar feed it; the line editor drains it, and an interactive
 * command reads it as a plain [InputStream] when it owns the terminal.
 *
 * Unconsumed bytes live here, not in the reader, so a key sequence split across a paste is never
 * lost when control passes from the line editor to a command and back.
 */
class InputChannel {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val buf = ArrayDeque<Byte>()
    private val lock = Object()

    @Volatile
    var closed = false

    /**
     * Fired the moment a `Ctrl-C` byte arrives, so a foreground job is cancelled even though the
     * REPL thread is blocked joining it and cannot poll for the key itself.
     */
    @Volatile
    var onInterrupt: (() -> Unit)? = null

    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty() || closed) return
        for (b in bytes) {
            if (b == 0x03.toByte()) onInterrupt?.invoke()
        }
        try {
            queue.put(bytes)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            queue.put(ByteArray(0))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Blocking; -1 once the channel is closed and drained. */
    fun readByte(): Int {
        while (true) {
            synchronized(lock) {
                if (buf.isNotEmpty()) return buf.removeFirst().toInt() and 0xFF
            }
            if (closed) return -1
            val chunk = try {
                queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            synchronized(lock) {
                for (b in chunk) buf.addLast(b)
            }
        }
    }

    /** @return the next byte, null on timeout, -1 at end of input. */
    fun pollByte(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            synchronized(lock) {
                if (buf.isNotEmpty()) return buf.removeFirst().toInt() and 0xFF
            }
            if (closed) return -1
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val chunk = try {
                queue.poll(minOf(left, 20L), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } ?: continue
            synchronized(lock) {
                for (b in chunk) buf.addLast(b)
            }
        }
    }

    fun unreadByte(b: Int) = synchronized(lock) { buf.addFirst(b.toByte()) }

    fun asInputStream(): InputStream = object : InputStream() {
        override fun read(): Int = readByte()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = readByte()
            if (first < 0) return -1
            b[off] = first.toByte()
            var n = 1
            while (n < len) {
                synchronized(lock) {
                    if (buf.isEmpty()) break
                }
                val next = readByte()
                if (next < 0) break
                b[off + n] = next.toByte()
                n++
            }
            return n
        }

        /**
         * `queue` is not `buf`, so a poll is needed first: without it a command that polls
         * `available()` (top, less) would never see a key that had already arrived.
         */
        override fun available(): Int {
            synchronized(lock) {
                if (buf.isNotEmpty()) return buf.size
            }
            val chunk = try {
                queue.poll()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            if (chunk != null) {
                synchronized(lock) {
                    for (b in chunk) buf.addLast(b)
                }
            }
            return synchronized(lock) { buf.size }
        }
    }
}
