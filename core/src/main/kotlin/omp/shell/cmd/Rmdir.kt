package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.fs.VNodeType

@CommandSpec(
    name = "rmdir",
    synopsis = "dir ...",
    group = "files",
    notes = "removes empty directories only; use `rm -r` for a tree",
)
object Rmdir : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("rmdir: missing operand")
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            // A link is not a directory, even when it points at one: `rmdir` removes the name the
            // user typed, and the target is not theirs to remove. listDir then reports an unreadable
            // directory itself.
            val stat = fsStatOrNull(vfs, path)
            if (stat == null) {
                ctx.errLine("rmdir: $op: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (stat.type != VNodeType.DIRECTORY) {
                ctx.errLine("rmdir: $op: Not a directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val entries = Cmds.listDir(ctx, "rmdir", vfs, path)
            if (entries == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (entries.isNotEmpty()) {
                ctx.errLine("rmdir: $op: Directory not empty")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            // A directory is not something the seam unlinks; this is the one place it is removed.
            if (!fsRmdir(ctx, "rmdir", op, vfs, path)) status = ExecContext.EXIT_GENERAL_ERROR
        }
        return status
    }
}
