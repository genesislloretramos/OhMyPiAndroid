package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

@CommandSpec(
    name = "sync",
    synopsis = "",
    group = "files",
    notes = "flushes what this shell has buffered; the device sync itself is the filesystem's business",
)
object Sync : Command {

    override fun run(ctx: ExecContext): Int {
        ctx.flush()
        return ExecContext.EXIT_OK
    }
}
