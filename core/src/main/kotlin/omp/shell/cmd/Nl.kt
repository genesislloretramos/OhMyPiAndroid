package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.IOException

@CommandSpec(
    name = "nl",
    synopsis = "[-ba] [file]",
    group = "text",
    notes = "numbers are six columns wide followed by a tab; without -a an empty line is not numbered",
)
object Nl : FileCommand() {

    override val flagSpec = "ba"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val all = 'a' in flags
        var number = 0
        val sb = StringBuilder()
        val status = eachLine(ctx, operands) { line ->
            if (all || line.isNotEmpty()) {
                number++
                sb.append(number.toString().padStart(6)).append('\t')
            }
            sb.append(line).append('\n')
            if (sb.length > 32 * 1024) {
                ctx.out(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) ctx.out(sb.toString())
        return status
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
                Errno.report(ctx, "nl", op, e)
                status = ExecContext.EXIT_GENERAL_ERROR
            } finally {
                if (input !== ctx.stdin) input.close()
            }
        }
        return status
    }
}
