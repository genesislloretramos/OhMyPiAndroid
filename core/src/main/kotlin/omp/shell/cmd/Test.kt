package omp.shell.cmd

import omp.shell.Session
import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.fs.PathException
import omp.shell.fs.PathResolver
import java.io.File

@CommandSpec(
    name = "test",
    synopsis = "EXPR",
    group = "builtins",
    notes = "also spelled `[`; unary -e -f -d -r -w -x -s -z -n, string = and !=, numeric -eq -ne -lt -le -gt -ge, combined with ! -a -o",
)
object Test : Command {
    override fun run(ctx: ExecContext): Int {
        val args = ArrayList(ctx.args)
        if (ctx.name == "[") {
            if (args.isEmpty() || args[args.size - 1] != "]") {
                ctx.errLine("[: missing `]'")
                return ExecContext.EXIT_USAGE
            }
            args.removeAt(args.size - 1)
        }
        if (args.isEmpty()) return ExecContext.EXIT_OK
        return try {
            if (Expr(ctx.session, args).parse()) ExecContext.EXIT_OK else ExecContext.EXIT_GENERAL_ERROR
        } catch (e: TestUsage) {
            ctx.errLine("${ctx.name}: ${e.message}")
            ExecContext.EXIT_USAGE
        }
    }
}

private class TestUsage(message: String) : RuntimeException(message)

/**
 * `!` binds tightest, then `-a`, then `-o`, which is the precedence POSIX gives them. Both sides of
 * a connective are always parsed, so `false -a no-such-thing -o true` is a usage error rather than
 * a short circuit that quietly swallows the bad operand.
 */
private class Expr(private val session: Session, private val args: List<String>) {

    private var pos = 0

    fun parse(): Boolean = orExpr()

    private fun orExpr(): Boolean {
        var value = andExpr()
        while (peek() == "-o") {
            pos++
            val rhs = andExpr()
            value = value || rhs
        }
        return value
    }

    private fun andExpr(): Boolean {
        var value = unaryExpr()
        while (peek() == "-a") {
            pos++
            val rhs = unaryExpr()
            value = value && rhs
        }
        return value
    }

    private fun unaryExpr(): Boolean {
        if (peek() == "!") {
            pos++
            return !unaryExpr()
        }
        return primary()
    }

    private fun primary(): Boolean {
        val token = take() ?: throw TestUsage("argument expected")
        if (token == "-z" || token == "-n") {
            val operand = need(token)
            end()
            return if (token == "-z") operand.isEmpty() else operand.isNotEmpty()
        }
        if (token in FILE_TESTS) {
            val operand = need(token)
            end()
            return fileTest(token, operand)
        }
        if (token in NUMERIC) {
            val right = need(token)
            end()
            val left = number(token)
            val r = number(right)
            return when (token) {
                "-eq" -> left == r
                "-ne" -> left != r
                "-lt" -> left < r
                "-le" -> left <= r
                "-gt" -> left > r
                else -> left >= r
            }
        }
        if (peek() == "=" || peek() == "!=") {
            val op = take()!!
            val right = need(op)
            end()
            return if (op == "=") token == right else token != right
        }
        end()
        return token.isNotEmpty()
    }

    private fun fileTest(op: String, path: String): Boolean {
        // An unresolvable path is simply not a file that answers the question, which is what POSIX
        // says about a nonexistent one; it is not a usage error.
        val resolved = try {
            PathResolver.resolve(session, path)
        } catch (e: PathException) {
            return false
        }
        val file = File(resolved)
        return when (op) {
            "-e" -> file.exists()
            "-f" -> file.isFile
            "-d" -> file.isDirectory
            "-r" -> file.canRead()
            "-w" -> file.canWrite()
            "-x" -> file.canExecute()
            else -> file.length() > 0
        }
    }

    private fun number(text: String): Long =
        text.trim().toLongOrNull() ?: throw TestUsage("$text: integer expression expected")

    private fun peek(): String? = args.getOrNull(pos)

    private fun take(): String? = args.getOrNull(pos)?.also { pos++ }

    private fun need(op: String): String = take() ?: throw TestUsage("$op: argument expected")

    /** A connective may follow a complete test; anything else left over is a usage error. */
    private fun end() {
        val next = peek() ?: return
        if (next == "-a" || next == "-o") return
        throw TestUsage("too many arguments")
    }

    private companion object {
        val FILE_TESTS = setOf("-e", "-f", "-d", "-r", "-w", "-x", "-s")
        val NUMERIC = setOf("-eq", "-ne", "-lt", "-le", "-gt", "-ge")
    }
}
