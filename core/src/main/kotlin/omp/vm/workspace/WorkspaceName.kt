package omp.vm.workspace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The name of a conversation folder, in one place, because the name is the one piece of user input
 * that becomes a directory on a phone's shared storage and is then typed into a file manager by a
 * person. Everything hostile about it — a slash, a `..`, a newline, 300 characters of it — has to be
 * dealt with once, here, and not by each caller differently.
 *
 * Two properties are non-negotiable and the rest is taste:
 *
 * - **The result can be concatenated into a host path and cannot escape it.** No separator survives,
 *   so there is nothing left that could climb out of the root, and `.` and `..` cannot come out the
 *   other end because a leading dot is stripped.
 * - **The result is the name the user asked for, as far as that is possible.** Nothing here
 *   normalises UTF-8 into ASCII: `café` and `заметки` are names a person typed, and transliterating
 *   them into `cafe` is the app deciding what the user's own words may look like. Nor is anything
 *   normalised *within* Unicode — see [sanitize] on combining marks. What is removed is only what is
 *   not part of a name: separators, control characters, and the punctuation a filesystem reserves.
 */
object WorkspaceName {

    /**
     * The cap, in code points — not in the UTF-16 units `String.length` counts, which is why the
     * cut is made with [String.offsetByCodePoints] and not with a substring.
     *
     * 60 code points is at most 240 bytes in UTF-8, which fits the 255-byte limit Android's
     * `DocumentsProvider` and the exFAT volume behind `/storage/emulated/0/Documents/omp` put on a
     * single path component even when every character is astral. It also leaves a name short enough
     * to read in the file manager's own listing, which is where the user meets it, and short enough
     * that a [Workspace.create] collision suffix is inside the budget rather than past it.
     */
    const val MAX = 60

    /**
     * The name for a conversation the user did not name, from the clock the [Workspace] was built
     * with: `session-YYYYMMDD-HHMM`, in the device's own zone like every other timestamp this shell
     * prints. A `:` where a `:` goes is the one thing that would be genuinely worse, and it is why
     * this is not `SimpleDateFormat("yyyy-MM-dd HH:mm")`.
     */
    fun generated(millis: Long): String =
        "session-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(millis))

    /**
     * Cuts [name] to [max] code points, on a code-point boundary.
     *
     * Every name this class produces is already within [MAX]; this exists for the one place a name
     * grows afterwards — the `-2` a collision adds in [Workspace.create] — so that a long name is
     * shortened rather than allowed to become a path component a filesystem will refuse. The cut is
     * `offsetByCodePoints`, which is a code-point boundary by construction: a substring at a fixed
     * index would be free to land in the middle of a surrogate pair and leave half a character in a
     * folder name.
     */
    fun fit(name: String, max: Int = MAX): String {
        if (name.codePointCount(0, name.length) <= max) return name
        return name.substring(0, name.offsetByCodePoints(0, max))
    }

    /**
     * [base] with a `-<suffix>` for a collision, still inside [MAX].
     *
     * The suffix is part of the name and has to be paid for out of the same budget: cutting the
     * joined string to [MAX] would drop the very digits that make it a different folder, and the
     * second conversation would be the first one again. So the base gives up the room instead.
     */
    fun withSuffix(base: String, suffix: Int): String {
        val tail = "-$suffix"
        return fit(base, MAX - tail.length) + tail
    }

    /**
     * @return a directory name that is safe to concatenate into [Workspace.root]'s host path, or
     * null when **nothing in [raw] survived** — because it was blank, or because every code point in
     * it is one that cannot be part of a name. That is a deliberately wide set: `..`, `///`, a
     * newline, a single emoji, a flag, a zero-width joiner. Null is the honest answer rather than a
     * placeholder like `untitled`, because what an unusable name means is the caller's decision —
     * [Workspace.create] answers it with a generated name, and a command that would rather refuse
     * can say so itself.
     */
    fun sanitize(raw: String): String? {
        if (raw.isBlank()) return null
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // A newline, a tab, a bell: part of no filename, and a rendering problem in every
                // tool that ever prints the name back.
                Character.isISOControl(cp) -> Unit
                // `/` is the separator here. `\` goes too, not because Linux needs it gone but
                // because the same folder is reachable from a desktop over adb, and a name that is
                // a path on the machine the user copies it to is not a name.
                cp == '/'.code || cp == '\\'.code -> Unit
                // Runs of spaces collapse to one dash. `isSpaceChar` is the Unicode answer and it
                // covers the non-breaking space a phone keyboard can produce too. Keeping spaces is
                // legal and hostile: every shell the name is later typed into has to quote it.
                Character.isSpaceChar(cp) -> if (out.isNotEmpty() && out.last() != '-') out.append('-')
                // Letters, digits and the punctuation a filename tolerates — and the combining
                // marks, which are not letters and are still part of the name. `Character
                // .isLetterOrDigit` is false for a mark, and dropping one silently deletes the
                // accent the user typed: Android keyboards and macOS both emit decomposed text, so
                // `e`+U+0301 and `é` would become two different folders for one word. Normalising
                // to NFC would fix that pairing and break another one — the folder on disk would
                // stop being the characters the user gave, which is the one thing this class
                // promises not to do quietly.
                Character.isLetterOrDigit(cp) || isMark(cp) || cp == '-'.code || cp == '_'.code ||
                    cp == '.'.code -> out.appendCodePoint(cp)
                // Everything else — a `?`, a `*`, an emoji, a zero-width joiner — is dropped rather
                // than escaped, since a name is a name and not a shell token.
                else -> Unit
            }
        }
        // A leading `-` reads as a flag and a leading `.` reads as hidden or as `..`; neither is
        // what a conversation folder is. Trailing dots and dashes go for the same reason a collision
        // would otherwise render as `notes--2`.
        var start = 0
        while (start < out.length && (out[start] == '-' || out[start] == '.')) start++
        if (start >= out.length) return null
        val trimmed = out.substring(start)
        var end = trimmed.length
        while (end > 0 && (trimmed[end - 1] == '-' || trimmed[end - 1] == '.')) end--
        if (end == 0) return null
        return fit(trimmed.substring(0, end))
    }

    /**
     * A combining mark: something with no width of its own that belongs to the character before it,
     * and which must therefore be kept or the name is spelled differently from what was typed.
     *
     * Variation selectors are the exception. They are marks, but what they modify is an emoji — and
     * an emoji is not a filename character, so the base is already gone by the time the selector
     * is reached. What would be left is a name made entirely of invisible characters, which is
     * worse than a name that is refused: the user gets a folder they cannot see or type again.
     */
    private fun isMark(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return false
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}
