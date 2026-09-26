package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

@CommandSpec(
    name = "pwd",
    synopsis = "",
    group = "files",
    notes = "the logical working directory, already symlink-free",
)
object Pwd : Command {

    override fun run(ctx: ExecContext): Int {
        ctx.outLine(ctx.session.cwd)
        return ExecContext.EXIT_OK
    }
}
