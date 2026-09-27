package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import java.io.InputStream

/**
 * `wc` inside the namespace. Byte counting is the reason it exists here rather than reusing the
 * phone's: `wc -c` is how a user finds out that `/dev/zero` is infinite and `/dev/urandom` is not,
 * and it can only answer that if the bytes came through the [omp.shell.fs.Vfs].
 */
@CommandSpec(
    name = "wc",
    synopsis = "[-l] [-w] [-c] [FILE ...]",
    group = "vm",
    notes = "counts what the Vfs handed it, so a device node can be counted too",
)
object VmWc : FileCommand() {
    override val flagSpec = "lwc"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val want = when {
            flags.isEmpty() -> setOf('l', 'w', 'c')
            else -> flags.toSet().intersect(setOf('l', 'w', 'c'))
        }
        var total = Counts()
        var status = ExecContext.EXIT_OK
        if (operands.isEmpty()) {
            count(ctx, ctx.stdin)?.let { report(ctx, it, "", want) }
            return ExecContext.EXIT_OK
        }
        for (op in operands) {
            val path = resolvePath(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
            val counts = try {
                ctx.session.vfs.openRead(path).use { count(ctx, it) }
            } catch (e: FsException) {
                if (reportFsError(ctx, op, e) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
                null
            }
            if (counts != null) {
                report(ctx, counts, op, want)
                total += counts
            }
        }
        if (operands.size > 1) report(ctx, total, "total", want)
        return status
    }

    private fun report(ctx: ExecContext, c: Counts, name: String, want: Set<Char>) {
        val parts = ArrayList<String>(3)
        if ('l' in want) parts += c.lines.toString()
        if ('w' in want) parts += c.words.toString()
        if ('c' in want) parts += c.bytes.toString()
        ctx.outLine(parts.joinToString(" ") + (if (name.isEmpty()) "" else " $name"))
    }

    /** One pass over the stream: a line is a newline, a word is a run between breaks. */
    private fun count(ctx: ExecContext, from: InputStream): Counts? {
        val c = Counts()
        val buf = ByteArray(8 * 1024)
        var inWord = false
        while (true) {
            if (ctx.cancelled.get()) return null
            val n = from.read(buf)
            if (n < 0) break
            c.bytes += n
            for (i in 0 until n) {
                when (val b = buf[i].toInt().toChar()) {
                    '\n' -> {
                        c.lines++
                        inWord = false
                    }
                    ' ', '\t', '\r' -> inWord = false
                    else -> if (!inWord) {
                        c.words++
                        inWord = true
                    }
                }
            }
        }
        return c
    }

    private data class Counts(var lines: Int = 0, var words: Int = 0, var bytes: Int = 0) {
        operator fun plus(o: Counts) = Counts(lines + o.lines, words + o.words, bytes + o.bytes)
    }
}
