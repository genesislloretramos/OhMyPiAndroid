package omp.agent.http

/**
 * What a refused request puts in front of a user: one line saying what to do about it, and at
 * most one line below it quoting the server.
 *
 * It lives in `:core` with a test because it is a decision, not plumbing: it is the difference
 * between a user who fixes a wrong key and a user who files a bug. A status code alone is not
 * actionable, and the body is not always there to be quoted — the JDK's `HttpURLConnection` eats a
 * 401's authentication challenge and hands back no body at all, so a message that depended on one
 * would answer differently depending on which runtime was underneath. The answer therefore does
 * not depend on there being a body, and a body never *replaces* the answer either: a provider that
 * answers a 401 with a page of JSON says more about itself than the sentence does, and a user who
 * is shown only the page is back to guessing. So the sentence always comes first and the quote,
 * when there is one, goes underneath it.
 */
object HttpRefusal {

    /**
     * @param url the request's URL, named in the message so a user knows *which* endpoint refused
     *   them when they have two configured.
     * @param code the HTTP status.
     * @param body the first bytes of the error body, or an empty string when there was none.
     */
    fun message(url: String, code: Int, body: String): String {
        val host = hostOf(url)
        val quote = sanitise(body)
        val diagnosis = when (code) {
            401 -> "HTTP 401: $host rejected the key — check the API key, and that it is for this endpoint"
            403 -> "HTTP 403: $host refused the key — the key is valid but not allowed to use this model"
            404 -> "HTTP 404: $host has no such endpoint — check the model name and the API version"
            429 -> "HTTP 429: $host is rate limiting this key — wait, then try again"
            400 -> "HTTP 400: $host rejected the request — the model name or one of its arguments is wrong"
            // Nothing to advise on an unmapped status, so the body *is* the message — the same
            // first line it has always been, only now stripped of what a terminal would obey.
            else -> return if (quote.isEmpty()) "HTTP $code: $host returned no body" else "HTTP $code: $quote"
        }
        return if (quote.isEmpty()) diagnosis else "$diagnosis\n$quote"
    }

    /**
     * A 3xx this app will not follow, named by the host it pointed at.
     *
     * The request carries an `Authorization` header, and the JDK re-sends request headers when it
     * follows a redirect — to whatever host the response names. So a redirect is not a detour to be
     * transparent about here, it is a way for the host the user configured to hand the key to a
     * third one, and a model API has no legitimate reason to answer with one. The message names
     * where it pointed, because that is the part a user can act on: an endpoint they did not
     * configure, or an `http` URL that has become `https`, or a path that has moved.
     *
     * @param location the `Location` header, which may be relative to [url] and may be absent.
     */
    fun redirect(url: String, code: Int, location: String?): String {
        val host = hostOf(url)
        val where = location?.takeIf { it.isNotBlank() }?.let { hostOfRedirect(url, it) }
            ?: sanitise(location ?: "").ifEmpty { "nowhere in particular" }
        return "HTTP $code: $host redirected the request to $where — a request carrying credentials is not " +
            "followed, so check the endpoint URL"
    }

    /**
     * The host a `Location` names, resolved against the request URL because the header is allowed
     * to be relative. Null when either will not parse — the caller then quotes the header itself.
     */
    private fun hostOfRedirect(url: String, location: String): String? =
        runCatching { java.net.URI(url).resolve(location).host }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /**
     * The server's words, made safe to put in a terminal this process does not give the server.
     *
     * This is a boundary, not formatting. Everything here comes from a host this process has no
     * business obeying: `ESC[31m` repaints the screen, `ESC[2J` erases the scrollback, `\r` rewrites
     * the line a user just read, and a CSI sequence can move the cursor anywhere and print whatever
     * it likes. A hostile or merely careless endpoint would then be styling a terminal it does not
     * own. Pretty-printed JSON makes it worse rather than better, because a real body is full of
     * newlines and tabs and an endpoint that wants an escape in there has plenty of room to put
     * one — so the characters go, every run of whitespace becomes one space, and what is left is
     * capped at [BODY_LIMIT] characters. A body made only of escapes and whitespace therefore
     * sanitises to nothing at all and adds no second line, rather than leaving a line of residue.
     */
    private fun sanitise(body: String): String {
        val out = StringBuilder(body.length.coerceAtMost(BODY_LIMIT))
        // True once a separator is owed: one at the start of the body is dropped, and one between
        // two runs of anything whitespace is not emitted twice.
        var spaced = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == ESC || ch == CSI8 -> {
                    // The whole sequence goes, not only its first character: a CSI is an escape
                    // because of the bytes after the ESC, and leaving "31m" behind is exactly the
                    // residue a user reads as noise. An escape prints nothing, so it is not a
                    // separator either — the text either side of one is a single word.
                    i = endOfEscape(body, i)
                }
                ch.isWhitespace() || ch.code < 0x20 || ch.code in 0x7F..0x9F -> {
                    spaced = out.isNotEmpty()
                    i++
                }
                else -> {
                    if (spaced) out.append(' ')
                    spaced = false
                    out.append(ch)
                    if (out.length == BODY_LIMIT) return out.toString()
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * The index just past the escape sequence starting at [start], which is its ESC or its 8-bit
     * CSI. A CSI runs to its final byte, the first character in `@`..`~`; a string sequence — OSC,
     * which can carry a whole hyperlink or a window title — runs to its terminator, BEL or ST; and
     * anything else is the two-character sequence it is. One with no terminator swallows the rest
     * of the body, which is what a terminal does with it too rather than printing half of it.
     */
    private fun endOfEscape(body: String, start: Int): Int {
        val esc = body[start] == ESC
        val next = if (esc && start + 1 < body.length) body[start + 1] else ' '
        var i = start + if (esc) 2 else 1
        if (next == '[') {
            while (i < body.length && body[i] !in '@'..'~') i++
            return minOf(i + 1, body.length)
        }
        if (next == ']') {
            while (i < body.length) {
                if (body[i] == BEL || body[i] == ST8) return i + 1
                if (body[i] == ESC && i + 1 < body.length && body[i + 1] == '\\') return i + 2
                i++
            }
            return body.length
        }
        return i
    }

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'
    private const val CSI8 = '\u009B'
    private const val ST8 = '\u009C'

    /** How much of a body is quoted: past this it is a page of HTML, not a reason. */
    private const val BODY_LIMIT = 512

    /** The host, or the whole URL when it will not parse — a bad URL is its own diagnostic. */
    private fun hostOf(url: String): String = runCatching { java.net.URL(url).host }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: url

}
