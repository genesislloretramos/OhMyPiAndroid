package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat

@CommandSpec(
    name = "stat",
    synopsis = "file ...",
    group = "files",
    notes = "lstat: a symbolic link is reported as the link, with its target on a Link line; " +
        "there is no -L, and no uid, gid or inode because an app cannot read them",
)
object Stat : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("stat: missing file operand")
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val stat = fsStatOrNull(vfs, path)
            if (stat == null) {
                ctx.errLine("stat: $op: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine("  File: $path")
            ctx.outLine("  Size: ${stat.size}")
            ctx.outLine("  Type: ${typeOf(stat)}")
            // A [VStat] carries one timestamp, the one every other tool here prints, so the access
            // and change times are the modification time rather than three guesses at one number.
            ctx.outLine("Access: ${Cmds.timestampSec(stat.mtimeMillis)}")
            ctx.outLine("Modify: ${Cmds.timestampSec(stat.mtimeMillis)}")
            ctx.outLine("Change: ${Cmds.timestampSec(stat.mtimeMillis)}")
            ctx.outLine("  Mode: ${Cmds.perms(stat)}")
            if (stat.type == VNodeType.SYMLINK) {
                val target = fsLinkTarget(vfs, path)
                if (target != null) ctx.outLine("  Link: $target")
            }
            ctx.outLine()
        }
        return status
    }

    private fun typeOf(stat: VStat): String = when (stat.type) {
        VNodeType.SYMLINK -> "symbolic link"
        VNodeType.DIRECTORY -> "directory"
        VNodeType.FILE -> "regular file"
        // A device node, a fifo or a socket: the seam does not say which, so it is not claimed.
        else -> "special file"
    }
}
