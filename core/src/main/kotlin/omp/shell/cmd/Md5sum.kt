package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

@CommandSpec(
    name = "md5sum",
    synopsis = "[file ...]",
    group = "text",
    notes = "prints <hex>  <name> with two spaces; with no operand it hashes stdin and calls it -",
)
object Md5sum : Command {

    override fun run(ctx: ExecContext): Int {
        val digest = MessageDigest.getInstance("MD5")
        if (ctx.args.isEmpty()) {
            if (!feed(ctx, ctx.stdin, digest)) return ExecContext.EXIT_INTERRUPTED
            ctx.outLine(hex(digest.digest()) + "  -")
            return ExecContext.EXIT_OK
        }
        var errored = false
        for (op in ctx.args) {
            val input = Cmds.openInput(ctx, op) ?: run { errored = true; continue }
            var complete = true
            // `feed` answers "it finished", so this is `finished` and not `cancelled`: reading the
            // result the other way round made every file operand exit 130 having printed nothing.
            val finished = try {
                feed(ctx, input, digest)
            } catch (e: IOException) {
                Errno.report(ctx, "md5sum", op, e)
                errored = true
                // A checksum of half a file would be a lie, so this operand prints nothing.
                complete = false
                false
            } finally {
                if (input !== ctx.stdin) input.close()
            }
            if (!finished) return ExecContext.EXIT_INTERRUPTED
            if (complete) ctx.outLine(hex(digest.digest()) + "  " + op)
        }
        return if (errored) ExecContext.EXIT_GENERAL_ERROR else ExecContext.EXIT_OK
    }

    /** Streams the whole input into [digest]; true means it finished, false means cancelled. */
    private fun feed(ctx: ExecContext, input: InputStream, digest: MessageDigest): Boolean {
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (ctx.cancelled.get()) return false
            val n = input.read(buf)
            if (n <= 0) return true
            digest.update(buf, 0, n)
        }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
