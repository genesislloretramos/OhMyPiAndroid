package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "grant-storage",
    synopsis = "",
    group = "builtins",
    notes = "opens the system 'All files access' screen for this package; the framework reports no result, so the grant is re-checked when the app resumes",
)
object GrantStorage : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        ctx.services.requestAllFilesAccess()
        ctx.outLine("Opened the system screen for \"All files access\".")
        ctx.outLine("Nothing comes back from it: the grant is re-read when this app resumes, and the status row drops the hint once it is held.")
        ctx.outLine("Without it, \$HOME (${ctx.services.homeDir()}) is still fully usable; only shared storage needs the grant.")
        return ExecContext.EXIT_OK
    }
}
