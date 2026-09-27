package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * `cat` inside the namespace. The only difference from the phone's is the dozen lines that open a
 * file: everything here goes through the session's [omp.shell.fs.Vfs], which is what makes
 * `cat /proc/meminfo` work and `cat /dev/urandom | head -c 8` work too.
 */
@CommandSpec(
    name = "cat",
    synopsis = "[FILE ...]",
    group = "vm",
    notes = "reads through the Vfs, so it can cat /proc, /sys and /dev as well as the rootfs",
)
object VmCat : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isEmpty()) return copy(ctx, ctx.stdin, ctx.stdout)
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            if (op == "-") {
                if (copy(ctx, ctx.stdin, ctx.stdout) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val path = resolvePath(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
            try {
                ctx.session.vfs.openRead(path).use {
                    if (copy(ctx, it, ctx.stdout) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
                }
            } catch (e: FsException) {
                if (reportFsError(ctx, op, e) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }

    private fun copy(ctx: ExecContext, from: InputStream, to: OutputStream): Int {
        val buf = ByteArray(8 * 1024)
        return try {
            while (true) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
            }
            to.flush()
            ExecContext.EXIT_OK
        } catch (e: IOException) {
            // A pipe that closes is `head` doing its job, not a failure worth reporting.
            val text = e.message ?: ""
            if (text.contains("Pipe closed") || text.contains("Write end dead") || text.contains("Broken pipe")) {
                ExecContext.EXIT_OK
            } else {
                ctx.errLine("cat: $text")
                ExecContext.EXIT_GENERAL_ERROR
            }
        }
    }
}
