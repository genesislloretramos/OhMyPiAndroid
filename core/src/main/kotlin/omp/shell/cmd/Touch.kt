package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File
import java.io.IOException

@CommandSpec(
    name = "touch",
    synopsis = "[-c] file ...",
    group = "files",
    notes = "creates the file when it is missing; -c only updates the time of a file that exists",
)
object Touch : FileCommand() {

    override val flagSpec = "c"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("touch: missing operand")
        val noCreate = 'c' in flags
        val now = System.currentTimeMillis()
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            if (!fsExists(file)) {
                if (noCreate) continue
                val created = try {
                    file.createNewFile()
                } catch (e: IOException) {
                    ctx.errLine("touch: $op: ${Errno.messageFor(e)}")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                } catch (e: SecurityException) {
                    ctx.errLine("touch: $op: Permission denied")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (!created) {
                    ctx.errLine("touch: $op: File exists")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            val touched = try {
                file.setLastModified(now)
            } catch (e: SecurityException) {
                false
            }
            if (!touched) {
                ctx.errLine("touch: $op: could not update the modification time")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }
}
