package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.SessionAssignmentCommand

@CommandSpec(
    name = "export",
    synopsis = "NAME=VALUE ... | NAME",
    group = "builtins",
    notes = "session environment only; nothing is persisted, and there is no separate export list",
)
object Export : FileCommand(), SessionAssignmentCommand {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isEmpty()) {
            for ((name, value) in ctx.session.env) ctx.outLine("$name=$value")
            return ExecContext.EXIT_OK
        }
        var status = ExecContext.EXIT_OK
        for (operand in operands) {
            val eq = operand.indexOf('=')
            if (eq >= 0) {
                val name = operand.substring(0, eq)
                if (!isName(name)) {
                    ctx.errLine("export: `$operand': not a valid identifier")
                    return ExecContext.EXIT_USAGE
                }
                ctx.session.env[name] = operand.substring(eq + 1)
                continue
            }
            if (!isName(operand)) {
                ctx.errLine("export: `$operand': not a valid identifier")
                return ExecContext.EXIT_USAGE
            }
            val value = ctx.session.env[operand]
            if (value == null) {
                ctx.outLine(operand)
                status = ExecContext.EXIT_GENERAL_ERROR
            } else {
                ctx.outLine("$operand=$value")
            }
        }
        return status
    }

    /** `export FOO=bar` is a special built-in, so the assignment prefix stays in the session. */
    override fun applyPrefix(ctx: ExecContext, assignments: List<Pair<String, String>>) {
        for ((name, value) in assignments) ctx.session.env[name] = value
    }

    private fun isName(s: String): Boolean =
        s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }
}
