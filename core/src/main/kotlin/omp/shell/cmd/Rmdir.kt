package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import java.io.File

@CommandSpec(
    name = "rmdir",
    synopsis = "dir ...",
    group = "files",
    notes = "removes empty directories only; use `rm -r` for a tree",
)
object Rmdir : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("rmdir: missing operand")
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val dir = File(path)
            // listDir reports a missing path, a non-directory and an unreadable directory itself.
            val entries = Cmds.listDir(ctx, "rmdir", dir)
            if (entries == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (entries.isNotEmpty()) {
                ctx.errLine("rmdir: $op: Directory not empty")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (!fsDeleteFile(ctx, "rmdir", op, dir)) status = ExecContext.EXIT_GENERAL_ERROR
        }
        return status
    }
}
