package omp.agent.json

/**
 * A JSON value, parsed.
 *
 * Written by hand because this project takes no dependencies, and the README's whole claim is that
 * every command is Kotlin inside the app's own sandbox: a model reply arrives over a socket, and
 * handing it to a library would put a jar in there. So this is the parser, and it is deliberately
 * **strict**.
 *
 * Strict is the requirement, not a style. The only things that will ever be parsed here are
 * responses from an HTTPS endpoint the user configured, and the failure mode of a lenient parser on
 * a bad response is to guess: a truncated body read as a whole object, a `nan` folded into a
 * number the agent will then print, a second document silently ignored. Each of those surfaces as a
 * hallucinated token rather than as an error, and a hallucinated token in a shell is worse than a
 * command that failed. So a bad escape, an unterminated string, a trailing comma, a bare `nan`, a
 * second top-level value and a single byte after the last one are all [JsonException]s, and there
 * is no lenient mode to turn off.
 *
 * [Num] keeps [Num.raw] because `Double` is a lie about integers. An id or a timestamp in a tool
 * result can be 20 digits, `Double.toString` will round it, and the number the user then sees is
 * not the number the server sent. [Num.value] is for arithmetic; [Num.raw] is what
 * [toCompactString] re-emits — when it is a number at all, since a [Num] built by hand can carry a
 * raw this parser would refuse, and emitting that would break the promise [toCompactString] makes —
 * and two [Num]s that share a [Num.value] but not a [Num.raw] are not equal because the spelling is
 * part of what this type exists to keep.
 *
 * A **duplicate key** is not an error. The document is valid JSON, and last one wins — what every
 * mainstream parser does, and what [Obj] can express without a second map. The earlier value is
 * gone, not merely shadowed.
 */
sealed class Json {

    /** A JSON object. A repeated key keeps the last value; see the note on [Json]. */
    data class Obj(val fields: Map<String, Json>) : Json() {
        override fun toCompactString(): String = buildString {
            append('{')
            var first = true
            for ((key, item) in fields) {
                if (!first) append(',')
                first = false
                append('"')
                for (c in key) appendEscaped(c)
                append('"')
                append(':')
                append(item.toCompactString())
            }
            append('}')
        }
    }

    /** A JSON array, in document order. */
    data class Arr(val items: List<Json>) : Json() {
        override fun toCompactString(): String = buildString {
            append('[')
            for ((i, item) in items.withIndex()) {
                if (i > 0) append(',')
                append(item.toCompactString())
            }
            append(']')
        }
    }

    /** A JSON string, with its escapes already resolved. */
    data class Str(val value: String) : Json() {
        /**
         * Re-emits a lone surrogate as an escape, which is what keeps the output encodable as
         * UTF-8; a pair goes out as two escapes, which is legal JSON and parses back to one char.
         */
        override fun toCompactString(): String = buildString {
            append('"')
            for (c in value) appendEscaped(c)
            append('"')
        }
    }

    /**
     * A JSON number. [value] is it as a [Double], which is lossy past 2^53; [raw] is the exact
     * text of the literal, which is the reason this class carries two of them.
     */
    data class Num(val value: Double, val raw: String) : Json() {
        /**
         * [raw] when it is a JSON number, and the [Double]'s own spelling when it is not.
         *
         * [raw] is a constructor argument, so a [Num] can be built out of text this very class
         * would refuse to parse — `Num(1.0, "01")`, `Num(0.0, "")` — and emitting that would make
         * this method's promise false for a value the caller built by hand. So the raw is checked
         * against the number grammar and the double's canonical form is emitted when it fails, which
         * is always something [parse] accepts: `Double.toString` writes a decimal with an exponent if
         * it needs one and no leading zero if it does not. A double that is not finite has no JSON
         * spelling at all, and `null` — the only value in the language that means *there is no
         * number here* — is what stands in for it.
         */
        override fun toCompactString(): String = when {
            isJsonNumber(raw) -> raw
            value.isFinite() -> value.toString()
            else -> "null"
        }
    }

    /** `true` or `false` — never the strings `"true"` and `"false"`. */
    data class Bool(val value: Boolean) : Json() {
        override fun toCompactString(): String = if (value) "true" else "false"
    }

    /** The literal `null` — never the string `"null"`. */
    object Null : Json() {
        override fun toCompactString(): String = "null"
    }

    /** The value again as JSON, with no whitespace in it and always safe to hand back to [parse]. */
    abstract fun toCompactString(): String

    override fun toString(): String = toCompactString()

    /** The value at [key], or null when this is not an object or has no such field. */
    fun field(key: String): Json? = (this as? Obj)?.fields?.get(key)

    /** The string at [key], or null. A number or `null` under that key is not a string. */
    fun str(key: String): String? = (field(key) as? Str)?.value

    /**
     * The array at [key], or null. The items rather than the [Arr] wrapper, because a caller
     * wants to walk them; a field that is not an array answers null rather than an empty list, so
     * "absent" and "wrong shape" both stay visible.
     */
    fun arr(key: String): List<Json>? = (field(key) as? Arr)?.items

    /**
     * The integer at [key], or null when the field is missing, is not a number, is not written as
     * an integer, or does not fit a [Long].
     *
     * **The literal's spelling decides, not its value.** A number with no `.` and no exponent is
     * read straight out of [Num.raw], so 19 digits come back exactly; one that is written
     * `1e3` or `2.0` is a `Double` as far as this is concerned and answers null even though both
     * are whole numbers. That is the only answer that cannot be a plausible wrong one: 2^63 is
     * `9.223372036854775808E18` as a double, which is inside the range a `Long` comparison written
     * in double arithmetic believes in, and converting it yields `Long.MIN_VALUE` rather than an
     * error — a 19-digit value silently turned into a negative one. A caller that wants an exponent
     * form read as an integer has to ask for the string.
     */
    fun long(key: String): Long? {
        val num = field(key) as? Num ?: return null
        if ('.' in num.raw || 'e' in num.raw || 'E' in num.raw) return null
        return num.raw.toLongOrNull()
    }

    /** The boolean at [key], or null. */
    fun bool(key: String): Boolean? = (field(key) as? Bool)?.value

    companion object {
        /**
         * @throws JsonException on anything that is not exactly one well-formed JSON value, with
         *   the UTF-8 byte offset the refusal happened at.
         */
        fun parse(text: String): Json = Parser(text).document()
    }
}

/**
 * A refusal by [parse]. [offset] is a **byte** offset into the UTF-8 encoding of the input, so it
 * can be shown against a byte cursor; the structural characters of JSON are all ASCII, so for
 * everything this parser can complain about it is also the character index.
 */
class JsonException(message: String, val offset: Int) :
    IllegalArgumentException("$message at offset $offset")

/** Quotation, backslash, the two whitespace controls, and everything below U+0020. */
private fun StringBuilder.appendEscaped(c: Char) {
    when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\u0008' -> append("\\b")
        '\u000C' -> append("\\f")
        else ->
            if (c < ' ' || c in '\uD800'..'\uDFFF') {
                append("\\u")
                for (shift in intArrayOf(12, 8, 4, 0)) append(HEX[(c.code shr shift) and 0xF])
            } else {
                append(c)
            }
    }
}

private const val HEX = "0123456789abcdef"

/**
 * Whether [s] is exactly the JSON number grammar — no leading `+`, no leading zero, no bare `.5`, no
 * trailing point, nothing left over — which is what [Json.Num.toCompactString] has to be able to
 * say about a [Json.Num.raw] it did not produce itself. It is deliberately the same shape the
 * parser's own `number()` accepts, so the two cannot drift into disagreeing about what a number is.
 */
private fun isJsonNumber(s: String): Boolean {
    var i = 0
    if (i < s.length && s[i] == '-') i++
    if (i >= s.length) return false
    when (s[i]) {
        '0' -> {
            i++
            if (i < s.length && s[i] in '0'..'9') return false
        }
        in '1'..'9' -> while (i < s.length && s[i] in '0'..'9') i++
        else -> return false
    }
    if (i < s.length && s[i] == '.') {
        i++
        if (i >= s.length || s[i] !in '0'..'9') return false
        while (i < s.length && s[i] in '0'..'9') i++
    }
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        i++
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        if (i >= s.length || s[i] !in '0'..'9') return false
        while (i < s.length && s[i] in '0'..'9') i++
    }
    return i == s.length
}

/**
 * The recursive-descent parser. It is written over [String] rather than a `Reader` because a
 * [JsonException] offset is a character index first, and because the whole document is a socket's
 * worth of text either way.
 */
private class Parser(private val text: String) {

    private var pos = 0
    private var depth = 0

    fun document(): Json {
        skipWhitespace()
        if (pos >= text.length) {
            if (pos == 0) fail("no JSON value in an empty document")
            fail("no JSON value in a document that is only whitespace")
        }
        val value = value()
        skipWhitespace()
        if (pos < text.length) fail("trailing data after the top-level value")
        return value
    }

    private fun value(): Json = when (val c = peek()) {
        '{' -> obj()
        '[' -> arr()
        '"' -> Json.Str(string())
        't' -> literal("true", Json.Bool(true))
        'f' -> literal("false", Json.Bool(false))
        'n' -> literal("null", Json.Null)
        '-', in '0'..'9' -> number()
        // A byte-order mark is the one character here that cannot be quoted usefully: it is
        // invisible, so a message containing it looks like it is complaining about nothing, and the
        // reader is left hunting for a character that is on the screen and not in the text.
        '\uFEFF' -> fail("the document starts with a byte-order mark, which is not JSON")
        else -> fail("'$c' is not the start of a JSON value")
    }

    private fun obj(): Json {
        pos++
        depth++
        if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH levels")
        val fields = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (pos >= text.length) fail("unterminated object")
        if (text[pos] == '}') {
            pos++
            depth--
            return Json.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (pos >= text.length) fail("unterminated object")
            if (text[pos] != '"') fail("a field name has to be a string in double quotes")
            val key = string()
            skipWhitespace()
            if (pos >= text.length) fail("unterminated object")
            if (text[pos] != ':') fail("a field name has to be followed by ':'")
            pos++
            skipWhitespace()
            // A repeated key overwrites: last one wins, the way every mainstream parser reads it.
            fields[key] = value()
            skipWhitespace()
            if (pos >= text.length) fail("unterminated object")
            when (text[pos]) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (pos < text.length && text[pos] == '}') fail("a trailing comma before '}'")
                }
                '}' -> {
                    pos++
                    depth--
                    return Json.Obj(fields)
                }
                else -> fail("an object is separated by ',' and closed by '}'")
            }
        }
    }

    private fun arr(): Json {
        pos++
        depth++
        if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH levels")
        val items = ArrayList<Json>()
        skipWhitespace()
        if (pos >= text.length) fail("unterminated array")
        if (text[pos] == ']') {
            pos++
            depth--
            return Json.Arr(items)
        }
        while (true) {
            skipWhitespace()
            items += value()
            skipWhitespace()
            if (pos >= text.length) fail("unterminated array")
            when (text[pos]) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (pos < text.length && text[pos] == ']') fail("a trailing comma before ']'")
                }
                ']' -> {
                    pos++
                    depth--
                    return Json.Arr(items)
                }
                else -> fail("an array is separated by ',' and closed by ']'")
            }
        }
    }

    private fun literal(word: String, value: Json): Json {
        if (!text.startsWith(word, pos)) fail("expected '$word'")
        pos += word.length
        return value
    }

    private fun number(): Json.Num {
        val start = pos
        if (text[pos] == '-') pos++
        if (pos >= text.length) fail("truncated number")
        when (val c = text[pos]) {
            '0' -> {
                pos++
                if (pos < text.length && text[pos].isAsciiDigit()) fail("a number has no leading zero")
            }
            in '1'..'9' -> while (pos < text.length && text[pos].isAsciiDigit()) pos++
            else -> fail("'$c' is not a digit where a number's integer part should be")
        }
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || !text[pos].isAsciiDigit()) fail("a decimal point needs a digit after it")
            while (pos < text.length && text[pos].isAsciiDigit()) pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || !text[pos].isAsciiDigit()) fail("an exponent needs a digit in it")
            while (pos < text.length && text[pos].isAsciiDigit()) pos++
        }
        val raw = text.substring(start, pos)
        return Json.Num(raw.toDouble(), raw)
    }

    private fun string(): String {
        pos++
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos]
            when {
                c == '"' -> {
                    pos++
                    return out.toString()
                }
                c == '\\' -> {
                    pos++
                    escape(out)
                }
                c < ' ' -> fail("a raw control character has to be escaped inside a string")
                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
    }

    private fun escape(out: StringBuilder) {
        if (pos >= text.length) fail("a string ends in the middle of an escape")
        when (val c = text[pos++]) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> unicode(out)
            else -> {
                pos--
                fail("'\\$c' is not a JSON escape")
            }
        }
    }

    /**
     * A `\u` escape, with a surrogate pair folded into the one character it stands for. A lone
     * surrogate is kept exactly as written: replacing it with U+FFFD would change the model's
     * text, and dropping it would change its length, and neither is this parser's decision to make
     * on someone else's behalf.
     */
    private fun unicode(out: StringBuilder) {
        val first = hex4()
        if (first in 0xD800..0xDBFF && pos + 1 < text.length && text[pos] == '\\' && text[pos + 1] == 'u') {
            val after = pos + 2
            if (after + 4 <= text.length) {
                val second = hex4At(after)
                if (second in 0xDC00..0xDFFF) {
                    pos = after + 4
                    out.append(first.toChar())
                    out.append(second.toChar())
                    return
                }
            }
        }
        out.append(first.toChar())
    }

    private fun hex4(): Int = hex4At(pos).also { pos += 4 }

    private fun hex4At(at: Int): Int {
        if (at + 4 > text.length) fail("a '\\u' escape needs four hex digits")
        var value = 0
        for (i in 0 until 4) {
            val digit = text[at + i].digitToIntOrNull(16)
                ?: fail("'${text[at + i]}' is not a hex digit in a '\\u' escape", at + i)
            value = value * 16 + digit
        }
        return value
    }

    private fun peek(): Char {
        if (pos >= text.length) fail("the document ends where a value should be")
        return text[pos]
    }

    private fun skipWhitespace() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun fail(message: String, at: Int = pos): Nothing = throw JsonException(message, byteOffset(at))

    /** UTF-8 length of the text before [at]; a surrogate pair counts as the one 4-byte character. */
    private fun byteOffset(at: Int): Int {
        var bytes = 0
        var i = 0
        val end = minOf(at, text.length)
        while (i < end) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < end && text[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    private companion object {
        /**
         * Deep enough for anything a model emits — tool-call arguments nest a handful of levels —
         * and shallow enough that a hostile or broken response is a [JsonException] with a message
         * rather than a `StackOverflowError` with a stack trace nobody can read.
         */
        const val MAX_DEPTH = 64
    }
}
