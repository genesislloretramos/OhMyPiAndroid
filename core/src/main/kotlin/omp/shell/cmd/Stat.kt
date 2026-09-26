package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

@CommandSpec(
    name = "stat",
    synopsis = "file ...",
    group = "files",
    notes = "no uid, gid or inode: an app cannot read them, and a made-up value is worse than a missing column",
)
object Stat : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("stat: missing file operand")
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            val attrs = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                ctx.errLine("stat: $op: ${Errno.messageFor(e)}")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            } catch (e: SecurityException) {
                ctx.errLine("stat: $op: Permission denied")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine("  File: $path")
            ctx.outLine("  Size: ${attrs.size()}")
            ctx.outLine("  Type: ${typeOf(attrs)}")
            ctx.outLine("Access: ${Cmds.timestampSec(attrs.lastAccessTime().toMillis())}")
            ctx.outLine("Modify: ${Cmds.timestampSec(attrs.lastModifiedTime().toMillis())}")
            ctx.outLine("Change: ${Cmds.timestampSec(attrs.creationTime().toMillis())}")
            ctx.outLine("  Mode: ${Cmds.perms(file)}")
            if (attrs.isSymbolicLink) {
                val target = fsLinkTarget(file)
                if (target != null) ctx.outLine("  Link: $target")
            }
            ctx.outLine()
        }
        return status
    }

    private fun typeOf(attrs: BasicFileAttributes): String = when {
        attrs.isSymbolicLink -> "symbolic link"
        attrs.isDirectory -> "directory"
        attrs.isRegularFile -> "regular file"
        // A device node, a fifo or a socket: java.io cannot tell which, so it is not claimed.
        else -> "special file"
    }
}
