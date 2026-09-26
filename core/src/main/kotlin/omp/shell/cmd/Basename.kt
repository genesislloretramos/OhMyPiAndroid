package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

@CommandSpec(
    name = "basename",
    synopsis = "path [suffix]",
    group = "files",
    notes = "POSIX semantics: trailing slashes are stripped and `/` stays `/`",
)
object Basename : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("basename: missing operand")
        if (ctx.args.size > 2) return ctx.fail("basename: extra operand '${ctx.args[2]}'")
        val path = ctx.args[0]
        if (path.isEmpty()) {
            ctx.outLine()
            return ExecContext.EXIT_OK
        }
        if (path.all { it == '/' }) {
            ctx.outLine("/")
            return ExecContext.EXIT_OK
        }
        val trimmed = path.trimEnd('/')
        var name = trimmed.substringAfterLast('/')
        val suffix = ctx.args.getOrNull(1)
        if (suffix != null && name != suffix && name.length > suffix.length && name.endsWith(suffix)) {
            name = name.substring(0, name.length - suffix.length)
        }
        ctx.outLine(name)
        return ExecContext.EXIT_OK
    }
}
