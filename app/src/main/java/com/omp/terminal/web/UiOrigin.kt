package com.omp.terminal.web

import omp.vm.provision.ProvisionState

/**
 * Where the chat UI is being served from, and the one interface that decides it.
 *
 * **Two implementations and one function, so that "which agent is answering" is one line rather
 * than a scattering.** The app is a frontend now: the chat is served by Apache inside the Debian
 * once that Debian is provisioned, and by [com.omp.terminal.web.LocalServer] on this build when it
 * is not. Both are real, both are wanted, and neither is a fallback in the sense of being
 * embarrassing — the Kotlin agent is the *only* agent a 32-bit device can ever have.
 *
 * ### What decides it, and what cannot
 *
 * [choose] reads [ProvisionState] and nothing else. That is a pair of facts read off the disk by
 * `omp.vm.provision.ProvisionPaths.state` — is there a whole rootfs, is there an agent binary in it
 * — and it is decided **once**, at start-up, and held. It is not re-decided when a page loads, when
 * a page navigates, when a query string arrives, or when anything the WebView is showing says
 * anything at all. A page cannot cause a different origin to be chosen, and that is the property
 * [UiOriginTest] exists to hold.
 *
 * ### The token gets in one way, and it is the way the page already works
 *
 * **The app appends the token to the URL it hands the WebView, and the page exchanges it for a
 * cookie.** That is the whole mechanism, and it is deliberately the *app injecting it into the
 * request* option rather than the other one: it needs **no guest-side code whatsoever**, so it
 * works today against [LocalServer], it works tomorrow against Apache serving the same three files,
 * and it keeps working whichever of the two arrangements the user eventually picks for the API —
 * one guest, two servers, PHP or Kotlin. The alternative, teaching the guest's PHP about this
 * app's token, would make the front end depend on a decision that has not been made and would have
 * to be redone if it is made the other way.
 *
 * `index.html` already does the other half: it reads `?t=` once, posts it to `/login`, and takes it
 * out of the address bar. Neither side of this has to know which server it is talking to.
 */
interface UiOrigin {

    /**
     * The URL the WebView is given, token included.
     *
     * The token is in it on purpose, and it is the same trade [TokenGate]'s KDoc makes: the
     * browser on this device is the thing the token exists to let in, and a URL nobody can open is
     * not a URL. A URL that is not `http` on loopback is refused by [permits] before a WebView ever
     * sees it.
     */
    fun url(): String

    /** What this origin is, in a phrase a user can be told: the status line, and a report. */
    fun label(): String

    /**
     * Whether [url] is this origin, and may therefore be fetched or navigated to.
     *
     * **Everything else is refused, and that is the WebView's whole security boundary here.** The
     * page comes out of a Debian the user can `apt install` into, so it is treated as untrusted: it
     * gets JavaScript, because a chat is JavaScript, and it gets **loopback, on this port, and
     * nothing else**. A redirect off-origin, an `https://` link, a `file://` URL, a `javascript:`
     * URL, a second loopback port and an off-device host are all refused, and refusing a
     * subresource as well as a navigation is the point — a page that may only *navigate* to its
     * own origin can still exfiltrate through an `<img>`.
     *
     * Compared on scheme, host and port, not on a prefix: `http://127.0.0.1:8731` must not permit
     * `http://127.0.0.1:87310`, and a string `startsWith` on the base would.
     */
    fun permits(url: String): Boolean

    companion object {

        /**
         * The origin for this device, decided by what is on it and by nothing else.
         *
         * @param state what `omp.vm.provision.ProvisionPaths` found on the disk.
         * @param guest the origin inside the Debian, or null when this build cannot name one.
         * @param loopback the origin this app serves itself, which always exists.
         */
        fun choose(state: ProvisionState, guest: UiOrigin?, loopback: UiOrigin): UiOrigin =
            // The guest is the product, so the guest is what is shown. A null guest cannot be
            // fallen back from silently either: it means the build has no guest origin to name,
            // and the loopback agent is a real answer rather than a consolation.
            if (state.realAgentInstalled && guest != null) guest else loopback
    }
}

/**
 * The ordinary http-on-loopback base, shared by both implementations.
 *
 * **A base URL is validated here rather than trusted**, because it is the one string in this file
 * that could come from somewhere other than this app's own code: a guest's port could be
 * configuration. A base that is not plain `http` on `127.0.0.1` is refused at construction rather
 * than at navigation time, so the app fails while it is being wired rather than after a page has
 * already been handed a URL.
 */
abstract class HttpLoopback(
    /** `http://127.0.0.1:PORT`, with no trailing slash and no path. */
    baseUrl: String,
    private val token: String,
) : UiOrigin {

    /** The scheme, host and port, kept apart so [permits] compares them and not a prefix. */
    val base: Base = Base.parse(baseUrl)

    override fun url(): String = "${base.text}/login?t=$token"

    override fun permits(url: String): Boolean {
        val other = try {
            Base.parse(url)
        } catch (e: IllegalArgumentException) {
            // Not an absolute http URL at all: no scheme, no host, a javascript: body, a
            // file: path. All of them are refused, and none of them is worth a distinct message.
            return false
        }
        return other.scheme == base.scheme && other.host == base.host && other.port == base.port
    }

    /** Scheme, host and port of one http origin, which is all two loopback URLs can differ in. */
    data class Base(val scheme: String, val host: String, val port: Int, val text: String) {
        companion object {
            fun parse(url: String): Base {
                val scheme = url.substringBefore("://", "")
                if (scheme != "http") {
                    throw IllegalArgumentException("UiOrigin: only http is served, not '$scheme'")
                }
                val rest = url.removePrefix("$scheme://").substringBefore('/').substringBefore('?')
                val colon = rest.lastIndexOf(':')
                val host = if (colon < 0) rest else rest.substring(0, colon)
                val port = if (colon < 0) -1 else rest.substring(colon + 1).toIntOrNull() ?: -1
                if (host != LOOPBACK) {
                    throw IllegalArgumentException("UiOrigin: only $LOOPBACK is served, not '$host'")
                }
                if (port <= 0) {
                    throw IllegalArgumentException("UiOrigin: no port in '$url'")
                }
                return Base(scheme, host, port, "$scheme://$host:$port")
            }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * The app's own loopback server, answering for the Kotlin agent that is in this build.
 *
 * This is not a placeholder. It is the only answer a 32-bit device can ever have, it is the answer
 * on a phone that has not been provisioned yet, and it is the one the browser URL on the
 * notification points at.
 */
class LoopbackOrigin(
    baseUrl: String,
    token: String,
) : HttpLoopback(baseUrl, token) {

    override fun label(): String = "this phone — the Kotlin agent in this build"
}

/**
 * Apache inside the Debian, serving the same three files and answering for the real `omp`.
 *
 * **It is a loopback origin like the other one, and deliberately so.** proot does not give the
 * guest its own network namespace, so a service listening on `127.0.0.1` inside the guest is
 * listening on a port this phone can reach, and it is reached exactly the way the app's own server
 * is. That means the WebView's boundary — loopback, one port, http — is the same boundary in both
 * cases, and the security reasoning above does not change when the guest arrives.
 */
class GuestOrigin(
    baseUrl: String,
    token: String,
) : HttpLoopback(baseUrl, token) {

    override fun label(): String = "the Debian — the real omp, under proot"
}
