package omp.agent.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HttpRefusal.message] — the lines a user reads when a request is refused.
 *
 * Two things are being pinned here. The first is that the advice survives: the cases that matter are
 * the ones with no body to quote, because that is the case a status code alone leaves a user with,
 * and it is reachable in practice — the JDK's `HttpURLConnection` consumes a 401's authentication
 * challenge and hands back nothing to read. The second is that a body never replaces that advice,
 * and never gets to act on the terminal it is quoted into: it comes from a host this process has no
 * reason to obey, and one that can put an escape sequence in a JSON error page.
 */
class HttpRefusalTest {

    private val url = "https://api.example.com/v1/chat/completions"

    @Test
    fun aBodyIsQuotedUnderneathTheAdviceRatherThanInsteadOfIt() {
        // The shape a real provider sends, including the fragment of the key it echoes back.
        val body = """{"error":{"message":"Incorrect API key provided: sk-proj-abcd****wxyz"}}"""
        val lines = HttpRefusal.message(url, 401, body).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("rejected the key"))
        assertTrue(lines[0], lines[0].contains("api.example.com"))
        assertEquals(body, lines[1])
    }

    @Test
    fun aBodyIsQuotedForEveryStatusThatHasOne() {
        val body = "upstream said no"
        for (code in listOf(400, 401, 403, 404, 429)) {
            val message = HttpRefusal.message(url, code, body)
            assertTrue("$code: $message", message.endsWith("\n$body"))
            assertTrue("$code: $message", message.substringBefore('\n').startsWith("HTTP $code: "))
        }
        // An unmapped status has no advice to give, so the body is the first line, as it always was.
        for (code in listOf(418, 500, 503)) {
            assertEquals("HTTP $code: $body", HttpRefusal.message(url, code, body))
        }
    }

    @Test
    fun aBodyCannotSmuggleTerminalControlSequencesIntoTheMessage() {
        // ESC[31m repaints the screen, ESC[0m repaints it again, BEL rings, and \r would rewrite the
        // line the user is reading. All of it comes from the server, and all of it is the terminal's
        // to obey.
        val body = "\u001B[31mred\u001B[0m\n\tsecond\u0007line\u001B[?25h\u007F"
        val message = HttpRefusal.message(url, 401, body)
        val quote = message.substringAfter('\n')
        assertTrue("nothing was quoted: $message", quote.isNotEmpty())
        for (ch in quote) {
            assertTrue("0x${ch.code.toString(16)} survived in: $quote", ch.code >= 0x20 && ch.code != 0x7F)
        }
        assertTrue(quote, quote.contains("red"))
        assertTrue(quote, quote.contains("second line"))
        // One newline in the whole message: the one that puts the quote on its own line.
        assertEquals(1, message.count { it == '\n' })
    }

    @Test
    fun aBodyOfNothingButEscapesIsQuotedAsNothingAtAll() {
        // Stripping the characters is the easy half; the residue has to go too, or a body that was
        // pure noise still costs the user a line of it.
        val message = HttpRefusal.message(url, 401, "\u001B[31m\u001B[0m\n\t \r ")
        assertTrue(message, message.contains("rejected the key"))
        assertFalse("a quote was invented: $message", message.contains('\n'))
    }

    @Test
    fun runsOfWhitespaceInABodyCollapseToOneSpace() {
        val body = "{\n\n  \"error\": \"no\",\n\t \"detail\": \"nope\"\n}"
        val message = HttpRefusal.message(url, 403, body)
        assertEquals("{ \"error\": \"no\", \"detail\": \"nope\" }", message.substringAfter('\n'))
    }

    @Test
    fun aLongBodyIsCutAtFiveHundredAndTwelveCharacters() {
        val body = "x".repeat(2_000)
        assertEquals(512, HttpRefusal.message(url, 500, body).removePrefix("HTTP 500: ").length)
        assertEquals(512, HttpRefusal.message(url, 401, body).substringAfter('\n').length)
    }

    @Test
    fun anEmptyBodyOnA401SaysTheKeyWasRejectedAndNamesTheHost() {
        val message = HttpRefusal.message(url, 401, "")
        assertTrue(message, message.contains("401"))
        assertTrue(message, message.contains("api.example.com"))
        assertTrue(message, message.contains("key"))
        // A bare "HTTP 401" is what this replaces.
        assertTrue("not actionable: $message", message.length > "HTTP 401".length)
    }

    @Test
    fun anEmptyBodyOnA403SaysTheKeyIsNotAllowed() {
        val message = HttpRefusal.message(url, 403, "")
        assertTrue(message, message.contains("403"))
        assertTrue(message, message.contains("api.example.com"))
        assertTrue(message, message.contains("not allowed"))
    }

    @Test
    fun theOtherStatusesNameWhatToDoAboutThem() {
        assertTrue(HttpRefusal.message(url, 404, "").contains("endpoint"))
        assertTrue(HttpRefusal.message(url, 429, "").contains("rate limiting"))
        assertTrue(HttpRefusal.message(url, 400, "").contains("rejected the request"))
    }

    @Test
    fun anUnmappedStatusStillNamesTheHost() {
        val message = HttpRefusal.message(url, 503, "")
        assertEquals("HTTP 503: api.example.com returned no body", message)
    }

    @Test
    fun aWhitespaceOnlyBodyCountsAsNoBody() {
        assertTrue(HttpRefusal.message(url, 401, "   \n ").contains("rejected the key"))
    }

    @Test
    fun anUnparseableUrlIsShownWholeRatherThanLost() {
        val message = HttpRefusal.message("not a url at all", 401, "")
        assertTrue(message, message.contains("not a url at all"))
    }

    @Test
    fun aRedirectNamesTheHostItPointedAt() {
        val message = HttpRefusal.redirect(url, 302, "https://elsewhere.example/v1/chat")
        assertTrue(message, message.contains("302"))
        assertTrue(message, message.contains("api.example.com"))
        assertTrue(message, message.contains("elsewhere.example"))
        assertTrue(message, message.contains("not followed"))
    }

    @Test
    fun aRedirectToTheSameHostStillSaysItWasNotFollowed() {
        val message = HttpRefusal.redirect(url, 307, "https://api.example.com/v2/chat")
        assertTrue(message, message.contains("307"))
        assertFalse("it was sent somewhere else: $message", message.contains("elsewhere"))
    }

    @Test
    fun aRelativeRedirectIsResolvedRatherThanLost() {
        assertTrue(HttpRefusal.redirect(url, 301, "/v2/chat").contains("api.example.com"))
    }

    @Test
    fun aRedirectWithNoLocationStillSaysWhatHappened() {
        val message = HttpRefusal.redirect(url, 303, null)
        assertTrue(message, message.contains("303"))
        assertTrue(message, message.contains("api.example.com"))
    }
}
