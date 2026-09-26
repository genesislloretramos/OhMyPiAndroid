package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.IOException

@CommandSpec(
    name = "rev",
    synopsis = "[file]",
    group = "text",
    notes = "reverses the characters of each line",
)
object Rev : Command {

    override fun run(ctx: ExecContext): Int {
        val operands = ctx.args
        if (operands.size > 1) {
            ctx.errLine("rev: extra operand '${operands[1]}'")
            return ExecContext.EXIT_USAGE
        }
        return eachLine(ctx, operands) { line -> ctx.outLine(line.reversed()) }
    }

    /** Cmds.forEachLine cannot stop mid-file, and on a phone Ctrl-C has to be immediate. */
    private fun eachLine(ctx: ExecContext, operands: List<String>, action: (String) -> Unit): Int {
        if (operands.isEmpty()) {
            val reader = Cmds.reader(ctx.stdin)
            while (true) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val line = reader.readLine() ?: break
                action(line)
            }
            return ExecContext.EXIT_OK
        }
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val input = Cmds.openInput(ctx, op) ?: run { status = ExecContext.EXIT_GENERAL_ERROR; continue }
            try {
                val reader = Cmds.reader(input)
                while (true) {
                    if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                    val line = reader.readLine() ?: break
                    action(line)
                }
            } catch (e: IOException) {
                Errno.report(ctx, "rev", op, e)
                status = ExecContext.EXIT_GENERAL_ERROR
            } finally {
                if (input !== ctx.stdin) input.close()
            }
        }
        return status
    }
}
