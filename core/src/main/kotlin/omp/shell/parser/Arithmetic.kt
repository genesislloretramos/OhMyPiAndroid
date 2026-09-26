package omp.shell.parser

/** `$(( expr ))`: integer-only, C-like precedence, `$name` and bare names both read variables. */
object Arithmetic {

    class EvalException(message: String) : Exception(message)

    private class Lexer2(val src: String) {
        private var pos = 0
        val tokens = ArrayList<Token2>()

        enum class Kind { NUM, IDENT, OP, END }
        data class Token2(val kind: Kind, val text: String, val value: Long = 0)

        fun run() {
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c.isWhitespace() -> pos++
                    c in '0'..'9' -> {
                        val start = pos
                        if (c == '0' && pos + 1 < src.length && (src[pos + 1] == 'x' || src[pos + 1] == 'X')) {
                            pos += 2
                            val hexStart = pos
                            while (pos < src.length && src[pos] in "0123456789abcdefABCDEF") pos++
                            if (hexStart == pos) throw EvalException("invalid hexadecimal constant")
                            tokens += Token2(Kind.NUM, src.substring(start, pos), src.substring(hexStart, pos).toLong(16))
                        } else {
                            while (pos < src.length && src[pos] in '0'..'9') pos++
                            val text = src.substring(start, pos)
                            val v = try {
                                if (text.length > 1 && text[0] == '0') text.substring(1).toLong(8) else text.toLong()
                            } catch (e: NumberFormatException) {
                                throw EvalException("invalid number: $text")
                            }
                            tokens += Token2(Kind.NUM, text, v)
                        }
                    }
                    c.isLetter() || c == '_' -> {
                        val start = pos
                        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                        tokens += Token2(Kind.IDENT, src.substring(start, pos))
                    }
                    else -> {
                        val two = if (pos + 1 < src.length) src.substring(pos, pos + 2) else ""
                        if (two in setOf("==", "!=", "<=", ">=", "&&", "||", "<<", ">>")) {
                            tokens += Token2(Kind.OP, two)
                            pos += 2
                        } else if (c in "+-*/%()<>!&|^~?:") {
                            tokens += Token2(Kind.OP, c.toString())
                            pos++
                        } else {
                            throw EvalException("syntax error in arithmetic expression: unexpected '$c'")
                        }
                    }
                }
            }
            tokens += Token2(Kind.END, "")
        }
    }

    fun eval(expr: String, lookup: (String) -> String?): Long {
        val lexer = Lexer2(expr)
        lexer.run()
        val p = Ast(lexer.tokens, lookup)
        val v = p.ternary()
        p.expectEnd()
        return v
    }

    private class Ast(val tokens: List<Lexer2.Token2>, val lookup: (String) -> String?) {
        private var i = 0

        fun expectEnd() {
            if (!at(Lexer2.Kind.END)) throw EvalException("syntax error in arithmetic expression: unexpected '${peek().text}'")
        }

        private fun at(kind: Lexer2.Kind) = tokens[i].kind == kind
        private fun peek() = tokens[i]
        private fun atOp(vararg ops: String) = at(Lexer2.Kind.OP) && ops.contains(tokens[i].text)

        private fun advance() = tokens[i++]

        fun ternary(): Long {
            val cond = logicalOr()
            if (atOp("?")) {
                advance()
                val a = ternary()
                if (!atOp(":")) throw EvalException("syntax error in arithmetic expression: expected ':'")
                advance()
                val b = ternary()
                return if (cond != 0L) a else b
            }
            return cond
        }

        fun logicalOr(): Long {
            var v = logicalAnd()
            while (atOp("||")) {
                advance()
                val r = logicalAnd()
                v = if (v != 0L || r != 0L) 1L else 0L
            }
            return v
        }

        fun logicalAnd(): Long {
            var v = bitOr()
            while (atOp("&&")) {
                advance()
                val r = bitOr()
                v = if (v != 0L && r != 0L) 1L else 0L
            }
            return v
        }

        fun bitOr(): Long {
            var v = bitXor()
            while (atOp("|")) {
                advance()
                v = v or bitXor()
            }
            return v
        }

        fun bitXor(): Long {
            var v = bitAnd()
            while (atOp("^")) {
                advance()
                v = v xor bitAnd()
            }
            return v
        }

        fun bitAnd(): Long {
            var v = equality()
            while (atOp("&")) {
                advance()
                v = v and equality()
            }
            return v
        }

        fun equality(): Long {
            var v = relational()
            while (atOp("==", "!=")) {
                val op = advance().text
                val r = relational()
                v = if ((op == "==") == (v == r)) 1L else 0L
            }
            return v
        }

        fun relational(): Long {
            var v = shift()
            while (atOp("<", ">", "<=", ">=")) {
                val op = advance().text
                val r = shift()
                v = when (op) {
                    "<" -> if (v < r) 1L else 0L
                    ">" -> if (v > r) 1L else 0L
                    "<=" -> if (v <= r) 1L else 0L
                    else -> if (v >= r) 1L else 0L
                }
            }
            return v
        }

        fun shift(): Long {
            var v = additive()
            while (atOp("<<", ">>")) {
                val op = advance().text
                val r = additive()
                v = if (op == "<<") v shl r.toInt() else v shr r.toInt()
            }
            return v
        }

        fun additive(): Long {
            var v = multiplicative()
            while (atOp("+", "-")) {
                val op = advance().text
                val r = multiplicative()
                v = if (op == "+") v + r else v - r
            }
            return v
        }

        fun multiplicative(): Long {
            var v = unary()
            while (atOp("*", "/", "%")) {
                val op = advance().text
                val r = unary()
                v = when (op) {
                    "*" -> v * r
                    "/" -> {
                        if (r == 0L) throw EvalException("division by 0")
                        v / r
                    }
                    else -> {
                        if (r == 0L) throw EvalException("division by 0")
                        v % r
                    }
                }
            }
            return v
        }

        fun unary(): Long {
            if (atOp("-")) {
                advance()
                return -unary()
            }
            if (atOp("+")) {
                advance()
                return unary()
            }
            if (atOp("!")) {
                advance()
                return if (unary() == 0L) 1L else 0L
            }
            if (atOp("~")) {
                advance()
                return unary().inv()
            }
            return primary()
        }

        private fun primary(): Long {
            val t = peek()
            if (t.kind == Lexer2.Kind.NUM) {
                advance()
                return t.value
            }
            if (t.kind == Lexer2.Kind.IDENT) {
                advance()
                val raw = lookup(t.text)
                if (raw.isNullOrEmpty()) return 0L
                return try {
                    val s = raw.trim()
                    if (s.length > 1 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s.substring(2).toLong(16) else s.toLong()
                } catch (e: NumberFormatException) {
                    throw EvalException("value of variable ${t.text} is not an integer: $raw")
                }
            }
            if (atOp("(")) {
                advance()
                val v = ternary()
                if (!atOp(")")) throw EvalException("syntax error in arithmetic expression: expected ')'")
                advance()
                return v
            }
            throw EvalException("syntax error in arithmetic expression: unexpected '${t.text}'")
        }
    }
}
