package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import java.io.File

@CommandSpec(
    name = "realpath",
    synopsis = "file ...",
    group = "files",
    notes = "one canonical path per line; ~ and the storage aliases are expanded first",
)
object Realpath : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("realpath: missing operand")
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (!File(path).exists()) {
                ctx.errLine("realpath: $op: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine(path)
        }
        return status
    }
}
