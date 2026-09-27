package omp.vm

import omp.vm.workspace.WorkspaceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name sanitiser, on the inputs that would actually hurt. A conversation folder ends up in the
 * user's `Documents` and is typed into a file manager by a person, so every one of these is a real
 * way a name can go wrong, not a theoretical one.
 */
class WorkspaceNameTest {

    /** A letter outside the BMP: two `char`s, one character, and the case a cap has to get right. */
    private val deseret = "𐐀"

    @Test
    fun anOrdinaryNameIsLeftExactlyAsTyped() {
        for (name in listOf("notes", "Photo-sort", "v1.2.3", "under_score", "SESSION-20250904-1533", "42")) {
            assertEquals(name, name, WorkspaceName.sanitize(name))
        }
    }

    @Test
    fun nothingSurvivesWhenThereWasNothingToKeep() {
        assertNull(WorkspaceName.sanitize(""))
        assertNull(WorkspaceName.sanitize("   "))
        assertNull(WorkspaceName.sanitize("\t\n "))
        // `.` and `..` are not names, they are instructions, and neither is a conversation.
        assertNull(WorkspaceName.sanitize("."))
        assertNull(WorkspaceName.sanitize(".."))
        assertNull(WorkspaceName.sanitize("./."))
        assertNull(WorkspaceName.sanitize("///"))
        assertNull(WorkspaceName.sanitize(".".repeat(300)))
    }

    @Test
    fun aNameOfNothingButUnusableCharactersHasNothingToKeep() {
        // These are names a person can type and no folder can have. Null is the documented answer,
        // and the caller decides what to do about it — the point is that it is not a name.
        for (raw in listOf("🎉", "🇩🇪", "‍", "️", "\uD83C", "!!!")) {
            assertNull(raw, WorkspaceName.sanitize(raw))
        }
        // A name that is only unusable *around* its letters keeps the letters: the punctuation is
        // not part of a folder name, and refusing the whole thing would be refusing the user's own
        // words over a question mark.
        assertEquals("abc", WorkspaceName.sanitize("a?b*c"))
    }

    @Test
    fun aPathCannotSurviveAsAName() {
        // Everything a traversal needs is a separator, and no separator comes out the other end.
        assertFalse(WorkspaceName.sanitize("../../etc/shadow")!!.contains('/'))
        assertEquals("etcshadow", WorkspaceName.sanitize("../../etc/shadow"))
        assertEquals("ab", WorkspaceName.sanitize("a/b"))
        assertEquals("etcpasswd", WorkspaceName.sanitize("/etc/passwd"))
        assertFalse(WorkspaceName.sanitize("C:\\Windows")!!.contains('\\'))
    }

    @Test
    fun aNameNeverStartsWithSomethingThatReadsAsAFlagOrAHiddenFile() {
        for (raw in listOf(".hidden", "..hidden", "-flag", "--flag", " .x", "...x")) {
            val safe = WorkspaceName.sanitize(raw)!!
            assertFalse("'$raw' produced '$safe'", safe.startsWith("-") || safe.startsWith("."))
        }
        assertEquals("flag", WorkspaceName.sanitize("-flag"))
        assertEquals("hidden", WorkspaceName.sanitize(".hidden"))
        assertEquals("x", WorkspaceName.sanitize(" .x"))
        // A name that was nothing but punctuation is not a name at all, and gets no placeholder.
        for (raw in listOf("---", ".-", "-.", "...", "-.-")) {
            assertNull(raw, WorkspaceName.sanitize(raw))
        }
    }

    @Test
    fun aNewlineIsNotAName() {
        val safe = WorkspaceName.sanitize("two\nlines")!!
        assertEquals("twolines", safe)
        assertFalse(safe.contains('\n'))
        // A leading newline is a control character like any other: dropped, not turned into a dash.
        assertEquals("ab", WorkspaceName.sanitize("\nab"))
        assertEquals("ab", WorkspaceName.sanitize("a\u0007b"))
    }

    @Test
    fun aVeryLongNameIsCappedInCodePointsNotInUtf16Units() {
        assertEquals(WorkspaceName.MAX, points(WorkspaceName.sanitize("a".repeat(300))!!))
        assertEquals(WorkspaceName.MAX, points(WorkspaceName.sanitize("x".repeat(1_000))!!))
        // A cap counted in `String.length` would come back as 30 characters of a 60-character name,
        // and would cut a surrogate pair in half on the way. The whole point of the assertion is
        // that it is exactly 60 *characters*, which is 120 units for this input.
        val astral = WorkspaceName.sanitize(deseret.repeat(100))!!
        assertEquals(WorkspaceName.MAX, points(astral))
        assertEquals(deseret.repeat(WorkspaceName.MAX), astral)
        // Nothing above is a truncated character: every code point is a whole letter. Counted as
        // code points, because half a surrogate pair is not a letter and would fail here.
        var at = 0
        while (at < astral.length) {
            val cp = astral.codePointAt(at)
            assertTrue(astral, Character.isLetter(cp))
            at += Character.charCount(cp)
        }
    }

    @Test
    fun aSuffixedNameStaysInsideTheCapAndKeepsItsSuffix() {
        // What `create` does on a collision: append the suffix, and pay for it out of the base's
        // budget rather than past the cap — cutting the joined string would drop the very digits
        // that make it a different folder, and the second conversation would be the first one again.
        val base = WorkspaceName.sanitize("a".repeat(100))!!
        val suffixed = WorkspaceName.withSuffix(base, 2)
        assertEquals(WorkspaceName.MAX, points(suffixed))
        assertTrue(suffixed, suffixed.endsWith("-2"))
        assertEquals("a".repeat(WorkspaceName.MAX - 2) + "-2", suffixed)
        // A base that is already short keeps all of itself.
        assertEquals("notes-2", WorkspaceName.withSuffix("notes", 2))
        // And a long base of astral characters is cut on a character boundary, not a unit one.
        val wide = WorkspaceName.withSuffix(deseret.repeat(80), 12)
        assertEquals(WorkspaceName.MAX, points(wide))
        assertTrue(wide, wide.endsWith("-12"))
        assertEquals(deseret.repeat(WorkspaceName.MAX - 3) + "-12", wide)
        // `fit` on its own is the plain cut, and leaves an already-short name alone.
        assertEquals("notes-2", WorkspaceName.fit("notes-2"))
    }

    @Test
    fun spacesCollapseToOneDashRatherThanSurviving() {
        assertEquals("my-project", WorkspaceName.sanitize("my project"))
        assertEquals("my-project", WorkspaceName.sanitize("  my   project  "))
        assertEquals("a-b", WorkspaceName.sanitize("a b"))
        // The Unicode spaces a phone keyboard can produce, and the one this test is really about:
        // a plain ASCII space in the line above would pass even if U+00A0 were dropped entirely.
        assertEquals("a-b", WorkspaceName.sanitize("a\u00a0b"))
        assertEquals("a-b", WorkspaceName.sanitize("a\u2003b"))
        assertEquals("a-b", WorkspaceName.sanitize("a\u3000b"))
    }

    @Test
    fun utf8IsKeptAsUtf8AndNotMangledIntoAscii() {
        // A name a person typed is not this app's to transliterate.
        for (name in listOf("café", "naïve-récap", "заметки", "日本語", "Ünïcödé")) {
            assertEquals(name, name, WorkspaceName.sanitize(name))
        }
        // A separator is removed, not turned into a dash: `a/b` and `a b` are two different things
        // the user typed and must not quietly become the same folder.
        assertEquals("café2", WorkspaceName.sanitize("  café/2  "))
    }

    @Test
    fun aCombiningMarkIsPartOfTheNameAndNotSomethingToDelete() {
        // What an Android keyboard and macOS actually emit for a café: the accent is a separate code
        // point, it is not a letter, and deleting it would silently spell the folder something else.
        val decomposed = "cafe\u0301"
        assertEquals(decomposed, WorkspaceName.sanitize(decomposed))
        assertEquals("cafe\u0301", WorkspaceName.sanitize("  cafe\u0301  "))
        // And a mark the user put first, which some keyboards and a lot of bad input produce.
        assertEquals("a\u0327\u0301", WorkspaceName.sanitize("a\u0327\u0301"))
        // Not normalised in either direction: the folder keeps the characters that were given.
        assertEquals(decomposed, WorkspaceName.sanitize(decomposed))
        assertEquals("café", WorkspaceName.sanitize("café"))
    }

    @Test
    fun theGeneratedNameIsTheDocumentedPattern() {
        assertTrue(WorkspaceName.generated(1_757_000_000_000L).matches(Regex("session-\\d{8}-\\d{4}")))
        // Same instant, same name: a generated name is reproducible, so a test can name a session.
        assertEquals(
            WorkspaceName.generated(1_757_000_000_000L),
            WorkspaceName.generated(1_757_000_000_000L),
        )
        // A different minute is a different name, and nothing in it is a character a filesystem or
        // a shell would have to quote.
        val later = WorkspaceName.generated(1_757_000_060_000L)
        assertFalse(later == WorkspaceName.generated(1_757_000_000_000L))
        assertTrue(later, later.all { it.isLetterOrDigit() || it == '-' })
    }

    private fun points(text: String): Int = text.codePointCount(0, text.length)
}
