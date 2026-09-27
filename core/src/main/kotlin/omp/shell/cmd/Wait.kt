package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.util.concurrent.TimeUnit

@CommandSpec(
    name = "wait",
    synopsis = "[PID]",
    group = "builtins",
    notes = "prints each job's exit status; Ctrl-C interrupts the wait itself, not the job being waited for",
)
object Wait : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val jobs = if (operands.isEmpty()) {
            ctx.session.jobs()
            // The unfiltered index, on purpose: a job that has already finished still has a
            // status, and this is the command that answers for it. `jobs` filters; this does not.
        } else {
            if (operands.size > 1) {
                ctx.errLine("wait: too many arguments")
                return ExecContext.EXIT_USAGE
            }
            val pid = operands[0].toIntOrNull()
            if (pid == null) {
                ctx.errLine("wait: ${operands[0]}: numeric argument required")
                return ExecContext.EXIT_USAGE
            }
            listOfNotNull(ctx.session.job(pid) ?: run {
                ctx.errLine("wait: $pid: no such job")
                return ExecContext.EXIT_GENERAL_ERROR
            })
        }
        for (job in jobs) {
            // Polling rather than an unbounded await, so Ctrl-C still lands while this blocks.
            while (job.finished.count > 0) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                job.finished.await(50, TimeUnit.MILLISECONDS)
            }
            ctx.outLine(job.status.toString())
        }
        return ExecContext.EXIT_OK
    }
}
