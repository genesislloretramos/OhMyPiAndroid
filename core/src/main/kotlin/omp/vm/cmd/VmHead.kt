package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * `head` inside the namespace, and the reason `head -c 10 /dev/zero` is a terminating command:
 * it reads exactly as many bytes as it was asked for and then stops, which is the only sane way to
 * read a device that has no end.
 */
@CommandSpec(
    name = "head",
    synopsis = "[-c N] [-n N] [FILE ...]",
    group = "vm",
    notes = "reads through the Vfs; -c is what makes /dev/zero and /dev/urandom usable",
)
object VmHead : FileCommand() {
    override val flagSpec = "cn"
    override val valueSpec = "n:"

    /**
     * `head -3`, `head -c 10` and `head -c10` are the spellings people type, and this command
     * exists mostly so `head -c N /dev/zero` works; rewriting them here is the whole of the
     * option-parsing work.
     */
    override fun normalizeArgv(argv: List<String>): List<String> {
        val out = ArrayList<String>(argv.size + 2)
        var i = 0
        while (i < argv.size) {
            val arg = argv[i]
            when {
                arg == "-c" && i + 1 < argv.size && argv[i + 1].toIntOrNull() != null -> {
                    out += "-c"
                    out += "-n"
                    out += argv[i + 1]
                    i += 2
                }
                arg.startsWith("-c") && arg.length > 2 && arg.drop(2).toIntOrNull() != null -> {
                    out += "-c"
                    out += "-n"
                    out += arg.drop(2)
                    i++
                }
                arg.length > 1 && arg.startsWith("-") && arg.drop(1).toIntOrNull() != null -> {
                    out += "-n"
                    out += arg.drop(1)
                    i++
                }
                else -> {
                    out += arg
                    i++
                }
            }
        }
        return out
    }

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val count = options["n"]?.toIntOrNull() ?: 10
        val limit = count.coerceAtLeast(0)
        val bytes = flags.contains('c')

        if (operands.isEmpty()) return pump(ctx, ctx.stdin, limit, bytes)
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = resolvePath(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
            try {
                ctx.session.vfs.openRead(path).use {
                    if (pump(ctx, it, limit, bytes) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
                }
            } catch (e: FsException) {
                if (reportFsError(ctx, op, e) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }

    private fun pump(ctx: ExecContext, from: InputStream, limit: Int, bytes: Boolean): Int {
        if (bytes) {
            val buf = ByteArray(minOf(limit, 8 * 1024))
            var written = 0
            while (written < limit) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val want = minOf(buf.size.toLong(), (limit - written).toLong()).toInt()
                val n = from.read(buf, 0, want)
                if (n < 0) break
                // Straight into the stream: the bytes are already in the buffer, and copying them
                // again to hand `out` something smaller would allocate for no reason.
                ctx.stdout.write(buf, 0, n)
                written += n
            }
            ctx.flush()
            return ExecContext.EXIT_OK
        }
        var lines = 0
        val reader = BufferedReader(InputStreamReader(from, Charsets.UTF_8))
        while (lines < limit) {
            val line = reader.readLine() ?: break
            ctx.outLine(line)
            lines++
        }
        return ExecContext.EXIT_OK
    }
}
