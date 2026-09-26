package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.IOException
import java.io.InputStream

@CommandSpec(
    name = "head",
    synopsis = "[-n N] file ...",
    group = "files",
    notes = "with no operand it reads stdin; -n -N prints all but the last N lines",
)
object Head : FileCommand() {

    override val valueSpec = "n:"

    /** `head -3` is the BSD/GNU/toybox shorthand for `head -n 3`. */
    override fun normalizeArgv(argv: List<String>): List<String> {
        val out = ArrayList<String>(argv.size)
        for (a in argv) {
            val rest = if (a.startsWith("-") && a != "--") a.drop(1) else ""
            if (rest.isNotEmpty() && rest.length <= 9 && rest.all { it in '0'..'9' }) {
                out += "-n"
                out += rest
            } else {
                out += a
            }
        }
        return out
    }

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        var count = 10L
        var allButLast = 0L
        val raw = options["n"]
        if (raw != null) {
            val v = raw.trim().toLongOrNull() ?: return ctx.fail("head: invalid number of lines: '$raw'")
            if (v < 0) allButLast = -v else count = v
        }
        if (operands.isEmpty()) return pump(ctx, ctx.stdin, count, allButLast, null)
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val source = Cmds.openInput(ctx, op)
            if (source == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val rc = pump(ctx, source, count, allButLast, op)
            if (rc != ExecContext.EXIT_OK) status = rc
            if (source !== ctx.stdin) closeQuietly(source)
        }
        return status
    }


    private fun attachedCount(ctx: ExecContext): String? {
        for (a in ctx.argv.drop(1)) {
            if (a == "--" || !a.startsWith("-") || a.length < 2) continue
            val digits = a.substring(1)
            if (digits.length < 8 && digits.all { it in '0'..'9' }) return digits
        }
        return null
    }

    private fun pump(
        ctx: ExecContext,
        source: InputStream,
        count: Long,
        allButLast: Long,
        op: String?,
    ): Int {
        var remaining = count
        val reader = Cmds.reader(source)
        val ring = ArrayDeque<String>()
        var line = try {
            reader.readLine()
        } catch (e: IOException) {
            return report(ctx, op, e)
        }
        while (line != null) {
            if (allButLast <= 0 && remaining <= 0) return ExecContext.EXIT_OK
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            if (allButLast > 0) {
                if (ring.size == allButLast.toInt()) ctx.outLine(ring.removeFirst())
                ring.addLast(line)
            } else if (remaining > 0) {
                ctx.outLine(line)
                remaining--
            }
            line = try {
                reader.readLine()
            } catch (e: IOException) {
                return report(ctx, op, e)
            }
        }
        return ExecContext.EXIT_OK
    }

    private fun report(ctx: ExecContext, op: String?, e: IOException): Int =
        if (op == null) ctx.fail("head: ${Errno.messageFor(e)}") else Errno.report(ctx, "head", op, e)

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
        }
    }
}
