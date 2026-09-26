package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "true",
    synopsis = "",
    group = "builtins",
    notes = "succeeds; `:` is not registered, so use this in a pipeline",
)
object True : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int =
        ExecContext.EXIT_OK
}
