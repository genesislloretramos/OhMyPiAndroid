package omp.shell.parser

import omp.shell.Session
import omp.shell.fs.PathResolver

/** A run of characters that is either protected from splitting/globbing or not. */
class Part(val text: String, val quoted: Boolean) {
    override fun toString(): String = text
}

/**
 * Word expansion, in the fixed order the shell language defines:
 * tilde, parameter, command substitution, arithmetic, word splitting, brace expansion, pathname
 * expansion, quote removal. Quote removal needs no pass because the lexer already produced final
 * text, so a `Part` carries the quoting that survived instead.
 */
class Expander(
    private val session: Session,
    /** Runs a program list and returns its stdout with trailing newlines stripped. */
    private val runCaptured: (String) -> String,
) {
    /** Positional parameters, supplied by `set ARG ...`; empty when `set` has not been used. */
    var positionalProvider: () -> List<String> = { emptyList() }

    private val positional: List<String> get() = positionalProvider()

    var selfPid: Long = 0

    fun expandWords(words: List<Word>): List<String> {
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (w in words) out += expandWord(w)
        return out
    }

    /** The value of a `NAME=word` assignment; splitting and globbing do not apply. */
    fun expandAssignment(word: Word): String = joinParts(expandParts(applyTilde(word)))

    fun expandWord(word: Word): List<String> {
        val parts = expandParts(applyTilde(word))
        val fields = splitFields(parts)
        val braced = ArrayList<Flat>()
        for (f in fields) braced += braceExpand(flatten(f))
        val out = ArrayList<String>()
        for (f in braced) out += glob(f)
        return out
    }

    // 1. tilde -----------------------------------------------------------------------

    private fun applyTilde(word: Word): Word {
        val first = word.segments.firstOrNull() as? Segment.Lit ?: return word
        if (first.quoted) return word
        if (!first.text.startsWith("~")) return word
        val slash = first.text.indexOf('/')
        val head = if (slash < 0) first.text else first.text.substring(0, slash)
        val rest = if (slash < 0) "" else first.text.substring(slash)
        val replacement = when (head) {
            "~" -> session.services.homeDir()
            "~+" -> session.oldPwd
            else -> return word
        }
        val segments = ArrayList<Segment>()
        segments += Segment.Lit(replacement + rest, false)
        for (i in 1 until word.segments.size) segments += word.segments[i]
        return Word(word.raw, segments)
    }

    // 2..4. parameter, command substitution, arithmetic ----------------------------------

    private fun expandParts(word: Word): List<Part> {
        val out = ArrayList<Part>()
        for (s in word.segments) {
            when (s) {
                is Segment.Lit -> if (s.text.isNotEmpty()) out += Part(s.text, s.quoted)
                is Segment.Param -> out += Part(evalParam(s), s.quoted)
                is Segment.CmdSubst -> out += Part(runCaptured(s.body), s.quoted)
                is Segment.Arith -> {
                    val v = try {
                        Arithmetic.eval(s.body) { name -> session.env[name] }
                    } catch (e: Arithmetic.EvalException) {
                        throw ShellParseException(e.message ?: "arithmetic error")
                    }
                    out += Part(v.toString(), s.quoted)
                }
            }
        }
        return out
    }

    private fun evalParam(p: Segment.Param): String {
        val name = p.name
        val value = rawValue(name)
        val present = value != null
        val text = value ?: ""
        when (p.op) {
            ParamOp.VALUE -> {
                if (!present && session.nounset) throw ShellParseException("$name: parameter not set")
                return text
            }
            ParamOp.LENGTH -> return if (name == "@" || name == "*") positional.size.toString() else text.length.toString()
            ParamOp.DEFAULT -> {
                if (present && text.isNotEmpty()) return text
                return expandArg(p.arg)
            }
            ParamOp.ASSIGN -> {
                if (present && text.isNotEmpty()) return text
                val d = expandArg(p.arg)
                session.env[name] = d
                return d
            }
            ParamOp.ERROR -> {
                if (present && text.isNotEmpty()) return text
                val msg = p.arg?.let { expandArg(it) } ?: "parameter null or not set"
                throw ShellParseException("$name: $msg")
            }
            ParamOp.REMOVE_PREFIX -> return stripPrefix(text, expandArg(p.arg), false)
            ParamOp.REMOVE_PREFIX_LONG -> return stripPrefix(text, expandArg(p.arg), true)
            ParamOp.REMOVE_SUFFIX -> return stripSuffix(text, expandArg(p.arg), false)
            ParamOp.REMOVE_SUFFIX_LONG -> return stripSuffix(text, expandArg(p.arg), true)
        }
    }

    private fun rawValue(name: String): String? = when {
        name == "?" -> session.lastStatus.toString()
        name == "$" -> selfPid.toString()
        name == "!" -> if (session.lastBackgroundPid == 0) "" else session.lastBackgroundPid.toString()
        name == "@" || name == "*" -> positional.joinToString(" ")
        name == "#" -> positional.size.toString()
        name.length == 1 && name[0] in '0'..'9' -> positional.getOrNull(name[0] - '0') ?: ""
        else -> session.env[name]
    }

    private fun expandArg(arg: String?): String {
        if (arg == null) return ""
        val word = Lexer(arg).let { l ->
            val tokens = l.tokenize()
            if (tokens.isEmpty() || tokens[0].type != TokenType.WORD) return arg
            tokens[0].word!!
        }
        return joinParts(expandParts(word))
    }

    private fun stripPrefix(value: String, pattern: String, longest: Boolean): String {
        val lengths = if (longest) value.length downTo 0 else 0..value.length
        for (i in lengths) {
            if (Glob.matchWhole(pattern, value.substring(0, i))) return value.substring(i)
        }
        return value
    }

    private fun stripSuffix(value: String, pattern: String, longest: Boolean): String {
        val starts = if (longest) 0..value.length else value.length downTo 0
        for (i in starts) {
            if (Glob.matchWhole(pattern, value.substring(i))) return value.substring(0, i)
        }
        return value
    }

    // 5. word splitting -----------------------------------------------------------------

    private fun splitFields(parts: List<Part>): List<List<Part>> {
        val out = ArrayList<List<Part>>()
        var current = ArrayList<Part>()
        var started = false
        for (p in parts) {
            if (p.quoted) {
                if (p.text.isNotEmpty()) {
                    current += Part(p.text, true)
                    started = true
                }
                continue
            }
            var i = 0
            while (i < p.text.length) {
                if (isWhitespace(p.text[i])) {
                    if (started) {
                        out += current
                        current = ArrayList()
                        started = false
                    }
                    i++
                    continue
                }
                val j = runEnd(p.text, i)
                current += Part(p.text.substring(i, j), false)
                started = true
                i = j
            }
        }
        if (started) out += current
        return out
    }

    private fun runEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && !isWhitespace(s[i])) i++
        return i
    }

    // 6. brace expansion ---------------------------------------------------------------

    private fun flatten(parts: List<Part>): Flat {
        val sb = StringBuilder()
        val q = ArrayList<Boolean>()
        for (p in parts) {
            sb.append(p.text)
            repeat(p.text.length) { q += p.quoted }
        }
        return Flat(sb.toString(), q.toBooleanArray())
    }

    private class Flat(val text: String, val quoted: BooleanArray) {
        fun slice(from: Int, toExclusive: Int): Flat =
            Flat(text.substring(from, toExclusive), quoted.copyOfRange(from, toExclusive))
    }

    private fun join(flat: Flat): List<Part> = partsOf(flat.text, flat.quoted)

    private fun joinParts(parts: List<Part>): String = parts.joinToString("") { it.text }

    private fun partsOf(text: String, quoted: BooleanArray): List<Part> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Part>()
        var i = 0
        while (i < text.length) {
            val q = quoted[i]
            var j = i
            while (j < text.length && quoted[j] == q) j++
            out += Part(text.substring(i, j), q)
            i = j
        }
        return out
    }

    private fun braceExpand(flat: Flat): List<Flat> {
        val open = findBraceOpen(flat) ?: return listOf(flat)
        val close = findBraceClose(flat, open) ?: return listOf(flat)
        val body = flat.text.substring(open + 1, close)
        if (body.isEmpty() || body.contains('{')) return listOf(flat)
        val alts = if (body.contains("..")) sequence(body) else body.split(',')
        if (alts.isEmpty()) return listOf(flat)
        val out = ArrayList<Flat>()
        for (alt in alts) {
            val merged = Flat(
                flat.text.substring(0, open) + alt + flat.text.substring(close + 1),
                mergeFlags(flat.quoted, open, close, alt.length),
            )
            out += braceExpand(merged)
        }
        return out
    }

    private fun mergeFlags(q: BooleanArray, open: Int, close: Int, altLen: Int): BooleanArray {
        val out = BooleanArray(q.size - (close - open) + altLen)
        System.arraycopy(q, 0, out, 0, open)
        for (i in 0 until altLen) out[open + i] = false
        System.arraycopy(q, close + 1, out, open + altLen, q.size - close - 1)
        return out
    }

    private fun findBraceOpen(flat: Flat): Int? {
        var i = 0
        while (i < flat.text.length) {
            if (flat.text[i] == '\\') {
                i += 2
                continue
            }
            if (flat.text[i] == '{' && !flat.quoted[i]) return i
            i++
        }
        return null
    }

    private fun findBraceClose(flat: Flat, open: Int): Int? {
        var i = open + 1
        while (i < flat.text.length) {
            if (flat.text[i] == '\\') {
                i += 2
                continue
            }
            if (flat.text[i] == '{' && !flat.quoted[i]) return null
            if (flat.text[i] == '}' && !flat.quoted[i]) return i
            i++
        }
        return null
    }

    private fun sequence(body: String): List<String> {
        val parts = body.split("..")
        if (parts.size !in 2..3) return listOf(body)
        val a = parts[0].toIntOrNull() ?: return listOf(body)
        val b = parts[1].toIntOrNull() ?: return listOf(body)
        val step = if (parts.size == 3) parts[2].toIntOrNull() ?: return listOf(body) else if (a <= b) 1 else -1
        if (step == 0) return listOf(body)
        val out = ArrayList<String>()
        if (step > 0) for (v in a..b step step) out += v.toString() else for (v in a downTo b step -step) out += v.toString()
        val width = parts[0].length
        if (width > 1 && parts[0].startsWith("0") && parts[1].length == width) {
            for (i in out.indices) out[i] = out[i].padStart(width, '0')
        }
        return out
    }

    // 7. pathname expansion -------------------------------------------------------------

    private fun glob(flat: Flat): List<String> {
        val text = flat.text
        if (text.isEmpty()) return emptyList()
        if (flat.quoted.any { it }) return listOf(text)
        if (!Glob.hasMagic(text)) return listOf(text)
        val matches = Glob.expand(text, session.cwd)
        if (matches.isNullOrEmpty()) return listOf(text)
        val cwd = session.cwd.trimEnd('/') + "/"
        if (text.startsWith("/")) return matches
        return matches.map { if (it.startsWith(cwd)) it.substring(cwd.length) else it }
    }

    /** `PATH` search for a bare command name; kept here so `command -v` and `which` agree. */
    fun which(name: String): String? {
        if (name.contains('/')) return name
        for (dir in (session.env["PATH"] ?: "").split(':')) {
            if (dir.isEmpty()) continue
            val candidate = PathResolver.normalize(dir.trimEnd('/') + "/" + name)
            if (java.io.File(candidate).canExecute()) return candidate
        }
        return null
    }
}
