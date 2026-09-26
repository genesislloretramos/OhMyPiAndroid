package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File

@CommandSpec(
    name = "rm",
    synopsis = "[-rf] file ...",
    group = "files",
    notes = "never prompts and has no -i: there is no second chance once it is running",
)
object Rm : FileCommand() {

    override val flagSpec = "rf"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("rm: missing operand")
        val recursive = 'r' in flags
        val force = 'f' in flags
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val path = Cmds.resolve(ctx, op) ?: run {
                if (!force) status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            if (!fsExists(file)) {
                // -f silences a missing operand, not a failure to remove something that is there.
                if (!force) {
                    ctx.errLine("rm: $op: No such file or directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            val link = fsIsLink(file)
            if (file.isDirectory && !link) {
                if (!recursive) {
                    ctx.errLine("rm: $op: Is a directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (!fsDeleteTree(ctx, "rm", file, ctx.cancelled)) status = ExecContext.EXIT_GENERAL_ERROR
            } else if (!fsDeleteFile(ctx, "rm", op, file)) {
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return status
    }
}
