package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "unset",
    synopsis = "NAME ...",
    group = "builtins",
    notes = "removes the name from the session environment; a name that was never set is not an error",
)
object Unset : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        for (name in operands) {
            ctx.session.env.remove(name)
            ctx.env.remove(name)
        }
        return ExecContext.EXIT_OK
    }
}
