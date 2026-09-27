package omp.shell

import omp.agent.http.HttpRefusal
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The request every HTTP call in this app makes, and the two decisions that belong to it.
 *
 * It lives in `:core` for the same reason [Sse] does: what is left to get wrong in a request is not
 * the code, it is the parts that only exist over a socket. Whether a credential went somewhere it
 * should not, and what a caller is told when the status is not the one it asked for, are both
 * invisible in a class that only an Android build can run — and both are the kind of thing that is
 * wrong until somebody checks. [omp.shell.PlatformServices.httpStream] and `httpGet` are adapters
 * over this and nothing else.
 *
 * **Redirects are not followed.** `HttpURLConnection` re-sends the request headers when it follows
 * one, credential headers included, to whatever host the response names — so an endpoint answering
 * `302 Location: https://elsewhere.example/` would be handed the `Authorization` header of the
 * endpoint the user actually configured, and the request that carried it was never addressed there.
 * A model API has no legitimate reason to answer with a redirect, so the honest response is to make
 * no second request at all and to report the status with the host it named, which is the part of a
 * 302 a user can act on. The JDK offers no way to follow a redirect with the credential stripped, so
 * not following is the whole of the fix.
 *
 * **A repeated header name is refused, not merged.** `setRequestProperty` overwrites, so two `Cookie`
 * lines, or an `Accept` a caller set twice, arrive as one value and the caller never finds out. The
 * JDK has no multi-value setter that survives a redirect, so the seam says so instead, naming the
 * header: a caller that believed it sent two values finds out before the request does.
 */
object HttpRequests {

    /**
     * Sends the request and hands back the connection with the response status read.
     *
     * The status is not judged here, because two callers want different things from one: a stream
     * wants anything but 2xx to be an exception, and `curl` wants the 404's body. [status] is the
     * first of those, and a caller wanting the second reads `errorStream` itself.
     *
     * @param connectTimeoutMs bounds the connection, [readTimeoutMs] the gaps in the response.
     * @throws IllegalArgumentException if [headers] carries one name twice, whatever the case of
     *   the two spellings of it. Nothing is sent when it does.
     * @throws IOException on a transport failure, with the connection already released.
     */
    @Throws(IOException::class)
    fun open(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        // Checked before the connection exists: a refusal about a header is worth nothing once
        // there is a socket to give back.
        val names = HashMap<String, String>()
        for ((key, _) in headers) {
            val first = names.put(key.lowercase(), key)
            if (first != null) {
                throw IllegalArgumentException(
                    "the header '$first' is repeated — a request carries one value per name, so " +
                        "only one of them would be sent",
                )
            }
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = method
            for ((key, value) in headers) conn.setRequestProperty(key, value)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            // Read the status here so "sent" means the response has started: the headers a caller
            // may need are there, and a transport failure is an exception out of this call rather
            // than out of whichever line the caller happens to write next.
            conn.responseCode
            return conn
        } catch (e: Throwable) {
            conn.disconnect()
            throw e
        }
    }

    /**
     * The status of a response, refusing everything a caller did not come for.
     *
     * @return the status, which is 2xx — there is nothing else this returns normally.
     * @throws IOException carrying [HttpRefusal]'s wording, for a 3xx that was not followed and for
     *   anything outside 2xx. The caller still owns the connection and has to release it.
     */
    @Throws(IOException::class)
    fun status(conn: HttpURLConnection, url: String): Int {
        val code = conn.responseCode
        if (code in 300..399) {
            throw IOException(HttpRefusal.redirect(url, code, conn.getHeaderField("Location")))
        }
        if (code !in 200..299) throw IOException(HttpRefusal.message(url, code, errorBody(conn)))
        return code
    }

    /**
     * As much of an error body as is worth putting in front of a user: [ERROR_BODY_LIMIT] bytes,
     * decoded as UTF-8 because that is what every API that answers an error with JSON uses, and
     * marked when it was cut short so nobody reads a truncated page as a whole one.
     */
    fun errorBody(conn: HttpURLConnection): String {
        val stream = conn.errorStream ?: return ""
        val bytes = ByteArray(ERROR_BODY_LIMIT)
        var filled = 0
        stream.use {
            while (filled < bytes.size) {
                val n = it.read(bytes, filled, bytes.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        val text = String(bytes, 0, filled, Charsets.UTF_8)
        return if (filled == bytes.size) "$text…" else text
    }

    /** How much of an error body goes into a message; past this it is a page of HTML. */
    private const val ERROR_BODY_LIMIT = 512
}
