package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "false",
    synopsis = "",
    group = "builtins",
    notes = "fails with status 1",
)
object False : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int =
        ExecContext.EXIT_GENERAL_ERROR
}
