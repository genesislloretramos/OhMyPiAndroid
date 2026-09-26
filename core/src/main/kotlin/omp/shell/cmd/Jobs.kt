package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "jobs",
    synopsis = "",
    group = "builtins",
    notes = "a job leaves the list as soon as it finishes, so a background command that has already exited is not shown",
)
object Jobs : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        ctx.session.jobs().forEachIndexed { i, job ->
            ctx.outLine("[${i + 1}]  ${job.pid}  ${job.argv.joinToString(" ")}")
        }
        return ExecContext.EXIT_OK
    }
}
