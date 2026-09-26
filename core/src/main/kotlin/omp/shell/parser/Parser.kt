package omp.shell.parser

/**
 * Recursive-descent front end. Every quoting, redirect and operator edge case lands here; a wrong
 * token boundary here is a bug in every command downstream.
 */
object Parser {

    fun parse(input: String): Program {
        val tokens = Lexer(input).tokenize()
        val p = Cursor(tokens)
        val statements = ArrayList<AndOrNode>()
        p.skipSeparators()
        while (!p.atEnd) {
            statements += parseAndOr(p)
            p.skipSeparators()
        }
        return Program(statements)
    }

    private fun parseAndOr(p: Cursor): AndOrNode {
        val first = parsePipeline(p)
        val rest = ArrayList<Pair<String, PipelineNode>>()
        while (!p.atEnd && (p.peek().type == TokenType.AND_IF || p.peek().type == TokenType.OR_IF)) {
            val op = p.next().type.name
            p.skipSeparators()
            if (p.atEnd) {
                throw ShellParseException("syntax error near unexpected token")
            }
            rest += op to parsePipeline(p)
        }
        return AndOrNode(first, rest)
    }

    private fun parsePipeline(p: Cursor): PipelineNode {
        val commands = ArrayList<CommandNode>()
        commands += parseCommand(p)
        while (!p.atEnd && p.peek().type == TokenType.PIPE) {
            p.next()
            p.skipNewlines()
            if (p.atEnd || p.peek().type == TokenType.PIPE) {
                throw ShellParseException("syntax error near unexpected token '|'")
            }
            commands += parseCommand(p)
        }
        var background = false
        if (!p.atEnd && p.peek().type == TokenType.BG) {
            p.next()
            background = true
        }
        return PipelineNode(commands, background)
    }

    private fun parseCommand(p: Cursor): CommandNode {
        val assignments = ArrayList<Pair<String, Word>>()
        val words = ArrayList<Word>()
        val redirects = ArrayList<Redirect>()
        while (!p.atEnd) {
            when (p.peek().type) {
                TokenType.WORD -> words += p.next().word!!
                TokenType.ASSIGN -> {
                    val t = p.next()
                    assignments += t.assignName!! to t.word!!
                }
                TokenType.REDIR -> {
                    val t = p.next()
                    redirects += Redirect(t.redirOp!!, t.word!!)
                }
                else -> break
            }
        }
        if (words.isEmpty() && assignments.isEmpty() && redirects.isEmpty()) {
            throw ShellParseException("syntax error near unexpected token")
        }
        return CommandNode(assignments, words, redirects)
    }

    private class Cursor(private val tokens: List<Token>) {
        private var i = 0

        val atEnd: Boolean get() = i >= tokens.size

        fun peek(): Token = tokens[i]

        fun next(): Token = tokens[i++]

        /** `;` and newline both end a list; a newline before a `&&` right-hand side is cosmetic. */
        fun skipSeparators() {
            while (!atEnd && (tokens[i].type == TokenType.SEMI)) i++
        }

        fun skipNewlines() {
            while (!atEnd && tokens[i].type == TokenType.SEMI && tokens[i].text == "\n") i++
        }
    }
}
