package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "alias",
    synopsis = "[NAME=VALUE ...] | [NAME]",
    group = "builtins",
    notes = "session-only and never persisted; an alias's value is split on spaces, so a quoted argument stays one word only if the expansion kept it quoted",
)
object Alias : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val aliases = ctx.session.aliases
        if (operands.isEmpty()) {
            for ((name, value) in aliases) ctx.outLine("alias $name='$value'")
            return ExecContext.EXIT_OK
        }
        var status = ExecContext.EXIT_OK
        for (operand in operands) {
            val eq = operand.indexOf('=')
            if (eq < 0) {
                val value = aliases[operand]
                if (value == null) {
                    ctx.errLine("alias: $operand: not found")
                    status = ExecContext.EXIT_GENERAL_ERROR
                } else {
                    ctx.outLine("alias $operand='$value'")
                }
                continue
            }
            val name = operand.substring(0, eq)
            if (!isName(name)) {
                ctx.errLine("alias: `$operand': not a valid identifier")
                return ExecContext.EXIT_USAGE
            }
            aliases[name] = operand.substring(eq + 1)
        }
        return status
    }

    private fun isName(s: String): Boolean =
        s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }
}
