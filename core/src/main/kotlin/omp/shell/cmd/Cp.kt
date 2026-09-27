package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType

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
        val vfs = ctx.session.vfs
        val destStat = fsStatOrNull(vfs, destPath)
        val destIsDir = destStat != null && fsIsDirFollowing(vfs, destPath, destStat)
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
            // `cp` copies what a link leads to; only the name being written to is left alone.
            val srcStat = Cmds.statFollowedOrNull(vfs, srcPath)
            if (srcStat == null) {
                ctx.errLine("cp: $srcRaw: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val target = if (destIsDir) fsChild(destPath, fsName(srcPath)) else destPath
            val srcIsDir = srcStat.type == VNodeType.DIRECTORY
            if (srcIsDir) {
                if (!recursive) {
                    ctx.errLine("cp: -r not specified; omitting directory '$srcRaw'")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                val targetStat = fsStatOrNull(vfs, target)
                if (targetStat != null && !fsIsDirFollowing(vfs, target, targetStat)) {
                    ctx.errLine("cp: cannot overwrite non-directory '$target' with directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                val failure = try {
                    if (fsCopyTree(ctx, "cp", vfs, srcPath, target, ctx.cancelled)) null
                    else "could not copy the whole tree"
                } catch (e: FsException) {
                    e.errno.text
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
                val targetStat = fsStatOrNull(vfs, target)
                if (targetStat != null && fsIsDirFollowing(vfs, target, targetStat)) {
                    "target '$target' is a directory"
                } else {
                    fsCopyOne(vfs, srcPath, target)
                    null
                }
            } catch (e: FsException) {
                e.errno.text
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
