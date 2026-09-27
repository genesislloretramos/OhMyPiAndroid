package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

/**
 * Stops a running job by setting the cancel flag it already polls.
 *
 * A pid whose job has already finished is refused, in words of its own rather than in the words
 * used for a pid this shell has never heard of. "No such process" is the true answer for a pid that
 * never existed and a false one for a job that ran and exited: it claims there was never such a
 * process, which is not what happened, and the difference is one a user acts on — this pid is a
 * real job that is already over, and its status is where `wait` now answers for it. So the refusal
 * says that instead, and the status is still a failure, because nothing was stopped and a `kill`
 * reporting success would be claiming work it did not do.
 */
@CommandSpec(
    name = "kill",
    synopsis = "[-9] PID ...",
    group = "builtins",
    notes = "sets the job's cancel flag, which is what stops it: every long-running command polls it, so -9 and the default do the same thing here",
)
object Kill : FileCommand() {
    override val flagSpec = "9"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isEmpty()) {
            ctx.errLine("kill: missing PID")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        var status = ExecContext.EXIT_OK
        for (raw in operands) {
            val pid = raw.toIntOrNull()
            val job = if (pid == null) null else ctx.session.job(pid)
            if (job == null) {
                ctx.errLine("kill: ($raw) - No such process")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            // Still on record, and over. The flag would be set on a job nothing reads it any more,
            // which is a `kill` reporting that it stopped a process that stopped on its own.
            if (!job.isRunning()) {
                ctx.errLine("kill: ($raw) - Job has already finished; wait $raw for its status")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            job.cancel()
        }
        return status
    }
}
