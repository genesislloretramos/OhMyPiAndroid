package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            if (size == 0L) {
                if (fsExists(file) && !fsDeleteFile(ctx, "truncate", op, file)) {
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            if (!fsExists(file)) {
                val created = try {
                    file.createNewFile()
                } catch (e: IOException) {
                    ctx.errLine("truncate: $op: ${Errno.messageFor(e)}")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                } catch (e: SecurityException) {
                    ctx.errLine("truncate: $op: Permission denied")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (!created) {
                    ctx.errLine("truncate: $op: File exists")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
            }
            val failure = try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(size)
                }
                null
            } catch (e: IOException) {
                Errno.messageFor(e)
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
