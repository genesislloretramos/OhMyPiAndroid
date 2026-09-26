package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "type",
    synopsis = "NAME ...",
    group = "builtins",
    notes = "reports the shell's own idea of a name: a registered command, an alias, or an executable on \$PATH",
)
object Type : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        var status = ExecContext.EXIT_OK
        for (name in operands) {
            when {
                isShellCommand(name) -> ctx.outLine("$name is a shell command")
                ctx.session.aliases.containsKey(name) ->
                    ctx.outLine("$name is aliased to '${ctx.session.aliases[name]}'")
                else -> {
                    val path = findOnPath(ctx.session, name)
                    if (path == null) {
                        ctx.errLine("type: $name: not found")
                        status = ExecContext.EXIT_GENERAL_ERROR
                    } else {
                        ctx.outLine("$name is $path")
                    }
                }
            }
        }
        return status
    }
}
