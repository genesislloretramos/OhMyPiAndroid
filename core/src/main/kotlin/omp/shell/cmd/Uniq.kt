package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.IOException
import java.util.Locale

@CommandSpec(
    name = "uniq",
    synopsis = "[-cdiu] [file]",
    group = "text",
    notes = "compares adjacent lines only, so sort first; -d and -u are mutually exclusive",
)
object Uniq : FileCommand() {

    override val flagSpec = "cdiu"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if ('d' in flags && 'u' in flags) {
            ctx.errLine("uniq: cannot use -d and -u together")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        if (operands.size > 1) {
            ctx.errLine("uniq: extra operand '${operands[1]}'")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val counts = 'c' in flags
        val onlyDup = 'd' in flags
        val onlyUnique = 'u' in flags
        val ignoreCase = 'i' in flags

        var current: String? = null
        var currentKey: String? = null
        var seen = 0

        fun flush() {
            val line = current ?: return
            if (onlyDup && seen < 2) return
            if (onlyUnique && seen != 1) return
            if (counts) ctx.out(seen.toString().padStart(7) + "\t")
            ctx.outLine(line)
        }

        val status = eachLine(ctx, operands) { line ->
            val key = if (ignoreCase) line.lowercase(Locale.ROOT) else line
            if (currentKey == null || key != currentKey) {
                flush()
                current = line
                currentKey = key
                seen = 1
            } else {
                seen++
            }
        }
        if (status == ExecContext.EXIT_INTERRUPTED) return status
        flush()
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
                Errno.report(ctx, "uniq", op, e)
                status = ExecContext.EXIT_GENERAL_ERROR
            } finally {
                if (input !== ctx.stdin) input.close()
            }
        }
        return status
    }
}
