package omp.agent.http

import omp.agent.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The [Sse] grammar, driven line by line with no socket in sight.
 *
 * This is the copy the app ships: [omp.shell.HttpStream] is a transport and this is the rule that
 * decides what an event is, so the rule gets tested here directly rather than only through a live
 * connection. Every case below is something a real endpoint does.
 */
class SseTest {

    /** A fresh machine per test: this is per-connection state, and a shared one would leak. */
    private lateinit var sse: Sse

    @Before
    fun setUp() {
        sse = Sse()
    }

    private fun feed(vararg lines: String): List<String> {
        val out = ArrayList<String>()
        for (line in lines) out += sse.feed(line)
        return out
    }

    private fun feedBody(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split("\n")) out += sse.feed(line)
        return out
    }

    // ---- the ordinary shape ------------------------------------------------------------

    @Test
    fun anEventIsADataLineAndABlankLine() {
        assertEquals(emptyList<String>(), feed("data: one"))
        assertEquals(listOf("one"), feed(""))
    }

    @Test
    fun everyEventOfABodyComesOutInOrder() {
        val body = "data: one\n\ndata: two\n\ndata: three\n\n"
        assertEquals(listOf("one", "two", "three"), feedBody(body))
    }

    @Test
    fun aDataLineWithNoSpaceStillCarriesItsValue() {
        assertEquals(listOf("tight"), feedBody("data:tight\n\n"))
    }

    @Test
    fun onlyOneLeadingSpaceIsStripped() {
        // SSE strips one space, not all of them: two spaces means a payload starting with a space.
        assertEquals(listOf(" padded"), feedBody("data:  padded\n\n"))
    }

    @Test
    fun anEmptyDataLineIsAnEmptyEvent() {
        assertEquals(listOf(""), feedBody("data:\n\n"))
    }

    // ---- lines that are not events -----------------------------------------------------

    @Test
    fun aCommentIsIgnored() {
        assertEquals(emptyList<String>(), feed(": keep-alive", ":", ": a longer note"))
        assertEquals(listOf("payload"), feedBody(": ping\ndata: payload\n\n"))
    }

    @Test
    fun fieldsOtherThanDataAreIgnored() {
        assertEquals(
            emptyList<String>(),
            feed("event: message", "id: 42", "retry: 3000"),
        )
        assertEquals(listOf("payload"), feedBody("event: message\nid: 1\ndata: payload\n\n"))
    }

    @Test
    fun aFieldWithNoColonIsAFieldNameWithNoValue() {
        assertEquals(emptyList<String>(), feed("event", "id"))
        assertEquals(listOf(""), feedBody("data\n\n"))
    }

    @Test
    fun aBlankLineWithNoEventIsNothing() {
        assertEquals(emptyList<String>(), feed("", "", ""))
    }

    @Test
    fun anUnknownFieldWhoseNameMerelyStartsWithDataIsIgnored() {
        // "database:" is not "data:", and reading it as data would corrupt every payload.
        assertEquals(emptyList<String>(), feed("database: x", "datas: y"))
        assertEquals(listOf("real"), feedBody("database: x\ndata: real\n\n"))
    }

    // ---- several data lines in one event -----------------------------------------------

    @Test
    fun twoDataLinesAreOneEventJoinedByANewline() {
        assertEquals(listOf("first\nsecond"), feedBody("data: first\ndata: second\n\n"))
    }

    @Test
    fun aPayloadSplitOverThreeLinesIsOneEvent() {
        assertEquals(listOf("a\nb\nc"), feedBody("data: a\ndata: b\ndata: c\n\n"))
    }

    @Test
    fun anEmptyDataLineInsideAnEventIsAnEmptyLine() {
        assertEquals(listOf("a\n\nb"), feedBody("data: a\ndata:\ndata: b\n\n"))
    }

    @Test
    fun aLiteralBackslashNInAPayloadIsTwoCharactersAndSurvives() {
        // The payload is the JSON text {"t":"a\nb"} where \n is a two-character escape inside a
        // JSON string. A reader that stripped or rewrote newlines would hand back {"t":"ab"}.
        val payload = """{"t":"a\nb"}"""
        assertEquals(listOf(payload), feedBody("data: $payload\n\n"))
        assertTrue(feedBody("data: $payload\n\n").single().contains("""a\nb"""))
    }

    @Test
    fun aPayloadSplitAcrossDataLinesRejoinsWithARealNewline() {
        // The two lines are one event, and the join puts back the line break the server introduced
        // — a real newline, not the two characters a JSON escape would use.
        val event = feedBody("data: first\ndata: second\n\n").single()
        assertEquals("first\nsecond", event)
        assertEquals(1, event.count { it == '\n' })
    }

    // ---- the end of the stream ---------------------------------------------------------

    @Test
    fun doneEndsTheStreamAndIsNotAnEvent() {
        assertEquals(listOf("one"), feedBody("data: one\n\ndata: [DONE]\n\n"))
        assertTrue(sse.done)
        assertEquals(emptyList<String>(), feed("data: two", ""))
        assertEquals(emptyList<String>(), sse.finish())
    }

    @Test
    fun anUnclosedDoneIsOnlyFinishedAtTheEndOfTheBody() {
        // A data line alone is not an event; the grammar takes it when the blank line or the end of
        // the body arrives, and only then does it decide the event was [DONE] and not a payload.
        assertEquals(emptyList<String>(), feed("data: [DONE]"))
        assertFalse(sse.done)
        assertEquals(emptyList<String>(), sse.finish())
        assertTrue(sse.done)
    }

    @Test
    fun aBodyThatJustEndsHandsOverTheOpenEvent() {
        assertEquals(emptyList<String>(), feed("data: one", "data: two"))
        assertFalse(sse.done)
        assertEquals(listOf("one\ntwo"), sse.finish())
        assertTrue(sse.done)
    }

    @Test
    fun aBodyThatEndsCleanlyHasNothingLeftToHandOver() {
        assertEquals(listOf("one"), feedBody("data: one\n\n"))
        assertEquals(emptyList<String>(), sse.finish())
    }

    @Test
    fun anEmptyBodyIsNothingAtAll() {
        assertEquals(emptyList<String>(), feedBody(""))
        assertEquals(emptyList<String>(), sse.finish())
        assertTrue(sse.done)
    }

    @Test
    fun doneIsSticky() {
        feedBody("data: [DONE]\n\n")
        assertEquals(emptyList<String>(), sse.finish())
        assertEquals(emptyList<String>(), feed("data: more"))
    }

    // ---- line endings ------------------------------------------------------------------

    @Test
    fun crlfLeavesNoStrayCarriageReturn() {
        assertEquals(listOf("one", "two"), feedBody("data: one\r\n\r\ndata: two\r\n\r\n"))
    }

    @Test
    fun crIsOnlyTrimmedFromTheEndOfALine() {
        // A CR inside a payload is data, not a line ending; only the trailing one is dropped.
        assertEquals(listOf("a\rb"), feedBody("data: a\rb\r\n\r\n"))
    }

    @Test
    fun aBodyWithMixedEndingsStillReads() {
        assertEquals(listOf("one", "two"), feedBody("data: one\n\ndata: two\r\n\r\n"))
    }

    @Test
    fun doneIsRecognisedWithWhitespaceAroundIt() {
        // A server that pads its terminator is still finished. A reader that misses it parks on a
        // socket for the length of the read timeout waiting for an event that is never coming, so
        // this is the same hang `done` exists to prevent, arrived at one space later.
        assertEquals(emptyList<String>(), feedBody("data: [DONE] \n\n"))
        assertTrue(sse.done)
        assertEquals(emptyList<String>(), feed("data: two"))
    }

    @Test
    fun aLineThatArrivesWithItsCrlfStillLeavesAPayloadTheJsonParserCanRead() {
        // A transport that splits on LF alone hands the CR along, and a CR left on the end of a
        // payload is a raw control character inside a string — which this project's own parser
        // refuses, so a model's answer is rejected over a byte the server never meant to send.
        val payload = feed("data: {\"delta\":\"hi\"}\r\n", "").single()
        assertEquals("""{"delta":"hi"}""", payload)
        assertEquals(Json.Obj(mapOf("delta" to Json.Str("hi"))), Json.parse(payload))
    }

    @Test
    fun aFieldThisAppHasNoUseForDoesNotBreakTheEventItIsIn() {
        // `event:`, `id:` and `retry:` are read and dropped, and dropping one must not cost the
        // payload lines on either side of it.
        assertEquals(listOf("one\ntwo"), feedBody("data: one\nevent: message\nid: 7\ndata: two\n\n"))
    }

    @Test
    fun anEventThatOutgrowsTheCapStopsTheStreamWithASentence() {
        // The machine's only unbounded input. Half a megabyte of payload is not delivered, the
        // sentence says what happened, and nothing else on the socket is read.
        val huge = "x".repeat(Sse.MAX_EVENT_CHARS + 1)
        val out = feed("data: $huge", "data: more", "")
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].contains(Sse.MAX_EVENT_CHARS.toString()))
        assertTrue(sse.done)
        assertEquals(emptyList<String>(), sse.finish())
    }

    @Test
    fun anEventExactlyAtTheCapIsStillDelivered() {
        // The other side of the boundary: a cap that rejected an event of the size it allows would
        // be a cap nobody could rely on.
        val body = "y".repeat(Sse.MAX_EVENT_CHARS)
        assertEquals(listOf(body), feedBody("data: $body\n\n"))
        assertFalse(sse.done)
    }
}
