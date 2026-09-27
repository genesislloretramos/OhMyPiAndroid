package omp.agent

import java.net.MalformedURLException
import java.net.URL

/**
 * The base URL a conversation is configured with, decided before a single byte of the key is put
 * on it.
 *
 * **The base URL is the only thing that decides which host sees the key**, so it is checked here
 * rather than left to the socket: a user who typed a `file:` or an `ftp:` URL is not asking for
 * an HTTP request, and a transport handed one would either throw a stack trace at them or, worse,
 * open something they did not mean to. The refusals are the ways a string can be wrong — not a
 * URL at all, a scheme the agent does not speak, no host inside it, and credentials in it — and
 * each says which one it is, because "invalid URL" is not an action a user can take.
 *
 * **A plaintext `http` to anywhere but loopback is a warning, not a refusal.** A phone running
 * `llama.cpp` on the same machine, or a home server on the LAN, is an ordinary and legitimate
 * setup, and refusing it would break the case this feature exists for. Sending an API key to a
 * remote host in the clear is a different thing, and a user is entitled to be told once before it
 * happens — see [Ready.warning].
 *
 * ### Two things this check has to get right, and both fail silently if it does not
 *
 * **Credentials in the URL are refused outright.** A base URL is written into
 * [omp.agent.store.AgentState]'s file inside the conversation folder, which is a folder any app
 * holding the all-files grant can read, and both the refusal here and `omp update` echo the URL
 * back. `https://sk-abc123@api.example.com/v1` would therefore put a key in shared storage and on
 * the screen, and `HttpURLConnection` would send it as Basic auth beside the `Authorization:
 * Bearer` header this loop sets. A key goes in with `omp key`.
 *
 * **"This device" means a literal address, not a name that starts like one.** `127.0.0.1.evil.test`
 * and `127.0.0.1.nip.io` are names anybody can register, and under a DNS server they run they
 * resolve wherever they like; treating either as loopback would suppress the plaintext warning
 * and send the key in the clear to whatever the name points at. So [isLoopback] parses the whole
 * host as four decimal octets, and anything with a letter or a fifth label in it is not this
 * device.
 */
sealed class Endpoint {

    /** A base URL the agent will make a request to. */
    data class Ready(
        /** The base URL with any trailing slash removed, so the chat path joins onto it. */
        val url: String,
        val host: String,
        /**
         * Whether the key can leave this device in the clear: true for `https`, and true for plain
         * `http` to a loopback address, because a model server on this machine is not a risk and a
         * warning there is noise that teaches a user to ignore the warning that matters. False is
         * the one case [warning] has something to say about.
         */
        val secure: Boolean,
    ) : Endpoint() {

        /**
         * The line to put in front of the first request, or null when there is nothing to say.
         *
         * Deliberately about the *key* and not about the URL: `http` to a remote host is not by
         * itself wrong, a key on it is, and this is the only place in the app that knows whether
         * the two are about to meet.
         */
        val warning: String? get() = if (secure) {
            null
        } else {
            "warning: $host is http, not https — the API key is about to cross the network in " +
                "plaintext, and $host is not this device"
        }
    }

    /**
     * A base URL the agent will not use, and the one sentence that says why.
     *
     * [baseUrl] is kept **without** any `user:password@` prefix, because the two callers print it
     * and one of them prints it to a terminal: a refusal that quoted the credentials out of the URL
     * it is refusing would put them on the screen, which is the one thing [KeyCommand] exists to
     * make impossible. The user who typed them still has them in the state file they came from, and
     * the sentence tells them where the key belongs instead.
     */
    data class Refused(val baseUrl: String, val reason: String) : Endpoint()

    companion object {

        /** Where every OpenAI-compatible endpoint keeps its chat call. */
        const val CHAT = "/chat/completions"

        /**
         * [baseUrl] as an [Endpoint], or the reason it is not one.
         *
         * [java.net.URL] is the parser, because it is the JDK's and not a dependency, and it is
         * strict in the one way that matters here: a string with no scheme is a
         * [MalformedURLException] rather than a URL with an empty protocol. Everything past that
         * — the scheme, the host, the loopback question — is a decision this class makes and
         * explains, because a parser that only says "no protocol" cannot answer "and what about
         * `ftp://`".
         */
        fun of(baseUrl: String): Endpoint {
            val text = baseUrl.trim()
            if (text.isEmpty()) {
                return Refused(baseUrl, "the base URL is empty; it is the address the model is at")
            }
            val url = try {
                URL(text)
            } catch (e: MalformedURLException) {
                return Refused(
                    baseUrl,
                    "'$text' is not a URL at all — a base URL is the scheme, the host and usually " +
                        "a path, like https://api.example.com/v1",
                )
            }
            val scheme = url.protocol.lowercase()
            if (scheme != "http" && scheme != "https") {
                return Refused(
                    withoutCredentials(text, url),
                    "'$scheme://' is not a scheme the agent can speak; it makes http and https " +
                        "requests and nothing else",
                )
            }
            val host = url.host
            if (host.isEmpty()) {
                return Refused(withoutCredentials(text, url), "'$text' has no host in it, so there is nowhere to send the key")
            }
            if (url.userInfo != null) {
                return Refused(
                    withoutCredentials(text, url),
                    "that base URL carries a password or a token in it; the agent sends the key in " +
                        "an Authorization header and nowhere else, so put it in with 'omp key' and " +
                        "leave the URL to say where the model is",
                )
            }
            return Ready(text.trimEnd('/'), host, scheme == "https" || isThisDevice(host))
        }

        /**
         * [text] as a URL with no `user:password@` in it, rebuilt from the parsed parts.
         *
         * **Rebuilt rather than cut.** Taking everything before the `@` leaves the credentials and
         * drops the host, which is worse than printing the URL at all: the message then carries the
         * secret *and* names no endpoint. The scheme, the host, the port and the path are all the
         * URL's own, so the result is the address a user typed with the secret taken out of it.
         */
        private fun withoutCredentials(text: String, url: URL): String = buildString {
            append(url.protocol).append("://").append(url.host)
            if (url.port != -1) append(':').append(url.port)
            append(url.file)
        }.ifEmpty { text.substringBefore('@') }

        /**
         * Whether [host] is this device, by name or by literal address.
         *
         * No [java.net.InetAddress] and no resolver lookup: a reverse lookup of a host that does
         * not resolve is a DNS query a user did not ask for, on the one code path that is about to
         * handle their credential. What that buys is that the answer has to come out of the string
         * itself, so a **whole** literal IPv4 in 127/8 is required and a name that merely starts
         * `127.` is not one: `127.0.0.1.nip.io` is a name somebody else can point anywhere.
         *
         * [ANY] is the other form of "this device" a server binds and a user then types, and
         * [THIS_HOST] is the trailing-dot spelling of `localhost`, which the same resolver answers
         * as itself and which an exact comparison would miss. A user running a local model gets the
         * warning on neither.
         */
        private fun isThisDevice(host: String): Boolean {
            val name = host.trim('[', ']').lowercase().trimEnd('.')
            return name == LOCALHOST || name == "::1" || name == ANY || inLoopbackBlock(name)
        }

        /**
         * Four decimal octets, 0..255, in 127/8 — and nothing else.
         *
         * A host that is not exactly this is a host whose name somebody chose, which is the case
         * the warning exists for.
         */
        private fun inLoopbackBlock(name: String): Boolean {
            if (!name.startsWith("127.")) return false
            val parts = name.split('.')
            if (parts.size != 4) return false
            for (part in parts) {
                if (part.isEmpty() || part.length > 3) return false
                for (c in part) if (c < '0' || c > '9') return false
                if (part.toInt() > 255) return false
            }
            return true
        }

        /** The name a loopback server is reached by, spelled once because it is checked twice. */
        private const val LOCALHOST = "localhost"

        /** The address that means "this machine" to a server that binds it and a client that uses it. */
        private const val ANY = "0.0.0.0"
    }
}
