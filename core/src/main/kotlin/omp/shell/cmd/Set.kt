package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * The positional parameters `set a b c` assigns.
 *
 * `Expander` owns the field that `$1` and `$@` read, but it hangs off `Shell`, which `ExecContext`
 * does not expose, so the values are parked here where the session start-up can hand them over.
 */
object Positional {
    @Volatile
    var values: List<String> = emptyList()
}

@CommandSpec(
    name = "set",
    synopsis = "[-e] [-x] [-u] [ARG ...]",
    group = "builtins",
    notes = "-x traces, -u errors on an unset variable, -e exits on a failing pipeline; ARG become the positional parameters",
)
object Set : FileCommand() {
    override val flagSpec = "exu"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val session = ctx.session
        if (flags.contains('e')) session.errexit = true
        if (flags.contains('x')) session.xtrace = true
        if (flags.contains('u')) session.nounset = true

        if (operands.isNotEmpty()) {
            Positional.values = operands
            return ExecContext.EXIT_OK
        }

        for ((name, value) in session.env) ctx.outLine("$name=$value")
        val active = buildList {
            if (session.errexit) add("-e")
            if (session.xtrace) add("-x")
            if (session.nounset) add("-u")
        }
        if (active.isNotEmpty()) ctx.outLine(active.joinToString(" ", prefix = "options: "))
        return ExecContext.EXIT_OK
    }
}
