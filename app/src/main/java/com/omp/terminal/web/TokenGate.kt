package com.omp.terminal.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The one credential check on this server, and an honest account of what it is.
 *
 * ### What it defends against
 *
 * **Another app on this phone.** A loopback port is not private: every process on the device can
 * connect to `127.0.0.1:PORT`, and a great many do it constantly — to analytics, to ad SDKs, to
 * local control planes — without asking anyone. Without a token, any of them could list a user's
 * conversations, read every question they have ever asked a model and every file the agent wrote
 * on their behalf, and spend their API key by asking the model things. That is the threat this
 * exists for, and it is not a hypothetical one: it is what a mis-scoped library looks like.
 *
 * **Any web page the user happens to have open.** A page cannot *read* a loopback response — the
 * same-origin policy stops it — but it can *send* a request, and a request that needed no
 * credential would let a page the user visited make this agent do things and learn how long the
 * answers were. A token the page does not have turns every one of those into a `401` it can
 * neither read nor tell apart from the server being switched off.
 *
 * ### What it does not defend against, stated plainly
 *
 * **A determined process on a rooted device.** Root is root: it can read the app's private
 * storage, and therefore this token, and no scheme that keeps a secret on a device defends against
 * someone who already holds the device. Claiming otherwise would be exactly the kind of reassuring
 * sentence this project does not write.
 *
 * **A user who pastes the URL into a browser on purpose.** The token is printed in the
 * notification and in `web` output precisely so the URL *can* be opened, and the token travels in
 * it. Anyone holding the phone, or a photograph of the notification, can use it. That is the deal:
 * convenience for the owner, paid for by the fact that the owner is who was given the key.
 *
 * **Traffic that is not this phone's.** The server binds to [LocalServer.LOOPBACK] and nothing
 * else, so there is no off-device path here to defend against. That is a property of the bind
 * address, not of this class.
 *
 * ### How the comparison is made
 *
 * [MessageDigest.isEqual] over the two byte arrays, which does not return early on the first
 * difference. A `==` on two strings across a loopback port is not an attack worth slowing down —
 * the round trip dominates — and the constant-time version costs nothing, so it is the one used.
 */
class TokenGate(
    /** The per-install token, from [AccessToken]. */
    private val token: String,
) {

    /**
     * The one path that answers without a token, and it serves one static document.
     *
     * It has to be open, or there is no way to obtain a token from the server at all. Both methods
     * on it are open — the `POST` is the login form's own submission, which cannot require the
     * thing it is asking for — and neither reads a conversation, a key or a filesystem.
     */
    val openPath: String = LOGIN_PATH

    /**
     * Whether [request] may be dispatched, and the reason when it may not.
     *
     * The token is taken from the `X-Omp-Token` header, the `t` query parameter, or the cookie
     * [ChatApi.login] set. All three are here because of what the browser can and cannot do: a
     * `fetch` can set a header, an `EventSource` cannot, and a `<link rel=stylesheet>` can do
     * neither. A front-end made to choose one of them would have had to put a credential into
     * every URL it loaded.
     */
    fun check(request: Request): String? {
        if (request.path == openPath) return null
        val offered = request.header(HEADER)
            ?: request.param(PARAM)
            ?: request.cookie(COOKIE)
        if (offered == null) {
            return "no $HEADER header, no $PARAM parameter and no $COOKIE cookie"
        }
        if (!MessageDigest.isEqual(offered.toByteArray(UTF8), token.toByteArray(UTF8))) {
            return "the token offered is not this install's"
        }
        return null
    }

    companion object {
        /** The header a `fetch` sends the token in. */
        const val HEADER = "X-Omp-Token"

        /** The query parameter, for a client that sets no headers at all. */
        const val PARAM = "t"

        /** The cookie the login sets, and the one a stylesheet and a stream ride on. */
        const val COOKIE = "omp_token"

        /** The path that answers without a token. */
        const val LOGIN_PATH = "/login"

        private val UTF8 = StandardCharsets.UTF_8
    }
}
