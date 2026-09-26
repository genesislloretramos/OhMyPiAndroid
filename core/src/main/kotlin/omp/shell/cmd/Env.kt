package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File

@CommandSpec(
    name = "env",
    synopsis = "[NAME=VALUE ...] [cmd [args ...]]",
    group = "builtins",
    notes = "with a command the assignments apply to that command only; with none they change the session",
)
object Env : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        var i = 0
        val assignments = LinkedHashMap<String, String>()
        while (i < operands.size) {
            val op = operands[i]
            val eq = op.indexOf('=')
            if (eq <= 0) break
            val name = op.substring(0, eq)
            if (!isName(name)) {
                ctx.errLine("env: `$op': not a valid identifier")
                return ExecContext.EXIT_USAGE
            }
            assignments[name] = op.substring(eq + 1)
            i++
        }
        val rest = operands.drop(i)

        if (rest.isEmpty()) {
            for ((name, value) in ctx.env) ctx.outLine("$name=$value")
            if (assignments.isNotEmpty()) ctx.session.env.putAll(assignments)
            return ExecContext.EXIT_OK
        }

        val commandName = rest[0]
        val childEnv = LinkedHashMap(ctx.env)
        childEnv.putAll(assignments)
        val target = CommandTable.lookup(commandName)
        if (target != null) {
            val child = ExecContext(
                rest, ctx.stdin, ctx.stdout, ctx.stderr, childEnv,
                ctx.services, ctx.session, ctx.isTty, ctx.cancelled,
            )
            return target.run(child)
        }
        return reportUnrunnable(ctx, commandName)
    }

    private fun isName(s: String): Boolean =
        s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }
}

/** The executor's own verdict for a name it cannot run, with this command's name in front of it. */
internal fun reportUnrunnable(ctx: ExecContext, name: String): Int {
    if (name.contains('/')) {
        val file = File(name)
        if (!file.exists()) {
            ctx.errLine("${ctx.name}: $name: No such file or directory")
            return ExecContext.EXIT_NOT_FOUND
        }
        if (!file.canExecute()) {
            ctx.errLine("${ctx.name}: $name: Permission denied")
            return ExecContext.EXIT_NOT_EXECUTABLE
        }
        ctx.errLine("${ctx.name}: $name: cannot execute binary file: Exec format error")
        return ExecContext.EXIT_NOT_EXECUTABLE
    }
    ctx.errLine("${ctx.name}: $name: command not found")
    return ExecContext.EXIT_NOT_FOUND
}
