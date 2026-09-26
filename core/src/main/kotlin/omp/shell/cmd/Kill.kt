package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

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
            job.cancel()
        }
        return status
    }
}
