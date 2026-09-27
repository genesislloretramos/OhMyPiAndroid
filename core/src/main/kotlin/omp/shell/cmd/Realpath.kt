package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.fs.FsException

@CommandSpec(
    name = "realpath",
    synopsis = "file ...",
    group = "files",
    notes = "one canonical path per line; ~ and the storage aliases are expanded first",
)
object Realpath : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("realpath: missing operand")
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            // This is the followed-path command: the resolver is lexical, so the walk is ours.
            val target = try {
                vfs.realpath(path)
            } catch (e: FsException) {
                ctx.errLine("realpath: $op: ${e.errno.text}")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine(target)
        }
        return status
    }
}
