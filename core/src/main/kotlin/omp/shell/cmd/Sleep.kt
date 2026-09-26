package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

@CommandSpec(
    name = "sleep",
    synopsis = "N[s|m|h|d] ...",
    group = "builtins",
    notes = "several durations add up; Ctrl-C ends it early with status 130",
)
object Sleep : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isEmpty()) {
            ctx.errLine("sleep: missing operand")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        var totalMillis = 0L
        for (operand in operands) {
            val millis = parse(operand)
            if (millis == null) {
                ctx.errLine("sleep: invalid time interval '$operand'")
                printUsage(ctx.stderr, usageLine(ctx))
                return ExecContext.EXIT_USAGE
            }
            totalMillis += millis
        }
        val end = ctx.services.monotonicMillis() + totalMillis
        while (true) {
            val left = end - ctx.services.monotonicMillis()
            if (left <= 0) return ExecContext.EXIT_OK
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            try {
                Thread.sleep(minOf(left, 50L))
            } catch (e: InterruptedException) {
                // The flag is the shell's interrupt path, but a thread interrupt is the same
                // request arriving the other way round; both mean stop now.
                Thread.currentThread().interrupt()
                return ExecContext.EXIT_INTERRUPTED
            }
        }
    }

    private fun parse(text: String): Long? {
        val digits = text.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val value = digits.toLongOrNull() ?: return null
        val seconds = when (text.substring(digits.length)) {
            "", "s" -> value
            "m" -> value * 60
            "h" -> value * 3600
            "d" -> value * 86400
            else -> return null
        }
        // An absurd interval overflows into a negative one, which would look like no wait at all.
        if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return null
        return seconds * 1000
    }
}
