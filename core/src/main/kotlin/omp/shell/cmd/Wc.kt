package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.IOException
import java.io.InputStream

@CommandSpec(
    name = "wc",
    synopsis = "[-lwcm] [file ...]",
    group = "text",
    notes = "with no option it prints lines, words and bytes; -c is bytes and -m is characters, " +
        "and a CRLF line ending is counted rather than stripped",
)
object Wc : FileCommand() {

    override val flagSpec = "lwcm"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val wantLines = flags.isEmpty() || 'l' in flags
        val wantWords = flags.isEmpty() || 'w' in flags
        val wantBytes = flags.isEmpty() || 'c' in flags
        val wantChars = 'm' in flags
        val selected = listOf(wantLines, wantWords, wantBytes, wantChars).count { it }

        val rows = ArrayList<Row>()
        var errored = false
        if (operands.isEmpty()) {
            if (!scan(ctx, ctx.stdin, rows)) return ExecContext.EXIT_INTERRUPTED
            rows[0] = Row("", rows[0].counts)
        } else {
            for (op in operands) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val input = Cmds.openInput(ctx, op) ?: run { errored = true; continue }
                val before = rows.size
                val ok = try {
                    scan(ctx, input, rows)
                } catch (e: IOException) {
                    Errno.report(ctx, "wc", op, e)
                    errored = true
                    true
                } finally {
                    if (input !== ctx.stdin) input.close()
                }
                if (!ok) return ExecContext.EXIT_INTERRUPTED
                for (i in before until rows.size) {
                    val r = rows[i]
                    if (r.name.isEmpty()) rows[i] = Row(op, r.counts)
                }
            }
        }

        if (operands.size > 1) {
            val total = Counts()
            for (r in rows) {
                total.lines += r.counts.lines
                total.words += r.counts.words
                total.bytes += r.counts.bytes
                total.chars += r.counts.chars
            }
            rows += Row("total", total)
        }

        val sb = StringBuilder()
        for (r in rows) {
            if (wantLines) sb.appendNumber(r.counts.lines, selected > 1)
            if (wantWords) sb.appendNumber(r.counts.words, selected > 1)
            if (wantBytes) sb.appendNumber(r.counts.bytes, selected > 1)
            if (wantChars) sb.appendNumber(r.counts.chars, selected > 1)
            if (r.name.isNotEmpty()) sb.append(' ').append(r.name)
            sb.append('\n')
        }
        ctx.out(sb.toString())
        return if (errored) ExecContext.EXIT_GENERAL_ERROR else ExecContext.EXIT_OK
    }

    private class Counts(
        var lines: Long = 0,
        var words: Long = 0,
        var bytes: Long = 0,
        var chars: Long = 0,
    )

    private class Row(val name: String, val counts: Counts)

    private fun StringBuilder.appendNumber(value: Long, pad: Boolean) {
        val text = value.toString()
        if (pad) {
            for (i in 0 until 7 - text.length) append(' ')
        }
        append(text)
    }

    /**
     * A byte-level pass rather than a line reader, because the last line counts only when it ended
     * with a newline, and only the bytes know that.
     */
    private fun scan(ctx: ExecContext, input: InputStream, out: MutableList<Row>): Boolean {
        val c = Counts()
        val buf = ByteArray(32 * 1024)
        var previousBlank = true
        var any = false
        var lastWasNewline = false
        while (true) {
            if (ctx.cancelled.get()) return false
            val n = input.read(buf)
            if (n <= 0) break
            any = true
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                c.bytes++
                // A continuation byte is 10xxxxxx, so counting everything else counts code points.
                if (b and 0xC0 != 0x80) c.chars++
                val blank = b == 0x20 || b == 0x09 || b == 0x0A
                if (!blank && previousBlank) c.words++
                previousBlank = blank
                if (b == 0x0A) c.lines++
            }
            lastWasNewline = buf[n - 1] == 0x0A.toByte()
        }
        if (any && !lastWasNewline) c.lines++
        out += Row("", c)
        return true
    }
}
