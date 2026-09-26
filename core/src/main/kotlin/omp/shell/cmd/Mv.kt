package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val dest = File(destPath)
        val destIsDir = dest.isDirectory
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
            val src = File(srcPath)
            if (!fsExists(src)) {
                ctx.errLine("mv: $srcRaw: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val target = if (destIsDir) File(dest, src.name) else dest
            if (target.path == src.path) continue
            if (target.parentFile?.path == src.path) {
                ctx.errLine("mv: cannot move '$srcRaw' to a subdirectory of itself")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (!moveOne(ctx, src, target, srcRaw)) status = ExecContext.EXIT_GENERAL_ERROR
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return status
    }

    private fun moveOne(ctx: ExecContext, src: File, target: File, srcRaw: String): Boolean {
        if (fsExists(target) && target.isDirectory != src.isDirectory) {
            val clash = if (src.isDirectory) {
                "non-directory '${target.path}' with directory"
            } else {
                "directory '${target.path}' with non-directory"
            }
            ctx.errLine("mv: cannot overwrite $clash")
            return false
        }
        try {
            Files.move(src.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: AtomicMoveNotSupportedException) {
            // Either a provider without atomic moves or a different filesystem; the next two cover both.
        } catch (e: IOException) {
        } catch (e: SecurityException) {
            ctx.errLine("mv: $srcRaw: Permission denied")
            return false
        }
        try {
            Files.move(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (e: IOException) {
            // A rename across a mount point fails here too, and the copy path below takes over.
        } catch (e: SecurityException) {
            ctx.errLine("mv: $srcRaw: Permission denied")
            return false
        }
        return copyThenDelete(ctx, src, target, srcRaw)
    }

    private fun copyThenDelete(ctx: ExecContext, src: File, target: File, srcRaw: String): Boolean {
        val copied = try {
            if (!src.isDirectory) {
                fsCopyOne(src.toPath(), target.toPath())
                true
            } else {
                fsCopyTree(ctx, "mv", src, target, ctx.cancelled)
            }
        } catch (e: IOException) {
            ctx.errLine("mv: $srcRaw: ${Errno.messageFor(e)}")
            return false
        } catch (e: SecurityException) {
            ctx.errLine("mv: $srcRaw: Permission denied")
            return false
        }
        if (!copied) {
            ctx.errLine("mv: could not copy the whole tree to '${target.path}'")
            return false
        }
        val removed = if (fsIsLink(src) || !src.isDirectory) {
            // Removing a link must not touch whatever it points at.
            fsDeleteFile(ctx, "mv", srcRaw, src)
        } else {
            fsDeleteTree(ctx, "mv", src, ctx.cancelled)
        }
        if (!removed) {
            ctx.errLine("mv: $srcRaw was copied to '${target.path}' but the source could not be removed")
            return false
        }
        return true
    }
}
