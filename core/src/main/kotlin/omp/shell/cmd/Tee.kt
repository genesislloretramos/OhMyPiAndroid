package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import omp.shell.fs.FsException
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

@CommandSpec(
    name = "tee",
    synopsis = "[-a] file ...",
    group = "text",
    notes = "copies stdin to every file and to stdout",
)
object Tee : FileCommand() {

    override val flagSpec = "a"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) {
            ctx.errLine("tee: missing operand")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val append = 'a' in flags
        val vfs = ctx.session.vfs
        val files = ArrayList<Pair<String, OutputStream>>()
        var errored = false
        for (op in operands) {
            val path = Cmds.resolve(ctx, op)
            if (path == null) {
                errored = true
                continue
            }
            try {
                // The seam refuses a directory with `Is a directory`, which is the wording this
                // command has always printed for one, so there is no check to make here first.
                files += op to BufferedOutputStream(vfs.openWrite(path, append), 32 * 1024)
            } catch (e: FsException) {
                Errno.report(ctx, "tee", op, e)
                errored = true
            } catch (e: SecurityException) {
                Errno.report(ctx, "tee", op, e)
                errored = true
            }
        }

        // stdin is drained to the end whatever happened above: the pipeline's writer thread blocks
        // until this returns, so stopping early would hang the shell rather than end the command.
        val buf = ByteArray(32 * 1024)
        var cancelled = false
        var writeFailed = false
        while (true) {
            if (ctx.cancelled.get()) {
                cancelled = true
                break
            }
            val n = try {
                ctx.stdin.read(buf)
            } catch (e: IOException) {
                -1
            }
            if (n <= 0) break
            try {
                ctx.stdout.write(buf, 0, n)
            } catch (e: IOException) {
                // A closed downstream pipe (`tee f | head -1`) is not an error worth reporting.
            }
            for ((name, out) in files) {
                try {
                    out.write(buf, 0, n)
                } catch (e: IOException) {
                    Errno.report(ctx, "tee", name, e)
                    writeFailed = true
                }
            }
        }
        for ((name, out) in files) {
            try {
                out.close()
            } catch (e: IOException) {
                Errno.report(ctx, "tee", name, e)
                writeFailed = true
            }
        }
        if (cancelled) return ExecContext.EXIT_INTERRUPTED
        return if (errored || writeFailed) ExecContext.EXIT_GENERAL_ERROR else ExecContext.EXIT_OK
    }
}
