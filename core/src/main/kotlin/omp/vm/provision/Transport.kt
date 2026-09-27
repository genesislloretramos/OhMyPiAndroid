package omp.vm.provision

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * How the layer gets bytes, and the one seam between it and a network.
 *
 * **Why a resumable download needs two calls and not one.** The caller has to know, *before* it
 * opens the file, whether the bytes about to arrive continue a partial download or start a new one:
 * appending a server's full body to the end of a half file produces a file that is the right size
 * and the wrong bytes, and the only thing that catches it is a digest — 55 MB later. So [open]
 * answers with a [Connection] whose [Connection.from] says where the body actually starts, and the
 * caller decides what to do with the file. A transport that answered "here are your bytes" in one
 * call would force that decision to be made after the first write, which is the same as not making
 * it.
 *
 * `from` is the offset the caller already holds, and the answer is what the *server* did with the
 * `Range` it was asked for: 206 and the same offset means continue, 200 and zero means the server
 * ignored the range and the whole body is coming, and a connection that answers anything else is
 * closed and reported rather than appended to.
 */
interface Transport {

    /**
     * Starts a request for [url], asking for the body from byte [from].
     *
     * @param from 0 for the whole body, or the byte offset already on disk.
     * @throws IOException when the request cannot be made at all. A request that *is* made and
     *   answers 404 comes back as a [Connection] with that status instead, because the difference
     *   between "no network" and "that file is not there" is a sentence a user has to be able to
     *   act on.
     */
    @Throws(IOException::class)
    fun open(url: String, from: Long): Connection

    /** One open response. The caller closes it, and closing it is what gives the radio the socket back. */
    interface Connection : Closeable {

        /** The HTTP status, as it arrived. Anything outside 2xx is a failed download. */
        val status: Int

        /**
         * The offset this body starts at: the `from` that was asked for when the server honoured
         * the range, and 0 when it did not. A value the caller did not ask for is a server that
         * answered something other than 200 or 206, and it is refused rather than believed.
         */
        val from: Long

        /** The size of the whole artifact, when the server said. Null when it did not. */
        val totalBytes: Long?

        /** The body, for reading once. */
        fun body(): InputStream
    }
}

/**
 * The shipped [Transport]: `HttpURLConnection`, and nothing else.
 *
 * **Why not [omp.shell.PlatformServices.httpStream].** That seam is a *server-sent-events* reader:
 * [omp.shell.HttpStream.next] returns the payload of a `data:` field as a `String`, which is
 * already fatal for a 234 MB binary and doubly so for a byte-range request, and it deliberately
 * hides the status behind a checkpointed read timeout meant for a model that thinks for minutes.
 * A download has the opposite shape: a status the caller must see before the first write, a body
 * that is bytes and not events, and a read timeout that has to be long or a mobile connection
 * kills the transfer. `HttpURLConnection` is the JDK's own and answers all three; the cost is that
 * this class is the one part of the layer that is not exercised on a JVM without a socket, which
 * is why [Transport] exists and why no test in this package opens one.
 *
 * Redirects are followed, which the agent's URL requires: a GitHub release asset is a 302 to a CDN
 * host, and a 302 that is reported as a failure would look exactly like a broken download.
 */
class HttpTransport(
    private val connectTimeoutMs: Int = 20_000,
    /**
     * Long, because a read timeout here is a statement about a *mobile radio* rather than about a
     * stalled socket: too short and a transfer is killed mid-file every time the connection is
     * held for a packet, which is a normal thing that happens on a phone. A partial file is not a
     * lost file — it is the whole point of resuming.
     */
    private val readTimeoutMs: Int = 60_000,
) : Transport {

    override fun open(url: String, from: Long): Transport.Connection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        val range = connection.getHeaderField("Content-Range")
        val started = if (status == HTTP_PARTIAL) range?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull() ?: from else 0L
        val total = range?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 }?.plus(started)
        return object : Transport.Connection {
            override val status: Int = status
            override val from: Long = started
            override val totalBytes: Long? = total

            override fun body(): InputStream = connection.inputStream

            override fun close() {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val HTTP_PARTIAL = 206
    }
}
