package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "echo",
    synopsis = "[-n] [-e] string ...",
    group = "text",
    notes = "-e expands \\n \\t \\r \\\\ \\0nnn, identically into a pipe and onto a terminal",
)
object Echo : FileCommand() {

    override val flagSpec = "ne"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val body = if (operands.isEmpty()) "" else operands.joinToString(" ")
        ctx.out(if (flags.indexOf('e') >= 0) expand(body) else body)
        if (flags.indexOf('n') < 0) ctx.out("\n")
        return ExecContext.EXIT_OK
    }

    private fun expand(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '0' -> {
                    var value = 0
                    var j = i + 2
                    var digits = 0
                    while (j < s.length && digits < 3 && s[j] in '0'..'7') {
                        value = value * 8 + (s[j] - '0')
                        j++
                        digits++
                    }
                    sb.append(value.toChar())
                    i = j
                }
                // An unknown escape stays literal, so `echo -e 'C:\path'` is not mangled.
                else -> { sb.append(c).append(n); i += 2 }
            }
        }
        return sb.toString()
    }
}
