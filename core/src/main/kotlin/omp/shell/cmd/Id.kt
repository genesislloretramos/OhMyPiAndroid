package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "id",
    synopsis = "[-unG]",
    group = "system",
    notes = "this app's own uid/gid only; an app cannot see any other process's credentials",
)
object Id : FileCommand() {
    override val flagSpec = "unG"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val uid = ctx.services.appUid()
        val gid = ctx.services.appGid()
        val name = "app_$uid"
        val all = "uid=$uid($name) gid=$gid($name) groups=$gid($name)"
        if (flags.isEmpty()) {
            ctx.outLine(all)
            return ExecContext.EXIT_OK
        }
        val parts = ArrayList<String>()
        if (flags.contains('u')) parts += "uid=$uid($name)"
        if (flags.contains('g')) parts += "gid=$gid($name)"
        if (flags.contains('G')) parts += "groups=$gid($name)"
        if (flags.contains('n')) parts += name
        ctx.outLine(parts.joinToString(" "))
        return ExecContext.EXIT_OK
    }
}

@CommandSpec(
    name = "whoami",
    synopsis = "",
    group = "system",
    notes = "an Android app runs as its own uid, not as 'shell' or 'root'",
)
object Whoami : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        ctx.outLine("app_${ctx.services.appUid()}")
        return ExecContext.EXIT_OK
    }
}
