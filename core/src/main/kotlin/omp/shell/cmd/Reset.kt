package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "reset",
    synopsis = "",
    group = "builtins",
    notes = "RIS: re-initialises the screen, which is what a real terminal reset does",
)
object Reset : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        ctx.out("\u001Bc")
        // The escape travels through stdout like any other output; the screen is re-initialised
        // here too, because stdout may be a pipe that never reaches the screen at all.
        ctx.session.screen.reset()
        ctx.flush()
        return ExecContext.EXIT_OK
    }
}
