package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType

@CommandSpec(
    name = "readlink",
    synopsis = "[-f] file",
    group = "files",
    notes = "without -f it prints the link target verbatim and says nothing about a file that is not a link",
)
object Readlink : FileCommand() {

    override val flagSpec = "f"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("readlink: missing operand")
        val canonicalise = 'f' in flags
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (canonicalise) {
                // -f is the followed-path command: the whole point is the resolved name, links and
                // all, and a path that does not lead anywhere is the one thing it cannot print.
                val target = try {
                    vfs.realpath(path)
                } catch (e: FsException) {
                    ctx.errLine("readlink: $op: ${e.errno.text}")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                ctx.outLine(target)
                continue
            }
            val stat = fsStatOrNull(vfs, path)
            val target = if (stat?.type == VNodeType.SYMLINK) fsLinkTarget(vfs, path) else null
            if (target == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine(target)
        }
        return status
    }
}
