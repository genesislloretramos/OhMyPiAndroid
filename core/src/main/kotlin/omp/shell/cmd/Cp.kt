package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File
import java.io.IOException
import java.nio.file.Files

@CommandSpec(
    name = "cp",
    synopsis = "[-r] src ... dst",
    group = "files",
    notes = "-r is required for a directory; a directory destination takes the basename of each source",
)
object Cp : FileCommand() {

    override val flagSpec = "r"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.size < 2) {
            return ctx.fail("cp: missing destination file operand after '${operands.firstOrNull() ?: ""}'")
        }
        val recursive = 'r' in flags
        val destRaw = operands.last()
        val sources = operands.dropLast(1)
        val destPath = Cmds.resolve(ctx, destRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val dest = File(destPath)
        val destIsDir = dest.isDirectory
        if (sources.size > 1 && !destIsDir) {
            return ctx.fail("cp: target '$destRaw' is not a directory")
        }

        var status = ExecContext.EXIT_OK
        for (srcRaw in sources) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val srcPath = Cmds.resolve(ctx, srcRaw) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val src = File(srcPath)
            if (!fsExists(src)) {
                ctx.errLine("cp: $srcRaw: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val target = if (destIsDir) File(dest, src.name) else dest
            val srcIsDir = src.isDirectory
            if (srcIsDir) {
                if (!recursive) {
                    ctx.errLine("cp: -r not specified; omitting directory '$srcRaw'")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (fsExists(target) && !target.isDirectory) {
                    ctx.errLine("cp: cannot overwrite non-directory '${target.path}' with directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                val failure = try {
                    if (fsCopyTree(ctx, "cp", src, target, ctx.cancelled)) null else "could not copy the whole tree"
                } catch (e: IOException) {
                    Errno.messageFor(e)
                } catch (e: SecurityException) {
                    "Permission denied"
                }
                if (failure != null) {
                    ctx.errLine("cp: $srcRaw: $failure")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            val fileFailure = try {
                if (target.isDirectory) {
                    "target '${target.path}' is a directory"
                } else {
                    fsCopyOne(src.toPath(), target.toPath())
                    null
                }
            } catch (e: IOException) {
                Errno.messageFor(e)
            } catch (e: SecurityException) {
                "Permission denied"
            }
            if (fileFailure != null) {
                ctx.errLine("cp: $srcRaw: $fileFailure")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return status
    }
}
