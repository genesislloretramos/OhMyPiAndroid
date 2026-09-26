package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

@CommandSpec(
    name = "exit",
    synopsis = "[n]",
    group = "builtins",
    notes = "ends the session; with no argument the last command's status is used",
)
object Exit : Command {
    override fun run(ctx: ExecContext): Int {
        val args = ctx.args
        if (args.size > 1) {
            ctx.errLine("sh: exit: too many arguments")
            return ExecContext.EXIT_USAGE
        }
        val status = if (args.isEmpty()) {
            ctx.session.lastStatus
        } else {
            args[0].trim().toIntOrNull() ?: run {
                ctx.errLine("sh: exit: ${args[0]}: numeric argument required")
                return ExecContext.EXIT_USAGE
            }
        }
        // lastStatus is set as well as returned, because the REPL records the return value and
        // `exit` with no argument must keep meaning "the last status".
        ctx.session.lastStatus = status
        ctx.session.exitRequested = true
        return status
    }
}
