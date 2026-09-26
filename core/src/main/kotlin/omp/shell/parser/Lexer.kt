package omp.shell.parser

/** Raised for anything the shell cannot make sense of; the REPL prints the message and keeps going. */
class ShellParseException(message: String) : Exception(message)

/** The one definition of shell whitespace, shared by the word lexer and word splitting. */
fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

enum class ParamOp {
    VALUE, LENGTH,
    DEFAULT, ASSIGN, ERROR,
    REMOVE_PREFIX, REMOVE_PREFIX_LONG,
    REMOVE_SUFFIX, REMOVE_SUFFIX_LONG,
}

sealed class Segment {
    /** Text that is already final. [quoted] suppresses field splitting and globbing for it. */
    data class Lit(val text: String, val quoted: Boolean) : Segment()

    data class Param(
        val name: String,
        val op: ParamOp = ParamOp.VALUE,
        /** The unexpanded right-hand side of `:-`, `:=`, `:?`, `#`, `%`, ... */
        val arg: String? = null,
        val quoted: Boolean = false,
    ) : Segment()

    data class CmdSubst(val body: String, val quoted: Boolean) : Segment()

    data class Arith(val body: String, val quoted: Boolean) : Segment()

    val isQuoted: Boolean
        get() = when (this) {
            is Lit -> quoted
            is Param -> quoted
            is CmdSubst -> quoted
            is Arith -> quoted
        }
}

/**
 * A command word. [raw] is the source text as typed, kept for diagnostics; [segments] is the
 * authoritative form the expander walks.
 */
class Word(
    val raw: String,
    val segments: List<Segment>,
) {
    /** Any quoted part suppresses splitting and globbing for that part only. */
    val quoted: Boolean = segments.any { it.isQuoted }

    /** Unquoted literal text, which is what brace expansion and globbing look at. */
    val literalText: String
        get() = buildString {
            for (s in segments) if (s is Segment.Lit && !s.quoted) append(s.text)
        }

    val isPureLiteral: Boolean
        get() = segments.isNotEmpty() && segments.all { it is Segment.Lit }

    override fun toString(): String = raw
}

enum class TokenType { WORD, ASSIGN, PIPE, AND_IF, OR_IF, SEMI, BG, REDIR }

class Token(val type: TokenType, val text: String = "") {
    var word: Word? = null
        internal set
    var assignName: String? = null
        internal set
    var redirOp: String? = null
        internal set

    override fun toString(): String = "$type($text)"
}

class Lexer(private val src: String) {
    private var pos = 0

    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            skipBlanks()
            if (pos >= src.length) break
            val c = src[pos]
            if (c == '#') {
                while (pos < src.length && src[pos] != '\n') pos++
                continue
            }
            when {
                c == '\n' -> {
                    pos++
                    out += Token(TokenType.SEMI, "\n")
                }
                c == '|' && peek(1) == '|' -> {
                    pos += 2
                    out += Token(TokenType.OR_IF, "||")
                }
                c == '|' -> {
                    pos++
                    out += Token(TokenType.PIPE, "|")
                }
                c == '&' && peek(1) == '&' -> {
                    pos += 2
                    out += Token(TokenType.AND_IF, "&&")
                }
                c == ';' && peek(1) == ';' -> {
                    pos += 2
                    out += Token(TokenType.SEMI, ";;")
                }
                c == ';' -> {
                    pos++
                    out += Token(TokenType.SEMI, ";")
                }
                c == '&' -> {
                    pos++
                    out += Token(TokenType.BG, "&")
                }
                c == '>' || c == '<' || (c == '2' && peek(1) == '>') || (c == '&' && peek(1) == '>') ->
                    out += lexRedirect()
                else -> out += lexWordOrAssign()
            }
        }
        return out
    }

    private fun peek(n: Int): Char = if (pos + n < src.length) src[pos + n] else ' '

    private fun skipBlanks() {
        while (pos < src.length && isWhitespace(src[pos])) pos++
    }

    private fun lexRedirect(): Token {
        val start = pos
        if (src[pos] == '&') {
            pos += 2
        } else if (src[pos] == '2' && peek(1) == '>') {
            pos++
            if (peek(0) == '>') pos++
        } else if (src[pos] == '>') {
            pos++
            if (peek(0) == '>') pos++
        } else {
            pos++
        }
        val t = Token(TokenType.REDIR, src.substring(start, pos))
        t.redirOp = src.substring(start, pos)
        skipBlanks()
        // `2>&1` dups a descriptor: the target is `&N`, which a plain word scan would split on `&`.
        if (pos < src.length && src[pos] == '&') {
            val dupStart = pos
            pos++
            if (pos < src.length && src[pos] in '0'..'9') pos++
            val text = src.substring(dupStart, pos)
            t.word = Word(text, listOf(Segment.Lit(text, false)))
            return t
        }
        t.word = lexWord()
        return t
    }

    private fun lexWordOrAssign(): Token {
        if (pos < src.length && isNameStart(src[pos])) {
            var p = pos + 1
            while (p < src.length && isNameChar(src[p])) p++
            if (p < src.length && src[p] == '=') {
                val name = src.substring(pos, p)
                pos = p + 1
                val t = Token(TokenType.ASSIGN, name + "=")
                t.assignName = name
                t.word = lexWord()
                return t
            }
        }
        val t = Token(TokenType.WORD)
        t.word = lexWord()
        return t
    }

    private fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'
    private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9'

    private fun lexWord(): Word {
        val start = pos
        val segments = ArrayList<Segment>()
        val lit = StringBuilder()
        var litQuoted = false

        fun flush() {
            if (lit.isNotEmpty()) {
                segments += Segment.Lit(lit.toString(), litQuoted)
                lit.setLength(0)
            }
        }

        while (pos < src.length) {
            val c = src[pos]
            if (isWhitespace(c) || c == '|' || c == ';' || c == '&' || c == '>' || c == '<') break
            if (c == '#' && lit.isEmpty() && segments.isEmpty()) break
            when (c) {
                '\'' -> {
                    flush()
                    litQuoted = true
                    val open = pos + 1
                    val end = src.indexOf('\'', open)
                    if (end < 0) throw ShellParseException("unexpected EOF while looking for matching '''")
                    segments += Segment.Lit(src.substring(open, end), true)
                    pos = end + 1
                }
                '"' -> {
                    flush()
                    litQuoted = true
                    lexDoubleQuoted(segments)
                }
                '\\' -> {
                    flush()
                    pos++
                    if (pos >= src.length) throw ShellParseException("unexpected EOF while looking for matching '\\'")
                    val e = src[pos]
                    if (e == '\n') {
                        pos++
                    } else {
                        lit.append(unescapeChar(e))
                        litQuoted = true
                        pos++
                    }
                }
                '$' -> {
                    val seg = lexDollar()
                    if (seg == null) {
                        lit.append('$')
                        pos++
                    } else {
                        flush()
                        segments += seg
                    }
                }
                '`' -> {
                    flush()
                    pos++
                    val end = src.indexOf('`', pos)
                    if (end < 0) throw ShellParseException("unexpected EOF while looking for matching '`'")
                    segments += Segment.CmdSubst(src.substring(pos, end), false)
                    pos = end + 1
                }
                else -> {
                    lit.append(c)
                    pos++
                }
            }
        }
        flush()
        return Word(src.substring(start, pos), segments)
    }

    private fun unescapeChar(e: Char): Char = when (e) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        '0' -> Char(0)
        'a' -> Char(7)
        'b' -> '\b'
        'f' -> Char(12)
        'v' -> Char(11)
        'e' -> Char(27)
        else -> e
    }

    private fun lexDoubleQuoted(segments: ArrayList<Segment>) {
        pos++
        while (true) {
            if (pos >= src.length) throw ShellParseException("unexpected EOF while looking for matching '\"'")
            val c = src[pos]
            when (c) {
                '"' -> {
                    pos++
                    return
                }
                '\\' -> {
                    val n = if (pos + 1 < src.length) src[pos + 1] else ' '
                    if (n == '$' || n == '`' || n == '"' || n == '\\') {
                        segments += Segment.Lit(n.toString(), true)
                        pos += 2
                    } else if (n == '\n') {
                        pos += 2
                    } else {
                        segments += Segment.Lit("\\", true)
                        pos++
                    }
                }
                '$' -> {
                    val seg = lexDollar()
                    if (seg == null) {
                        segments += Segment.Lit("$", true)
                        pos++
                    } else {
                        segments += when (seg) {
                            is Segment.CmdSubst -> seg.copy(quoted = true)
                            is Segment.Arith -> seg.copy(quoted = true)
                            is Segment.Param -> seg.copy(quoted = true)
                            is Segment.Lit -> seg
                        }
                    }
                }
                '`' -> {
                    pos++
                    val end = src.indexOf('`', pos)
                    if (end < 0) throw ShellParseException("unexpected EOF while looking for matching '`'")
                    segments += Segment.CmdSubst(src.substring(pos, end), true)
                    pos = end + 1
                }
                else -> {
                    val lit = StringBuilder()
                    while (pos < src.length && src[pos] != '"' && src[pos] != '\\' && src[pos] != '$' && src[pos] != '`') {
                        lit.append(src[pos])
                        pos++
                    }
                    if (lit.isNotEmpty()) segments += Segment.Lit(lit.toString(), true)
                }
            }
        }
    }

    /** Consumes a `$...` construct at [pos]; returns null when `$` is literal. */
    private fun lexDollar(): Segment? {
        val n = peek(1)
        return when {
            n == '(' && peek(2) == '(' -> {
                pos += 3
                val body = scanBalanced('(', ')', doubleCloser = true)
                pos += 2
                Segment.Arith(body, false)
            }
            n == '(' -> {
                pos += 2
                val body = scanBalanced('(', ')')
                pos++
                Segment.CmdSubst(body, false)
            }
            n == '{' -> {
                pos += 2
                val body = scanBalanced('{', '}')
                pos++
                parseBraceParam(body)
            }
            n == '?' || n == '!' || n == '$' || n == '#' || n == '@' || n == '*' -> {
                pos += 2
                Segment.Param(n.toString())
            }
            isNameStart(n) -> {
                var p = pos + 1
                while (p < src.length && isNameChar(src[p])) p++
                val name = src.substring(pos + 1, p)
                pos = p
                Segment.Param(name)
            }
            n in '0'..'9' -> {
                val d = n - '0'
                pos += 2
                Segment.Param(d.toString())
            }
            else -> null
        }
    }

    private fun isNameOrSpecial(c: Char) = isNameChar(c) || c == '@' || c == '*' || c == '?' || c == '$' || c == '!'

    /**
     * `${...}`: the name comes first and the operator follows it, so `${#V}` is the length of `V`
     * and `${V#pat}` is a prefix removal. Getting that order wrong silently swaps two operators.
     */
    private fun parseBraceParam(body: String): Segment {
        if (body.isEmpty()) throw ShellParseException("bad substitution: \${}")
        if (body == "#") return Segment.Param("@", ParamOp.LENGTH)
        if (body.length > 1 && body[0] == '#' && isNameOrSpecial(body[1])) {
            return Segment.Param(body.substring(1), ParamOp.LENGTH)
        }
        var i = 0
        while (i < body.length && isNameOrSpecial(body[i])) i++
        if (i == 0) throw ShellParseException("bad substitution: \${$body}")
        val name = body.substring(0, i)
        val rest = body.substring(i)
        if (rest.isEmpty()) return Segment.Param(name)
        if (rest.startsWith("##")) return Segment.Param(name, ParamOp.REMOVE_PREFIX_LONG, rest.substring(2))
        if (rest.startsWith("#")) return Segment.Param(name, ParamOp.REMOVE_PREFIX, rest.substring(1))
        if (rest.startsWith("%%")) return Segment.Param(name, ParamOp.REMOVE_SUFFIX_LONG, rest.substring(2))
        if (rest.startsWith("%")) return Segment.Param(name, ParamOp.REMOVE_SUFFIX, rest.substring(1))
        if (rest.startsWith(":")) {
            val op = when (rest.getOrNull(1)) {
                '-' -> ParamOp.DEFAULT
                '=' -> ParamOp.ASSIGN
                '?' -> ParamOp.ERROR
                else -> ParamOp.DEFAULT
            }
            val arg = if (op == ParamOp.DEFAULT && rest.getOrNull(1) != '-') rest.substring(1) else rest.substring(2)
            return Segment.Param(name, op, arg)
        }
        throw ShellParseException("bad substitution: \${$body}")
    }

    /**
     * Scans from [pos] for the closing bracket of the construct the caller has already opened.
     * Quotes and escapes are honoured, so a `)` inside a quoted string does not close the scan.
     */
    private fun scanBalanced(open: Char, close: Char, doubleCloser: Boolean = false): String {
        val start = pos
        var depth = 1
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\\' -> pos += 2
                c == '\'' -> {
                    val end = src.indexOf('\'', pos + 1)
                    if (end < 0) throw ShellParseException("unexpected EOF while looking for matching single quote")
                    pos = end + 1
                }
                c == '"' -> {
                    pos++
                    while (pos < src.length && src[pos] != '"') {
                        if (src[pos] == '\\') pos++
                        pos++
                    }
                    pos++
                }
                c == open -> {
                    depth++
                    pos++
                }
                c == close -> {
                    depth--
                    if (depth == 0) {
                        if (doubleCloser && (pos + 1 >= src.length || src[pos + 1] != close)) {
                            throw ShellParseException("syntax error: expected '$close$close'")
                        }
                        return src.substring(start, pos)
                    }
                    pos++
                }
                else -> pos++
            }
        }
        throw ShellParseException("unexpected EOF while looking for matching '$close'")
    }
}
