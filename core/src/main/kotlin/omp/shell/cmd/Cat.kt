package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.IOException
import java.io.InputStream
import java.util.Locale

@CommandSpec(
    name = "cat",
    synopsis = "[-n] file ...",
    group = "files",
    notes = "with no operand it reads stdin; bytes are passed through untouched, so binary survives",
)
object Cat : FileCommand() {

    override val flagSpec = "n"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val number = 'n' in flags
        if (operands.isEmpty()) return pump(ctx, ctx.stdin, number, null)
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val source = Cmds.openInput(ctx, op)
            if (source == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val rc = pump(ctx, source, number, op)
            if (rc != ExecContext.EXIT_OK) status = rc
            if (source !== ctx.stdin) closeQuietly(source)
        }
        return status
    }

    /**
     * Streams the bytes rather than the lines, so a binary file is reproduced exactly. Numbering
     * only inspects the byte values it is already writing, so it costs nothing when `-n` is absent.
     */
    private fun pump(ctx: ExecContext, source: InputStream, number: Boolean, op: String?): Int {
        val buf = ByteArray(64 * 1024)
        var line = 1L
        var atLineStart = true
        while (true) {
            val n = try {
                source.read(buf)
            } catch (e: IOException) {
                return if (op == null) {
                    ctx.fail("${ctx.name}: ${Errno.messageFor(e)}")
                } else {
                    Errno.report(ctx, ctx.name, op, e)
                }
            }
            if (n < 0) return ExecContext.EXIT_OK
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            if (!number) {
                ctx.stdout.write(buf, 0, n)
                continue
            }
            for (i in 0 until n) {
                val b = buf[i]
                if (atLineStart) {
                    ctx.out(String.format(Locale.US, "%6d\t", line))
                    line++
                }
                ctx.stdout.write(b.toInt() and 0xFF)
                atLineStart = (b.toInt() and 0xFF) == 0x0A
            }
        }
    }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
        }
    }
}
