package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "printenv",
    synopsis = "[NAME ...]",
    group = "builtins",
    notes = "prints the process environment, so per-command assignments are included; exit 1 if any name is unset",
)
object Printenv : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isEmpty()) {
            for ((name, value) in ctx.env) ctx.outLine("$name=$value")
            return ExecContext.EXIT_OK
        }
        var status = ExecContext.EXIT_OK
        for (name in operands) {
            val value = ctx.env[name]
            if (value == null) status = ExecContext.EXIT_GENERAL_ERROR else ctx.outLine(value)
        }
        return status
    }
}
