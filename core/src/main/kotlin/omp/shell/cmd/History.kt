package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "history",
    synopsis = "[-c] [N]",
    group = "builtins",
    notes = "oldest first, each entry keeping its own number so a truncated listing still says where it is",
)
object History : FileCommand() {
    override val flagSpec = "c"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val history = ctx.session.history
        if (flags.contains('c')) {
            history.clear()
            return ExecContext.EXIT_OK
        }
        if (operands.size > 1) {
            ctx.errLine("history: too many arguments")
            return ExecContext.EXIT_USAGE
        }
        val entries = history.all()
        if (operands.isEmpty()) {
            for (i in entries.indices) print(ctx, i + 1, entries[i])
            return ExecContext.EXIT_OK
        }
        val count = operands[0].toIntOrNull()
        if (count == null || count < 0) {
            ctx.errLine("history: ${operands[0]}: numeric argument required")
            return ExecContext.EXIT_USAGE
        }
        for (i in (entries.size - count).coerceAtLeast(0) until entries.size) {
            print(ctx, i + 1, entries[i])
        }
        return ExecContext.EXIT_OK
    }

    /** `   1  <line>`: three spaces, the number, two spaces, the command. */
    private fun print(ctx: ExecContext, number: Int, line: String) {
        ctx.outLine(number.toString().padStart(4) + "  " + line)
    }
}
