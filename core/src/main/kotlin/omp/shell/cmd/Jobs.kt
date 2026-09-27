package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * The jobs this session is running now, in the order they were started.
 *
 * The filter is in this read and not in [omp.shell.Session.jobs], because the index deliberately
 * holds a job that has already finished: `wait <pid>` has to answer for one, and the shell's
 * retention policy can only bound what it can see. `jobs` is the one command whose whole answer is
 * "what is running", so it is the one place that has to say so.
 */
@CommandSpec(
    name = "jobs",
    synopsis = "",
    group = "builtins",
    notes = "a job leaves the list as soon as it finishes, so a background command that has already exited is not shown",
)
object Jobs : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        // A finished job is still on record — it is `wait`'s to answer for — but a line here would
        // be a claim about what this shell is doing now, and it would be false.
        ctx.session.jobs().filter { it.isRunning() }.forEachIndexed { i, job ->
            ctx.outLine("[${i + 1}]  ${job.pid}  ${job.argv.joinToString(" ")}")
        }
        return ExecContext.EXIT_OK
    }
}
