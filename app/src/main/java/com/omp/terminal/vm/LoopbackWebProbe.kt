package com.omp.terminal.vm

import omp.vm.provision.GuestWeb
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Asks the guest's Apache for this build's own chat document, and says whether it got it.
 *
 * **This is the probe, and [omp.vm.provision.GuestWeb]'s KDoc is where what it establishes is
 * written down.** Read that before changing anything here, because the claim this makes is the one
 * the whole chat origin rests on and the limit of it is not a detail.
 *
 * ### Why a socket and not the app's own HTTP client
 *
 * **[omp.shell.PlatformServices.httpGet] goes to a URL this app can name, and its answers are
 * translated through the platform's error model** — a refused connect, a timeout and a 404 are three
 * different failures to deal with, and a probe that has to interpret them is a probe whose bugs look
 * like answers. A raw request over a raw socket is one question — did a page come back, and was it
 * this build's — with a yes or a no, and every failure is the same no.
 *
 * ### The request, and the four words that make it safe
 *
 * ```
 * GET / HTTP/1.0
 * Host: 127.0.0.1:PORT
 * Connection: close
 * ```
 *
 * **`/`, and not the login path.** The token is this app's to issue and the guest's PHP knows nothing
 * about it; `/` is the one path both arrangements serve, and asking a path that needed the token
 * would make this a test of a credential rather than of whether Apache is up.
 *
 * **`Connection: close` and `HTTP/1.0`, so the response ends.** Apache closes the connection after a
 * 1.0 request with that header, so the read below terminates on its own and this class needs no
 * timer to give up on a body that never ends — a body cap alone would hang on a server that is
 * holding the socket open.
 *
 * **[GuestWeb.PAGE_MARKER] is the whole of the content check.** A port held by anything at all
 * answers a connect; only a server serving `app/src/main/assets/web/index.html` answers with the
 * marker, and `GuestWebProbeTest` fails when the marker and the file drift apart.
 *
 * **The read is capped at [MAX_BYTES] and the request is not authenticated**, so the worst case here
 * is a few kilobytes from a loopback server this app just started.
 */
object LoopbackWebProbe : GuestWeb.WebProbe {

    override fun answers(port: Int): Boolean {
        var socket: Socket? = null
        try {
            socket = Socket()
            // A connect bound and a read bound, and they are different numbers on purpose: Apache
            // under a path emulator can accept a connection before it has anything to say, and a
            // read timeout that equalled the connect timeout would report a working server as dead.
            socket.soTimeout = READ_MS
            socket.connect(InetSocketAddress(GuestWeb.LOOPBACK, port), CONNECT_MS)
            val request = "GET / HTTP/1.0\r\nHost: ${GuestWeb.LOOPBACK}:$port\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val text = read(socket.getInputStream())
            return text.contains(STATUS_200) && text.contains(GuestWeb.PAGE_MARKER)
        } catch (e: IOException) {
            // A refused connect, a timed-out read, a reset and a server that hung up mid-body are one
            // answer to "is Apache serving this build's page", and none of them is worth
            // distinguishing: what is wanted is a boolean, and every branch of this would be the same
            // one.
            return false
        } finally {
            try {
                socket?.close()
            } catch (e: IOException) {
                // A socket this class opened against its own guest, in a `finally` on the way out of
                // a boolean. A failure to close changes nothing that is reported.
            }
        }
    }

    /**
     * The response, up to [MAX_BYTES], as text.
     *
     * **The cap is the safety and not the policy.** The policy is [GuestWeb.PAGE_MARKER] being present
     * somewhere in what came back; the cap is what stops a guest that serves an unbounded body from
     * filling this app's heap while this class decides that. Four kilobytes is several times the
     * length of `index.html`'s head, which is where the marker is.
     */
    private fun read(stream: InputStream): String {
        val bytes = ByteArray(MAX_BYTES)
        var filled = 0
        while (filled < MAX_BYTES) {
            val n = stream.read(bytes, filled, MAX_BYTES - filled)
            if (n < 0) break
            filled += n
        }
        return String(bytes, 0, filled, Charsets.UTF_8)
    }

    /**
     * The status line's 200, in a form that cannot be matched by a header value.
     *
     * **`" 200 "` — the space either side is the point.** A bare `200` occurs inside a `Date:` header,
     * a `Content-Length`, a `Server:` banner and a chunked body, and a probe that matched one of
     * those would call a 404 page a served one.
     */
    private const val STATUS_200 = " 200 "

    /** The connect bound: 400 ms, the same number [omp.vm.doctor.Doctor.PROBE_MS] uses. */
    private const val CONNECT_MS = 400

    /** The read bound: 1500 ms, and longer than the connect because a server takes longer to answer. */
    private const val READ_MS = 1500

    /** How much of the response is read before the marker is judged: 4 KiB. */
    private const val MAX_BYTES = 4 * 1024
}
