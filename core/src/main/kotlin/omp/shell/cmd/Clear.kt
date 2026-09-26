package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "clear",
    synopsis = "",
    group = "builtins",
    notes = "home, erase the display, erase the scrollback",
)
object Clear : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        ctx.out("\u001B[H\u001B[2J\u001B[3J")
        ctx.flush()
        return ExecContext.EXIT_OK
    }
}
