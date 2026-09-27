package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException

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
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (!fsExists(vfs, path)) {
                if (noCreate) continue
                // O_CREAT|O_EXCL, so a name that appeared between the two answers as `File exists`
                // and a path with nowhere to put it as the reason the seam could not create it.
                try {
                    vfs.createFile(path)
                } catch (e: FsException) {
                    ctx.errLine("touch: $op: ${e.errno.text}")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                } catch (e: SecurityException) {
                    ctx.errLine("touch: $op: Permission denied")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                continue
            }
            // The name exists; the timestamp belongs to whatever it leads to, as `touch` always has.
            val touched = try {
                vfs.setModified(vfs.realpath(path), now)
                true
            } catch (e: FsException) {
                false
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
