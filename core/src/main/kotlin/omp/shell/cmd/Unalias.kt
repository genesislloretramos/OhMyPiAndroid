package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "unalias",
    synopsis = "NAME ...",
    group = "builtins",
    notes = "removes one alias; a name that is not an alias is reported and the rest are still removed",
)
object Unalias : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        var status = ExecContext.EXIT_OK
        for (name in operands) {
            if (ctx.session.aliases.remove(name) == null) {
                ctx.errLine("unalias: $name: not found")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }
}
