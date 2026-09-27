package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import omp.shell.fs.FsErrno
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs

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
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val existing = fsStatOrNull(vfs, path)
            if (existing != null) {
                // -p accepts a directory that is already there and refuses a file either way.
                if (!parents || existing.type != VNodeType.DIRECTORY) {
                    ctx.errLine("mkdir: $op: File exists")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            val failure = try {
                // The seam makes one level at a time, so -p is the loop and the rest is a single call.
                val ok = if (parents) fsMakeDirs(vfs, path) else {
                    vfs.mkdir(path)
                    true
                }
                if (ok) null else reason(vfs, path, parents)
            } catch (e: FsException) {
                e.errno.text
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

    /** Why a `mkdir` that did not create the directory did not. */
    private fun reason(vfs: Vfs, path: String, parents: Boolean): String {
        if (fsExists(vfs, path)) return FsErrno.FILE_EXISTS.text
        if (!parents) return FsErrno.NO_SUCH_FILE.text
        val parent = path.substringBeforeLast('/', "")
        return if (parent.isNotEmpty() && fsExists(vfs, parent)) FsErrno.NOT_A_DIRECTORY.text
        else FsErrno.NO_SUCH_FILE.text
    }
}
