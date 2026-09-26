package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

@CommandSpec(
    name = "dirname",
    synopsis = "path",
    group = "files",
    notes = "POSIX semantics: trailing slashes are stripped, `/` stays `/` and a bare name gives `.`",
)
object Dirname : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("dirname: missing operand")
        if (ctx.args.size > 1) return ctx.fail("dirname: extra operand '${ctx.args[1]}'")
        val path = ctx.args[0]
        if (path.isEmpty()) {
            ctx.outLine(".")
            return ExecContext.EXIT_OK
        }
        if (path.all { it == '/' }) {
            ctx.outLine("/")
            return ExecContext.EXIT_OK
        }
        val trimmed = path.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        ctx.outLine(
            when {
                slash < 0 -> "."
                slash == 0 -> "/"
                else -> trimmed.substring(0, slash)
            },
        )
        return ExecContext.EXIT_OK
    }
}
