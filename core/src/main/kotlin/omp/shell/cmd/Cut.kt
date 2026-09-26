package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.IOException

@CommandSpec(
    name = "cut",
    synopsis = "(-f LIST | -c LIST) [-d SEP] [file ...]",
    group = "text",
    notes = "LIST is N, N-M or N-, comma separated; the default separator is a single tab",
)
object Cut : FileCommand() {

    override val valueSpec = "f:c:d:"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val fieldSpec = options["f"]
        val charSpec = options["c"]
        if ((fieldSpec == null) == (charSpec == null)) {
            ctx.errLine("cut: exactly one of -c or -f is required")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val ranges = parseList(fieldSpec ?: charSpec!!)
        if (ranges == null) {
            ctx.errLine("cut: invalid list '${fieldSpec ?: charSpec}'")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val rawSep = options["d"]
        val sep = if (rawSep == null) {
            '\t'
        } else if (rawSep.length == 1) {
            rawSep[0]
        } else {
            ctx.errLine("cut: the delimiter must be a single character")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val byCharacter = charSpec != null

        return eachLine(ctx, operands) { line ->
            if (byCharacter) {
                val chars = line.toCharArray()
                val sb = StringBuilder()
                var first = true
                for (i in chars.indices) {
                    if (!wanted(ranges, i + 1)) continue
                    if (!first) sb.append(sep)
                    sb.append(chars[i])
                    first = false
                }
                ctx.outLine(sb.toString())
            } else {
                val fields = line.split(sep)
                val sb = StringBuilder()
                var first = true
                for (i in fields.indices) {
                    if (!wanted(ranges, i + 1)) continue
                    if (!first) sb.append(sep)
                    sb.append(fields[i])
                    first = false
                }
                ctx.outLine(sb.toString())
            }
        }
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
                Errno.report(ctx, "cut", op, e)
                status = ExecContext.EXIT_GENERAL_ERROR
            } finally {
                if (input !== ctx.stdin) input.close()
            }
        }
        return status
    }

    /** Merged, ascending 1-based ranges, so membership is a scan rather than an expansion. */
    private fun parseList(spec: String): List<IntRange>? {
        val out = ArrayList<IntRange>()
        for (part in spec.split(',')) {
            if (part.isEmpty()) return null
            val dash = part.indexOf('-', startIndex = 1)
            val range: IntRange = if (dash < 0) {
                val n = part.toIntOrNull() ?: return null
                if (n < 1) return null
                n..n
            } else {
                val from = part.substring(0, dash).toIntOrNull() ?: return null
                val tail = part.substring(dash + 1)
                val to = if (tail.isEmpty()) Int.MAX_VALUE else tail.toIntOrNull() ?: return null
                if (from < 1 || to < from) return null
                from..to
            }
            out += range
        }
        out.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in out) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                if (r.last > last.last) merged[merged.size - 1] = last.first..r.last
            } else {
                merged += r
            }
        }
        return merged
    }

    private fun wanted(ranges: List<IntRange>, index: Int): Boolean {
        for (r in ranges) {
            if (index < r.first) return false
            if (index <= r.last) return true
        }
        return false
    }
}
