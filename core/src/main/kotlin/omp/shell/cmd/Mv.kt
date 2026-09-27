package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs

@CommandSpec(
    name = "mv",
    synopsis = "src ... dst",
    group = "files",
    notes = "a cross-filesystem move is a copy followed by a delete, and a failed delete is reported",
)
object Mv : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.size < 2) {
            return ctx.fail("mv: missing destination file operand after '${ctx.args.firstOrNull() ?: ""}'")
        }
        val destRaw = ctx.args.last()
        val sources = ctx.args.dropLast(1)
        val destPath = Cmds.resolve(ctx, destRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val vfs = ctx.session.vfs
        // The destination may be a link to a directory, and writing through it is what a shell
        // does; the source must not be followed, or `mv link name` would move the target.
        val destStat = fsStatOrNull(vfs, destPath)
        val destIsDir = destStat != null && fsIsDirFollowing(vfs, destPath, destStat)
        if (sources.size > 1 && !destIsDir) {
            return ctx.fail("mv: target '$destRaw' is not a directory")
        }

        var status = ExecContext.EXIT_OK
        for (srcRaw in sources) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val srcPath = Cmds.resolve(ctx, srcRaw) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val srcStat = fsStatOrNull(vfs, srcPath)
            if (srcStat == null) {
                ctx.errLine("mv: $srcRaw: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val target = if (destIsDir) fsChild(destPath, fsName(srcPath)) else destPath
            if (target == srcPath) continue
            if (target.substringBeforeLast('/', "") == srcPath) {
                ctx.errLine("mv: cannot move '$srcRaw' to a subdirectory of itself")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (!moveOne(ctx, vfs, srcPath, srcStat, target, srcRaw)) status = ExecContext.EXIT_GENERAL_ERROR
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return status
    }

    private fun moveOne(
        ctx: ExecContext,
        vfs: Vfs,
        src: String,
        srcStat: VStat,
        target: String,
        srcRaw: String,
    ): Boolean {
        val srcIsDir = srcStat.type == VNodeType.DIRECTORY
        val targetStat = fsStatOrNull(vfs, target)
        if (targetStat != null && fsIsDirFollowing(vfs, target, targetStat) != srcIsDir) {
            val clash = if (srcIsDir) {
                "non-directory '$target' with directory"
            } else {
                "directory '$target' with non-directory"
            }
            ctx.errLine("mv: cannot overwrite $clash")
            return false
        }
        // The seam has one rename, which is the atomic one inside a filesystem; a rename across a
        // mount point is not available there either, and the copy below takes over.
        try {
            vfs.rename(src, target)
            return true
        } catch (e: SecurityException) {
            ctx.errLine("mv: $srcRaw: Permission denied")
            return false
        } catch (e: FsException) {
        }
        return copyThenDelete(ctx, vfs, src, srcIsDir, target, srcRaw)
    }

    private fun copyThenDelete(
        ctx: ExecContext,
        vfs: Vfs,
        src: String,
        srcIsDir: Boolean,
        target: String,
        srcRaw: String,
    ): Boolean {
        val copied = try {
            if (!srcIsDir) {
                fsCopyOne(vfs, src, target)
                true
            } else {
                fsCopyTree(ctx, "mv", vfs, src, target, ctx.cancelled)
            }
        } catch (e: FsException) {
            ctx.errLine("mv: $srcRaw: ${e.errno.text}")
            return false
        } catch (e: SecurityException) {
            ctx.errLine("mv: $srcRaw: Permission denied")
            return false
        }
        if (!copied) {
            ctx.errLine("mv: could not copy the whole tree to '$target'")
            return false
        }
        val removed = if (!srcIsDir) {
            // Removing a link must not touch whatever it points at.
            fsDeleteFile(ctx, "mv", srcRaw, vfs, src)
        } else {
            fsDeleteTree(ctx, "mv", vfs, src, ctx.cancelled)
        }
        if (!removed) {
            ctx.errLine("mv: $srcRaw was copied to '$target' but the source could not be removed")
            return false
        }
        return true
    }
}
