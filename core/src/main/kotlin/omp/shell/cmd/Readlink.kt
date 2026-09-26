package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File

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
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            if (canonicalise) {
                if (!fsExists(file)) {
                    ctx.errLine("readlink: $op: No such file or directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                ctx.outLine(path)
                continue
            }
            val target = if (fsIsLink(file)) fsLinkTarget(file) else null
            if (target == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine(target)
        }
        return status
    }
}
