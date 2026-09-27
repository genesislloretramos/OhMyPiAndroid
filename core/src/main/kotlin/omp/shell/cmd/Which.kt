package omp.shell.cmd

import omp.shell.Session
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * The PATH search `type` and `which` share. A name with a slash is looked up directly, as a shell
 * does; anything else is searched through `$PATH` in order, and the first existing executable file
 * wins. There is no `$0`-relative fallback, because this shell runs nothing by relative path.
 */
internal fun findOnPath(session: Session, name: String): String? {
    val vfs = session.vfs
    if (name.contains('/')) {
        return if (fsIsExecutableFile(vfs, name)) name else null
    }
    for (dir in (session.env["PATH"] ?: "").split(':')) {
        if (dir.isEmpty()) continue
        val candidate = dir.trimEnd('/') + "/" + name
        if (fsIsExecutableFile(vfs, candidate)) return candidate
    }
    return null
}

@CommandSpec(
    name = "which",
    synopsis = "NAME ...",
    group = "builtins",
    notes = "shell builtins have no path, so `which echo` prints nothing for it; use `type` to see those",
)
object Which : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isEmpty()) return ExecContext.EXIT_GENERAL_ERROR
        var status = ExecContext.EXIT_OK
        for (name in operands) {
            val path = findOnPath(ctx.session, name)
            if (path == null) status = ExecContext.EXIT_GENERAL_ERROR else ctx.outLine(path)
        }
        return status
    }
}

/**
 * A registered command, for `type`; the session's table is the only authority on what is built in,
 * so a namespace that registered its own name answers for itself.
 */
internal fun isShellCommand(ctx: ExecContext, name: String): Boolean = ctx.session.table.lookup(name) != null
