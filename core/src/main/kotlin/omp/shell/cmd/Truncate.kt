package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs

@CommandSpec(
    name = "truncate",
    synopsis = "[-s N] file",
    group = "files",
    notes = "a size of 0 removes the file, as it does in coreutils; K, M and G suffixes are accepted",
)
object Truncate : FileCommand() {

    override val valueSpec = "s:"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val raw = options["s"]
        if (raw == null) {
            ctx.errLine("truncate: you must specify -s SIZE")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val size = parseSize(raw) ?: return ctx.fail("truncate: invalid number: '$raw'")
        if (operands.isEmpty()) return ctx.fail("truncate: missing file operand")

        var status = ExecContext.EXIT_OK
        val vfs = ctx.session.vfs
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (size == 0L) {
                if (fsExists(vfs, path) && !fsDeleteFile(ctx, "truncate", op, vfs, path)) {
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            if (!fsExists(vfs, path)) {
                // O_CREAT|O_EXCL: a name that appeared between the two answers as `File exists`, and
                // a path with nowhere to put it as whatever the seam could not create it for.
                try {
                    vfs.createFile(path)
                } catch (e: FsException) {
                    ctx.errLine("truncate: $op: ${e.errno.text}")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                } catch (e: SecurityException) {
                    ctx.errLine("truncate: $op: Permission denied")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
            }
            val failure = try {
                resize(vfs, vfs.realpath(path), size)
                null
            } catch (e: FsException) {
                e.errno.text
            } catch (e: SecurityException) {
                "Permission denied"
            }
            if (failure != null) {
                ctx.errLine("truncate: $op: $failure")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }

    /**
     * The seam sets no length, so the file is read and written back at the size asked for: the
     * bytes it had, cut or zero-padded. Truncating through [Vfs.writeBytes] keeps the name, the
     * inode and the permissions, which is what `truncate(1)` promises.
     */
    private fun resize(vfs: Vfs, path: String, size: Long) {
        if (size > Int.MAX_VALUE) throw FsException(FsErrno.INVALID_ARGUMENT, path)
        val current = if (fsExists(vfs, path)) vfs.readBytes(path) else ByteArray(0)
        if (current.size == size.toInt()) return
        val out = ByteArray(size.toInt())
        System.arraycopy(current, 0, out, 0, if (current.size < out.size) current.size else out.size)
        vfs.writeBytes(path, out)
    }

    private fun parseSize(raw: String): Long? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        var multiplier = 1L
        val suffixes = listOf("KiB" to 1024L, "KB" to 1024L, "MiB" to MIB, "MB" to MIB,
            "GiB" to GIB, "GB" to GIB, "K" to 1024L, "M" to MIB, "G" to GIB,
            "T" to TIB, "k" to 1024L, "m" to MIB, "g" to GIB, "t" to TIB)
        for ((suffix, factor) in suffixes) {
            if (text.endsWith(suffix) && text.length > suffix.length) {
                multiplier = factor
                text = text.substring(0, text.length - suffix.length)
                break
            }
        }
        val value = text.trim().toLongOrNull() ?: return null
        if (value < 0) return null
        return value * multiplier
    }

    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * 1024 * 1024
    private const val TIB = 1024L * 1024 * 1024 * 1024
}
