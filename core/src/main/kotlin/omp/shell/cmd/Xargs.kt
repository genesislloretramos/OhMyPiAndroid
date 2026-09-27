package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@CommandSpec(
    name = "xargs",
    synopsis = "[-0r] [-n N] [-I STR] command [initial-argument ...]",
    group = "text",
    notes = "no shell runs here: the words are appended to the command verbatim, and the exit " +
        "status is 123 if any run failed",
)
object Xargs : FileCommand() {

    override val flagSpec = "r0"
    override val valueSpec = "n:I:"

    /** Well inside every platform argument limit, and the reason a long list becomes several runs. */
    private const val MAX_ARGS_PER_RUN = 512

    private const val NUL = '\u0000'

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val nulSeparated = '0' in flags
        val skipEmpty = 'r' in flags
        val replaceWith = options["I"]?.also {
            if (it.isEmpty()) {
                ctx.errLine("xargs: the -I replace string must not be empty")
                printUsage(ctx.stderr, usageLine(ctx))
                return ExecContext.EXIT_USAGE
            }
        }
        val perRun = options["n"]?.let {
            val n = it.toIntOrNull()
            if (n == null || n <= 0) {
                ctx.errLine("xargs: invalid number of arguments: '$it'")
                printUsage(ctx.stderr, usageLine(ctx))
                return ExecContext.EXIT_USAGE
            }
            n
        }
        val command = if (operands.isEmpty()) listOf("echo") else operands
        val target = ctx.session.table.lookup(command[0])

        val text = try {
            readAll(ctx) ?: return ExecContext.EXIT_INTERRUPTED
        } catch (e: IOException) {
            ctx.errLine("xargs: ${e.message}")
            return ExecContext.EXIT_GENERAL_ERROR
        }

        val items: List<String> = if (replaceWith != null) {
            splitLines(text)
        } else if (nulSeparated) {
            text.split(NUL).filter { it.isNotEmpty() }
        } else {
            tokenize(text)
        }
        if (items.isEmpty() && skipEmpty) return ExecContext.EXIT_OK

        var sawFailure = false
        var sawHardFailure = false
        var notFound = false

        fun invoke(argv: List<String>) {
            if (target == null) {
                ctx.errLine("xargs: ${command[0]}: No such file or directory")
                notFound = true
                return
            }
            val child = ExecContext(
                argv = argv,
                stdin = ByteArrayInputStream(ByteArray(0)),
                stdout = ctx.stdout,
                stderr = ctx.stderr,
                env = ctx.env,
                services = ctx.services,
                session = ctx.session,
                isTty = ctx.isTty,
                cancelled = ctx.cancelled,
            )
            val status = try {
                target.run(child)
            } catch (e: Exception) {
                ctx.errLine("xargs: ${command[0]}: ${e.message ?: e::class.java.simpleName}")
                ExecContext.EXIT_GENERAL_ERROR
            }
            if (status != 0) {
                sawFailure = true
                if (status == 255) sawHardFailure = true
            }
        }

        if (replaceWith != null) {
            for (line in items) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                invoke(command.map { it.replace(replaceWith, line) })
            }
        } else if (items.isEmpty()) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            invoke(command)
        } else {
            val limit = perRun ?: MAX_ARGS_PER_RUN
            var i = 0
            while (i < items.size) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val end = minOf(i + limit, items.size)
                invoke(command + items.subList(i, end))
                i = end
            }
        }

        if (notFound) return ExecContext.EXIT_NOT_FOUND
        if (sawHardFailure) return 124
        if (sawFailure) return 123
        return ExecContext.EXIT_OK
    }

    /** Returns null when the command was cancelled, which is not an empty input. */
    private fun readAll(ctx: ExecContext): String? {
        val out = ByteArrayOutputStream(8 * 1024)
        val buf = ByteArray(32 * 1024)
        while (true) {
            if (ctx.cancelled.get()) return null
            val n = ctx.stdin.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** One input line per run with -I; a trailing newline does not make an extra line. */
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var start = 0
        for (i in 0 until text.length) {
            if (text[i] == '\n') {
                lines += text.substring(start, i)
                start = i + 1
            }
        }
        if (start < text.length) lines += text.substring(start)
        // A blank line carries nothing to substitute, which is what -r's rule also says.
        return lines.filter { it.isNotBlank() }
    }

    /** Blanks separate, but quotes and a backslash protect a blank, as xargs itself does. */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var started = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {
                    if (started) {
                        out += sb.toString()
                        sb.setLength(0)
                        started = false
                    }
                    i++
                }
                c == '\'' -> {
                    started = true
                    i++
                    while (i < text.length && text[i] != '\'') sb.append(text[i++])
                    if (i < text.length) i++
                }
                c == '"' -> {
                    started = true
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(text[i++])
                    }
                    if (i < text.length) i++
                }
                c == '\\' -> {
                    started = true
                    i++
                    if (i < text.length) sb.append(text[i++])
                }
                else -> {
                    started = true
                    sb.append(c)
                    i++
                }
            }
        }
        if (started) out += sb.toString()
        return out
    }
}
