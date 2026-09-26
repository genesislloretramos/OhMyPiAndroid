package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File
import java.io.IOException

@CommandSpec(
    name = "mkdir",
    synopsis = "[-p] dir ...",
    group = "files",
    notes = "-p makes missing parents and accepts a directory that is already there",
)
object Mkdir : FileCommand() {

    override val flagSpec = "p"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("mkdir: missing operand")
        val parents = 'p' in flags
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val dir = File(path)
            if (dir.isDirectory) {
                if (!parents) {
                    ctx.errLine("mkdir: $op: File exists")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            if (fsExists(dir)) {
                ctx.errLine("mkdir: $op: File exists")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val failure = try {
                val ok = if (parents) dir.mkdirs() else dir.mkdir()
                if (ok) null else reason(dir, parents)
            } catch (e: IOException) {
                Errno.messageFor(e)
            } catch (e: SecurityException) {
                "Permission denied"
            }
            if (failure != null) {
                ctx.errLine("mkdir: $op: $failure")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }

    private fun reason(dir: File, parents: Boolean): String {
        if (fsExists(dir)) return "File exists"
        if (!parents) return "No such file or directory"
        val parent = dir.parentFile ?: return "No such file or directory"
        return if (parent.exists()) "Not a directory" else "No such file or directory"
    }
}
